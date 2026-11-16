"""Claude Code, through the ACP adapter built on the Claude Agent SDK.

github.com/agentclientprotocol/claude-agent-acp. CLAUDE_CODE_EXECUTABLE points it at the
user's own claude, so it runs under that install's sign-in (or ANTHROPIC_API_KEY) and its
sessions, rather than the copy the SDK bundles. The bridge never reads either.
"""

import shutil

from . import Spec

ADAPTER = "@agentclientprotocol/claude-agent-acp@0.94.0"

SPEC = Spec(
    id="claude",
    name="Claude",
    argv=["npx", "-y", ADAPTER],
    needs=["npx", "claude"],
    setup="Install Claude Code (claude.com/claude-code) and sign in once with `claude`.",
    env={"CLAUDE_CODE_EXECUTABLE": shutil.which("claude") or "claude"},
    # Claude decides its own permissions: routine actions go ahead, risky ones still ask the R1.
    # Only the R1's sessions: Claude Code in a terminal keeps its own mode.
    mode="auto",
)
