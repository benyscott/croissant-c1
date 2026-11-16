"""The R1's Terminal: a command run on this computer, its output told as it comes.

Not an agent: the bridge runs it itself (server.py, ops term and term_cancel). Each command
runs in a login shell ($SHELL -l -c) in the folder the R1 says, with no input and no colours,
its output and errors together. The folder it ends in (after a cd) goes back to the R1, which
sends it with the next command. Nothing asks first: whoever holds the bridge's token can run
anything as this user, as they already can through the agents.
"""

import asyncio
import codecs
import os
import re
import shlex
import signal
import tempfile
from collections.abc import Callable
from pathlib import Path

MAX_CHARS = 100_000  # of one command's output; the rest is dropped
ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07]*\x07|\r(?!\n)")
QUIET_ENV = {"TERM": "dumb", "PAGER": "cat", "GIT_PAGER": "cat", "NO_COLOR": "1", "CLICOLOR": "0"}


def shown(path: Path) -> str:
    """A folder as a prompt shows it: ~ for home."""
    home = str(Path.home())
    p = str(path)
    return "~" + p[len(home):] if p == home or p.startswith(home + "/") else p


class Command:
    """One command running for one R1; [stop] ends it and everything it started."""

    def __init__(self) -> None:
        self.proc: asyncio.subprocess.Process | None = None

    async def run(self, command: str, cwd: str, out: Callable[[str], None]) -> tuple[int, bool, str]:
        """Run [command] in [cwd] (~ allowed), [out] hearing its output; its exit code, whether
        it was stopped, and the folder it ended in as a prompt shows it."""
        folder = Path(cwd or "~").expanduser()
        if not folder.is_dir():
            folder = Path.home()
        fd, where = tempfile.mkstemp(prefix="acp-term-")
        os.close(fd)
        script = f"trap 'printf %s \"$PWD\" > {shlex.quote(where)}' EXIT\n{command}"
        self.proc = await asyncio.create_subprocess_exec(
            os.environ.get("SHELL", "/bin/zsh"), "-l", "-c", script, cwd=folder,
            env={**os.environ, **QUIET_ENV}, stdin=asyncio.subprocess.DEVNULL,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT, start_new_session=True)
        decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
        sent, cut = 0, False
        try:
            while chunk := await self.proc.stdout.read(4096):
                text = ANSI.sub("", decoder.decode(chunk))
                if cut or not text:
                    continue
                if sent + len(text) > MAX_CHARS:
                    text, cut = text[:MAX_CHARS - sent] + "\n[output cut]", True
                sent += len(text)
                out(text)
            code = await self.proc.wait()
            try:
                moved = Path(Path(where).read_text().strip() or folder)
            except OSError:
                moved = folder
            return code, code in (-signal.SIGTERM, -signal.SIGKILL), shown(moved if moved.is_dir() else folder)
        finally:
            self.proc = None
            Path(where).unlink(missing_ok=True)

    async def stop(self) -> None:
        proc = self.proc
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
