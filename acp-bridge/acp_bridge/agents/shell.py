"""A shell on this computer: each message is a command, run in the session's folder.

The bridge's own (acp_bridge/shell.py), so nothing to install. It never asks before running.
"""

import sys

from . import Spec

SPEC = Spec(
    id="shell",
    name="Shell",
    argv=[sys.executable, "-m", "acp_bridge.shell"],
    needs=[],
    setup="Nothing to set up.",
)
