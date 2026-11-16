"""The bridge's WebSocket: every agent on this computer, served to the R1.

The R1 connects to ws://<address>:<port>/ with `Authorization: Bearer <token>` (from the
pairing QR); without it the handshake gets 401. Then JSON both ways, one message a frame.

The R1 asks, {"id": n, "op": ..., ...}, and gets {"id": n, "result": ...} or {"id": n, "error": words}:
  hello                              {"name": "acp-bridge", "version", "host", "epoch"}
  agents                             [{"id", "name", "ready", "missing", "setup"}]
  sessions {agent, cursor?}          {"sessions": [{"sessionId", "cwd", "title"?, "updatedAt"?,
                                     "running", "asking"}], "nextCursor"?}: the agent's own session/list,
                                     with the sessions started here that it does not list yet, dated
                                     when they were (so a chat just made comes first)
  new {agent, cwd}                   {"sessionId"}: a new session in that folder, watched
  open {agent, sessionId, cwd, after?, epoch?}
                                     {"seq", "epoch"}: watch a session; one not in a turn here
                                     is loaded again first (its history replays as updates), so
                                     what was said on the computer since shows; the updates after
                                     `after` (all, without it) are sent at once. With an epoch
                                     that is not the session's, the R1's seq is from another
                                     log: a reset event, then all of them
  prompt {agent, sessionId, text}    the words go in; the turn's updates follow
  cancel {agent, sessionId}          stop the turn
  permit {agent, sessionId, request, option}
                                     answer a permission, with one of its options' optionId
                                     (null cancels)
  close {agent, sessionId}           stop watching
  alerts                             {"asking": [asking event, ...], "done": [done event, ...],
                                     "running": [{"agent", "sessionId", "doing"}, ...]}:
                                     every permission waiting now and every turn ended unseen, in
                                     any session; from then on this R1 is told each one that comes
                                     ("asking", "done") and each one gone ("answered", "seen"),
                                     watched or not, so it can show them wherever it is
  seen {agent, sessionId}            the session's ended turn is seen (opening it is too)
  ls {cwd, path}                     {"entries": [{"name", "dir", "size", "mtime"}]}: a folder in a
                                     session's folder (files.py), folders first
  read {cwd, path, bytes?}           a file there: {"text"} or {"binary": true}, or with bytes
                                     its {"base64"}; nothing outside the folder, nothing written

The bridge tells, for each watched session:
  {"event": "update", "agent", "sessionId", "seq", "update"}
where update is ACP's session/update as the agent sent it ("sessionUpdate": agent_message_chunk,
tool_call, plan, ...), or one of the bridge's own, each in the same log:
  user_message_chunk                 words the R1 sent (as ACP's history replay has them)
  bridge_permission                  {"request", "toolCall", "options"}: the agent waits for permit
  bridge_permitted                   {"request", "option"}: answered, here or by another R1
  bridge_turn                        {"running": true} at a prompt, {"running": false,
                                     "stopReason"} or {"running": false, "error"} at its end
  {"event": "reset", "agent", "sessionId", "epoch"}
                                     forget what was heard of the session: its log follows anew
And to an R1 that asked for alerts, for every session:
  {"event": "asking", "agent", "sessionId", "cwd", "title", "request", "toolCall", "options"}
                                     title: the conversation's, as its agent lists it, else its
                                     first words
  {"event": "answered", "agent", "sessionId", "request", "option"}
  {"event": "done", "agent", "sessionId", "cwd", "title", "words", "error"?}
                                     a turn ended: words, the start of what the agent said in it
  {"event": "seen", "agent", "sessionId"}
  {"event": "turn", "agent", "sessionId", "running", "doing"?}
                                     a turn began (true) or ended (false); told again while it
                                     runs each time what it does changes: doing, a few words
                                     ("thinking", "running npm test", "editing Shell.kt")
A session's log lives as long as the bridge runs, its seqs its epoch's ([Session.epoch]: the
run's, new at each start, and how often the log was started anew): an R1 that slept opens
again with the last seq it saw and gets only what it missed, a permission still waiting among
it. When a session is loaded again and its history no longer begins with what the log holds
(it was rewritten, or a turn here streamed what the replay has whole), the log starts anew.
"""

import asyncio
import hmac
import json
import logging
import secrets
import signal
import socket
from dataclasses import dataclass, field
from datetime import datetime, timezone
from http import HTTPStatus
from pathlib import Path
from typing import Any

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from . import files
from .acp import AcpError, Agent, Json
from .agents import ALL, Spec
from .config import Config

