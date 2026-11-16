"""A shell on this computer, as an ACP agent: each prompt is a command, its output the reply.

`python -m acp_bridge.shell` speaks ACP on stdio, like the other agents' adapters. A session
is a folder; a command that changes folder (cd) moves the session there. Each command runs in
a login shell ($SHELL -l -c), with no input and no colours, its output and errors together as
they come. Cancel stops it and everything it started. Nothing asks first: whoever holds the
bridge's token can run anything as this user, as they already can through the other agents.
"""

import asyncio
import codecs
import json
import os
import re
import shlex
import shutil
import signal
import sys
import tempfile
import uuid
from pathlib import Path

MAX_CHARS = 100_000  # of one command's output; the rest is dropped
ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07|\r(?!\n)")
QUIET_ENV = {"TERM": "dumb", "PAGER": "cat", "GIT_PAGER": "cat", "NO_COLOR": "1", "CLICOLOR": "0"}

sessions: dict[str, Path] = {}  # sessionId: its folder now
running: dict[str, asyncio.subprocess.Process] = {}
out_lock = asyncio.Lock()


async def send(message: dict) -> None:
    async with out_lock:
        sys.stdout.write(json.dumps(message, ensure_ascii=False) + "\n")
        sys.stdout.flush()


async def say(session_id: str, text: str) -> None:
    await send({"jsonrpc": "2.0", "method": "session/update", "params": {
        "sessionId": session_id,
        "update": {"sessionUpdate": "agent_message_chunk", "content": {"type": "text", "text": text}}}})


def command_of(prompt: list[dict]) -> str:
    """The command in the prompt, undone from dictation: a final full stop after a word goes,
    and a capital the speech put on a command that exists only in lower case."""
    text = "".join(b.get("text", "") for b in prompt if b.get("type") == "text").strip()
    if re.search(r"\w\.$", text) and "\n" not in text:
        text = text[:-1]
    first, _, rest = text.partition(" ")
    if first[:1].isupper() and not shutil.which(first) and shutil.which(first.lower()):
        text = first.lower() + (" " + rest if rest else "")
    return text


async def run(session_id: str, command: str) -> str:
    cwd = sessions[session_id]
    fd, where = tempfile.mkstemp(prefix="acp-shell-")
    os.close(fd)
    script = f"trap 'printf %s \"$PWD\" > {shlex.quote(where)}' EXIT\n{command}"
    proc = await asyncio.create_subprocess_exec(
        os.environ.get("SHELL", "/bin/zsh"), "-l", "-c", script, cwd=cwd,
        env={**os.environ, **QUIET_ENV}, stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, start_new_session=True)
    running[session_id] = proc
    decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
    sent, said_any, cut = 0, False, False
    try:
        while chunk := await proc.stdout.read(4096):
            text = ANSI.sub("", decoder.decode(chunk))
            if cut or not text:
                continue
            if sent + len(text) > MAX_CHARS:
                text, cut = text[:MAX_CHARS - sent] + "\n[output cut]", True
            sent += len(text)
            said_any = True
            await say(session_id, text)
        code = await proc.wait()
    finally:
        running.pop(session_id, None)
    cancelled = code in (-signal.SIGTERM, -signal.SIGKILL)
    tail = []
    if code and not cancelled:
        tail.append(f"[exit {code}]")
    try:
        moved = Path(Path(where).read_text().strip() or cwd)
    except OSError:
        moved = cwd
    finally:
        Path(where).unlink(missing_ok=True)
    if moved != cwd and moved.is_dir():
        sessions[session_id] = moved
        tail.append(f"[now in {str(moved).replace(str(Path.home()), '~', 1)}]")
    if not said_any and not tail:
        tail.append("[done]")
    if tail:
        await say(session_id, ("\n" if said_any else "") + " ".join(tail))
    return "cancelled" if cancelled else "end_turn"


async def stop(session_id: str) -> None:
    proc = running.get(session_id)
    if not proc:
        return
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(proc.pid, sig)
        except ProcessLookupError:
            return
        try:
            await asyncio.wait_for(proc.wait(), 2)
            return
        except TimeoutError:
            pass


async def handle(method: str, params: dict):
    if method == "initialize":
        return {"protocolVersion": 1, "agentCapabilities": {"loadSession": False},
                "agentInfo": {"name": "shell", "version": "0.1.0"}, "authMethods": []}
    if method == "session/new":
        id_ = uuid.uuid4().hex
        sessions[id_] = Path(params["cwd"])
        return {"sessionId": id_}
    if method == "session/resume":  # known again after a restart, its history gone
        sessions.setdefault(params["sessionId"], Path(params["cwd"]))
        return {}
    if method == "session/prompt":
        id_ = params["sessionId"]
        if id_ not in sessions:
            raise LookupError(f"no session {id_}")
        command = command_of(params.get("prompt", []))
        if not command:
            return {"stopReason": "end_turn"}
        return {"stopReason": await run(id_, command)}
    raise NotImplementedError(method)


async def answer(message: dict) -> None:
    reply: dict = {"jsonrpc": "2.0", "id": message["id"]}
    try:
        reply["result"] = await handle(message["method"], message.get("params") or {})
    except NotImplementedError as e:
        reply["error"] = {"code": -32601, "message": f"no method {e}"}
    except Exception as e:
        reply["error"] = {"code": -32603, "message": str(e)}
    await send(reply)


async def main() -> None:
    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader(limit=16 * 1024 * 1024)
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    tasks: set[asyncio.Task] = set()
    while line := await reader.readline():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "id" in message and "method" in message:
            task = asyncio.ensure_future(answer(message))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
        elif message.get("method") == "session/cancel":
            await stop((message.get("params") or {}).get("sessionId", ""))
    for id_ in list(running):
        await stop(id_)


if __name__ == "__main__":
    asyncio.run(main())
