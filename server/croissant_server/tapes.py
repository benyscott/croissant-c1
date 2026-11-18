"""
A C1's tapes (Recorder recordings) sent whole to this machine, to keep.

The upload is a backup's (backups.py), on the WebSocket `/tape`, with two more fields in the
begin frame: `tape`, the recording's own name on the C1 (`20261118-143000`, when it started),
and `title`, its name as the C1 shows it ("Tape 3", or what it was renamed). It is kept as
`2026-11-18 1430 Interview.wav` in the tapes folder (~/Documents/SideBrain/Tapes) (`-2`, `-3` when that is taken). Nothing
is listed or served back: the folder is the owner's to open.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from datetime import datetime
from typing import Any

from .backups import Backups, Refused

TAPE = re.compile(r"\d{8}-\d{6}", re.ASCII)
# Anything a file system would refuse, or a shell would trip on, leaves a title.
UNSAFE = re.compile(r"[\\/:*?\"<>|\x00-\x1f\x7f]")
TITLE_MAX = 80


def clean_title(title: Any) -> str:
    """A title fit for a file name: no separators or controls, no leading dots, at most TITLE_MAX."""
    text = UNSAFE.sub("", title if isinstance(title, str) else "")
    text = " ".join(text.split()).lstrip(".").strip()
    return text[:TITLE_MAX].rstrip() or "Tape"


class Tapes(Backups):
    what = "tape"

    def _names(self, begin: dict[str, Any]) -> Iterator[str]:
        tape = begin.get("tape")
        if not isinstance(tape, str) or TAPE.fullmatch(tape) is None:
            raise Refused(f"begin needs the tape's name as yyyyMMdd-HHmmss, got {repr(tape)[:80]}")
        started = datetime.strptime(tape, "%Y%m%d-%H%M%S").strftime("%Y-%m-%d %H%M")
        base = f"{started} {clean_title(begin.get('title'))}"
        yield f"{base}.wav"
        n = 2
        while True:
            yield f"{base}-{n}.wav"
            n += 1