VERSION = 1
# What a history replay holds: set against a log, the rest (commands, modes, ...) is left out.
HISTORY = {"user_message_chunk", "agent_message_chunk", "agent_thought_chunk", "tool_call",
           "tool_call_update", "plan"}
log = logging.getLogger("acp-bridge")


# What a tool call of each ACP kind is doing, as a verb.
VERBS = {"read": "reading", "edit": "editing", "delete": "deleting", "move": "moving", "search": "searching",
         "execute": "running", "think": "thinking", "fetch": "fetching", "switch_mode": "planning"}


def doing_of(update: Json) -> str | None:
    """A few words for what an update shows the agent doing, or None when it shows nothing new."""
    kind = update.get("sessionUpdate")
    if kind == "agent_thought_chunk":
        return "thinking"
    if kind == "agent_message_chunk":
        return "writing"
    if kind != "tool_call":
        return None
    verb = VERBS.get(update.get("kind", ""), "working")
    what = " ".join(str(update.get("title", "")).replace("`", "").split())
    if verb in ("reading", "editing", "deleting", "moving"):
        what = what.split(" ")[-1].rstrip("/").rsplit("/", 1)[-1]  # "Read /a/b/Core.kt" -> "Core.kt"
    elif what.split(" ", 1)[0].lower() in ("read", "edit", "write", "search", "fetch", "grep", "find"):
        what = what.split(" ", 1)[1] if " " in what else ""
    if what.startswith("mcp__"):  # "mcp__brain__file" -> "using brain file"
        verb, what = "using", " ".join(what.split("__")[1:]).replace("_", " ")
    words = f"{verb} {what}".strip() if what else verb
    return words if len(words) <= 40 else words[:39].rstrip() + "…"


class Refused(Exception):
    """Words for the R1: what it asked cannot be done."""


@dataclass
class Session:
    agent: str
    id: str
    cwd: str
    log: list[Json] = field(default_factory=list)
    watchers: set["Client"] = field(default_factory=set)
    waiting: dict[str, asyncio.Future] = field(default_factory=dict)
    asks: dict[str, Json] = field(default_factory=dict)  # the waiting ones' "asking" events
    running: bool = False
    loaded: bool = False  # the agent process running now has it (new here, or loaded)
    replay: list[Json] | None = None  # while loading: its history as it comes, set against the log after
    gen: int = 0  # how often the log was started anew
    opening: asyncio.Lock = field(default_factory=asyncio.Lock)
    turn: asyncio.Task | None = None
    made: datetime | None = None  # when it was made here: its date until its agent gives one
    title: str | None = None  # as its agent last listed it
    doing: str | None = None  # what its running turn does now, in a few words

    def name(self) -> str:
        """The conversation's title, else its first words, short."""
        if self.title:
            return self.title
        for e in self.log:
            u = e["update"]
            if u.get("sessionUpdate") == "user_message_chunk" and (t := u.get("content", {}).get("text", "").strip()):
                return t if len(t) <= 60 else t[:59].rstrip() + "…"
        return ""

    def epoch(self, run: str) -> str:
        return f"{run}.{self.gen}"

    def add(self, update: Json) -> None:
        entry = {"event": "update", "agent": self.agent, "sessionId": self.id,
                 "seq": len(self.log) + 1, "update": update}
        self.log.append(entry)
        for client in self.watchers:
            client.tell(entry)


class Client:
    """One R1 on the socket. What it is told goes through a queue, so a slow R1 never holds up an agent."""

    def __init__(self, ws: ServerConnection):
        self.ws = ws
        self.out: asyncio.Queue[Json] = asyncio.Queue()

    def tell(self, message: Json) -> None:
        self.out.put_nowait(message)

    async def write(self) -> None:
        while True:
            await self.ws.send(json.dumps(await self.out.get(), ensure_ascii=False))


