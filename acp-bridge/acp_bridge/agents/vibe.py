"""Mistral Vibe, which speaks ACP itself: `vibe-acp`.

It runs with the key Vibe is set up with (`vibe` once, ~/.vibe); the bridge never reads it. A
folder's own .vibe/config.toml (its MCP servers, tool permissions) is read only while the
folder is trusted in ~/.vibe/trusted_folders.toml.
"""

from . import Spec

SPEC = Spec(
    id="vibe",
    name="Vibe",
    argv=["vibe-acp"],
    needs=["vibe-acp"],
    setup="Install Mistral Vibe (`uv tool install mistral-vibe`) and run `vibe` once to sign in.",
)
