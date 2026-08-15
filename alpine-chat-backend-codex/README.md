# Alpine Codex chat backend

Synthetic `Codex Agent (ChatGPT 로그인)` backend for the common chat feature.

- It receives only the current user message from the host request.
- Host conversation IDs are linked to private Codex thread IDs; prompt text is never stored here.
- Automatic correction replay is disabled.
- Command, file, tool, web, image, sleep, or sub-agent items fail closed and interrupt the turn.
- It never routes through Direct Provider OAuth or Alpine Runtime.