class Bridge:
    def __init__(self, config: Config):
        self.config = config
        self.agents: dict[str, Agent] = {}
        self.starting: dict[str, asyncio.Task] = {}
        self.sessions: dict[tuple[str, str], Session] = {}
        self.alerted: set[Client] = set()  # told every permission, in any session
        self.done: dict[tuple[str, str], Json] = {}  # a turn ended and not seen yet, by session
        self.epoch = secrets.token_hex(4)

    # ---- agents ----------------------------------------------------------------------------

    def spec(self, id_: str) -> Spec:
        if id_ not in ALL:
            raise Refused(f"no agent {id_}")
        spec = ALL[id_]
        if missing := spec.missing():
            raise Refused(f"{spec.name} needs {', '.join(missing)}")
        return spec

    async def agent(self, id_: str) -> Agent:
        """The agent's process, started at first use, again if it died; one for all its sessions."""
        running = self.agents.get(id_)
        if running and running.proc and running.proc.returncode is None:
            return running
        if id_ not in self.starting:
            self.starting[id_] = asyncio.ensure_future(self._start(id_))
        try:
            return await asyncio.shield(self.starting[id_])
        finally:
            self.starting.pop(id_, None)

    async def _start(self, id_: str) -> Agent:
        spec = self.spec(id_)
        for (agent_id, _), s in self.sessions.items():
            if agent_id == id_:  # its process is gone: the session is loaded again when opened
                s.running = s.loaded = False
        agent = Agent(spec.argv, str(Path.home()), spec.env,
                      on_notify=lambda m, p: self._notify(id_, m, p),
                      on_request=lambda m, p: self._request(id_, m, p))
        await agent.start()
        log.info("%s started: %s", spec.name, agent.info.get("agentInfo", {}))
        self.agents[id_] = agent
        return agent

    async def _notify(self, agent: str, method: str, params: Json) -> None:
        s = self.sessions.get((agent, params.get("sessionId")))
        if method == "session/update" and s:
            if s.replay is not None:
                s.replay.append(params.get("update", {}))
            else:
                s.add(params.get("update", {}))
                if s.running and (doing := doing_of(params.get("update", {}))) and doing != s.doing:
                    s.doing = doing
                    self.turn(s)

    async def _request(self, agent: str, method: str, params: Json) -> Json:
        if method != "session/request_permission":
            raise AcpError({"code": -32601, "message": f"{method} not offered"})
        s = self.sessions.get((agent, params.get("sessionId")))
        if not s:
            return {"outcome": {"outcome": "cancelled"}}
        request = str(len(s.log) + 1)
        future = asyncio.get_running_loop().create_future()
        s.waiting[request] = future
        s.add({"sessionUpdate": "bridge_permission", "request": request,
               "toolCall": params.get("toolCall", {}), "options": params.get("options", [])})
        s.asks[request] = {"event": "asking", "agent": agent, "sessionId": s.id, "cwd": s.cwd, "title": s.name(),
                           "request": request,
                           "toolCall": params.get("toolCall", {}), "options": params.get("options", [])}
        for client in self.alerted:
            client.tell(s.asks[request])
        option = None
        try:
            option = await future
        finally:
            s.waiting.pop(request, None)
            s.asks.pop(request, None)
            for client in self.alerted:
                client.tell({"event": "answered", "agent": agent, "sessionId": s.id, "request": request, "option": option})
        s.add({"sessionUpdate": "bridge_permitted", "request": request, "option": option})
        if option is None:
            return {"outcome": {"outcome": "cancelled"}}
        return {"outcome": {"outcome": "selected", "optionId": option}}

    # ---- what the R1 asks -------------------------------------------------------------------

    async def handle(self, client: Client, op: str, m: Json) -> Any:
        if op == "hello":
            return {"name": "acp-bridge", "version": VERSION, "host": socket.gethostname(), "epoch": self.epoch}
        if op == "agents":
            return [{"id": s.id, "name": s.name, "ready": not s.missing(), "missing": s.missing(),
                     "setup": s.setup} for s in ALL.values()]
        if op == "alerts":
            self.alerted.add(client)
            return {"asking": [a for s in self.sessions.values() for a in s.asks.values()],
                    "done": list(self.done.values()),
                    "running": [{"agent": s.agent, "sessionId": s.id, "doing": s.doing} for s in self.sessions.values() if s.running]}
        agent_id = m.get("agent", "")
        if op in ("ls", "read"):
            try:
                if op == "ls":
                    return files.listing(m["cwd"], m.get("path", ""))
                return files.read(m["cwd"], m["path"], bool(m.get("bytes")))
            except files.Outside as e:
                raise Refused(str(e))
        if op == "sessions":
            return await self.list(agent_id, m.get("cursor"))
        if op == "new":
            agent = await self.agent(agent_id)
            cwd = str(Path(m["cwd"]).expanduser().resolve())
            if not Path(cwd).is_dir():
                raise Refused(f"no folder {cwd}")
            made = await agent.call("session/new", {"cwd": cwd, "mcpServers": []})
            s = self.sessions[(agent_id, made["sessionId"])] = Session(agent_id, made["sessionId"], cwd,
                                                                      made=datetime.now(timezone.utc))
            s.loaded = True
            await self.mode(agent_id, s)
            s.watchers.add(client)
            return {"sessionId": s.id}
        key = (agent_id, m.get("sessionId", ""))
        if op == "seen":
            self.seen(key)
            return {}
        if op == "open":
            self.seen(key)
            if held := self.sessions.get(key):
                held.watchers.discard(client)  # what it gets of the session follows here, once
            s = await self.open(agent_id, m["sessionId"], m.get("cwd"))
            after = int(m.get("after") or 0)
            epoch = s.epoch(self.epoch)
            if m.get("epoch") and m["epoch"] != epoch:
                client.tell({"event": "reset", "agent": agent_id, "sessionId": s.id, "epoch": epoch})
                after = 0
            for entry in s.log[after:]:
                client.tell(entry)
            s.watchers.add(client)
            return {"seq": len(s.log), "epoch": epoch}
        if not (s := self.sessions.get(key)):
            raise Refused("open the session first")
        if op == "close":
            s.watchers.discard(client)
            return {}
        if op == "prompt":
            if s.running:
                raise Refused("a turn is running")
            s.add({"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": m["text"]}})
            s.turn = asyncio.ensure_future(self.prompt(s, m["text"]))
            return {}
        if op == "cancel":
            for future in s.waiting.values():
                if not future.done():
                    future.set_result(None)
            await (await self.agent(agent_id)).notify("session/cancel", {"sessionId": s.id})
            return {}
        if op == "permit":
            future = s.waiting.get(str(m.get("request")))
            if not future or future.done():
                raise Refused("nothing waits for that")
            future.set_result(m.get("option"))
            return {}
        raise Refused(f"no op {op}")

    async def list(self, agent_id: str, cursor: str | None) -> Json:
        agent = await self.agent(agent_id)
        if not agent.can_list:
            listed: Json = {"sessions": []}
        else:
            listed = await agent.call("session/list", {"cursor": cursor} if cursor else {})
        known = {x["sessionId"] for x in listed.get("sessions", [])}
        extra = [] if cursor else [
            {"sessionId": s.id, "cwd": s.cwd} for (a, _), s in self.sessions.items()
            if a == agent_id and s.id not in known]
        for x in extra + listed.get("sessions", []):
            s = self.sessions.get((agent_id, x["sessionId"]))
            if s and s.made and not x.get("updatedAt"):
                x["updatedAt"] = s.made.isoformat().replace("+00:00", "Z")
            if s and x.get("title"):
                s.title = x["title"]
            x["running"] = bool(s and s.running)
            x["asking"] = bool(s and s.waiting)  # a permission waits on the R1
        return {**listed, "sessions": extra + listed.get("sessions", [])}

    async def open(self, agent_id: str, session_id: str, cwd: str | None) -> Session:
        """The session, loaded from its agent again unless a turn of it runs here: the agent may
        have more of it than the log (said on the computer, or by another app)."""
        agent = await self.agent(agent_id)
        s = self.sessions.get((agent_id, session_id))
        if not cwd and not s:
            raise Refused("which folder is it in")
        if not s:
            s = self.sessions[(agent_id, session_id)] = Session(agent_id, session_id, str(Path(cwd).expanduser().resolve()))
        async with s.opening:
            if s.running or s.waiting or (s.loaded and not s.log):  # live here, or new and empty
                return s
            if not agent.can_load:
                if not s.loaded:
                    await agent.call("session/resume", {"sessionId": s.id, "cwd": s.cwd, "mcpServers": []})
                    s.loaded = True
                    await self.mode(agent_id, s)
                return s
            s.replay = []
            try:
                await agent.call("session/load", {"sessionId": s.id, "cwd": s.cwd, "mcpServers": []})
                replay = s.replay
            except AcpError as e:
                if not s.log:
                    raise
                log.warning("%s %s not loaded again, its log as it was: %s", agent_id, s.id, e)
                return s
            finally:
                s.replay = None
            s.loaded = True
            await self.mode(agent_id, s)
            self.heard(s, replay)
            return s

    async def mode(self, agent_id: str, s: Session) -> None:
        """The agent's own mode for the session ([Spec.mode]), when it has one; not set, it runs as it would."""
        if not (mode := self.spec(agent_id).mode):
            return
        try:
            await (await self.agent(agent_id)).call("session/set_mode", {"sessionId": s.id, "modeId": mode})
        except AcpError as e:
            log.warning("%s %s: mode %s not set: %s", agent_id, s.id, mode, e)

    def heard(self, s: Session, replay: "list[Json]") -> None:
        """A session's history as its agent replayed it, set against its log: only the new end
        is added when the history begins with the log, else the log starts anew from it."""
        held = [e["update"] for e in s.log if e["update"].get("sessionUpdate") in HISTORY]
        said = [u for u in replay if u.get("sessionUpdate") in HISTORY]
        if said[:len(held)] == held:
            for update in said[len(held):]:
                s.add(update)
            return
        s.gen += 1
        s.log = []
        for client in s.watchers:
            client.tell({"event": "reset", "agent": s.agent, "sessionId": s.id, "epoch": s.epoch(self.epoch)})
        for update in replay:
            s.add(update)

    async def prompt(self, s: Session, text: str) -> None:
        s.running = True
        s.doing = "thinking"
        s.add({"sessionUpdate": "bridge_turn", "running": True})
        self.turn(s)
        try:
            agent = await self.agent(s.agent)
            done = await agent.call("session/prompt", {
                "sessionId": s.id, "prompt": [{"type": "text", "text": text}]})
            s.running = False
            s.add({"sessionUpdate": "bridge_turn", "running": False, "stopReason": done.get("stopReason")})
            self.ended(s, None)
        except Exception as e:
            s.running = False
            s.add({"sessionUpdate": "bridge_turn", "running": False, "error": str(e)})
            self.ended(s, str(e))

    def ended(self, s: Session, error: str | None) -> None:
        """A turn of [s] ended: kept as not seen, and told to every R1 that asked for alerts."""
        said: list[str] = []
        for e in reversed(s.log):
            u = e["update"]
            kind = u.get("sessionUpdate")
            if kind == "user_message_chunk":
                break
            if kind == "agent_message_chunk" and u.get("content", {}).get("type") == "text":
                said.append(u["content"].get("text", ""))
        words = " ".join("".join(reversed(said)).split())
        self.turn(s)
        notice = {"event": "done", "agent": s.agent, "sessionId": s.id, "cwd": s.cwd, "title": s.name(),
                  "words": words if len(words) <= 200 else words[:199].rstrip() + "…"}
        if error:
            notice["error"] = error
        self.done[(s.agent, s.id)] = notice
        for client in self.alerted:
            client.tell(notice)

    def turn(self, s: Session) -> None:
        event = {"event": "turn", "agent": s.agent, "sessionId": s.id, "running": s.running}
        if s.running and s.doing:
            event["doing"] = s.doing
        for client in self.alerted:
            client.tell(event)

    def seen(self, key: tuple[str, str]) -> None:
        if self.done.pop(key, None):
            for client in self.alerted:
                client.tell({"event": "seen", "agent": key[0], "sessionId": key[1]})

    # ---- the socket -------------------------------------------------------------------------

    def check(self, connection: ServerConnection, request):
        given = request.headers.get("Authorization", "")
        if not hmac.compare_digest(given.encode(), f"Bearer {self.config.token}".encode()):
            log.warning("refused %s: no token or a wrong one", connection.remote_address[0])
            return connection.respond(HTTPStatus.UNAUTHORIZED, "token\n")
        return None

    async def connected(self, ws: ServerConnection) -> None:
        client = Client(ws)
        writer = asyncio.ensure_future(client.write())
        log.info("R1 connected from %s", ws.remote_address[0])
        try:
            async for raw in ws:
                try:
                    m = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                asyncio.ensure_future(self.reply(client, m))
        except ConnectionClosed:
            pass
        finally:
            writer.cancel()
            self.alerted.discard(client)
            for s in self.sessions.values():
                s.watchers.discard(client)
            log.info("R1 left %s", ws.remote_address[0])

    async def reply(self, client: Client, m: Json) -> None:
        answer: Json = {"id": m.get("id")}
        try:
            answer["result"] = await self.handle(client, m.get("op", ""), m)
        except Refused as e:
            answer["error"] = str(e)
        except AcpError as e:
            answer["error"] = str(e)
        except Exception as e:
            log.exception("%s failed", m.get("op"))
            answer["error"] = f"{type(e).__name__}: {e}"
        client.tell(answer)

    async def run(self) -> None:
        loop = asyncio.get_running_loop()
        stop = loop.create_future()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, lambda: stop.done() or stop.set_result(None))
        try:
            async with serve(self.connected, "0.0.0.0", self.config.port,
                             process_request=self.check, max_size=16 * 1024 * 1024):
                log.info("listening on port %d", self.config.port)
                await stop
        finally:
            await asyncio.gather(*(a.stop() for a in self.agents.values()))
            log.info("stopped, and every agent with it")
