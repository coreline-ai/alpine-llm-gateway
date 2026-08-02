# Alpine LLM Bridge Probe

Debug-only Phase 4 host app. It proves the optional composition:

`Alpine llmctl -> Python Gateway -> loopback Host Bridge -> Android LLM executor`

It installs Python only when explicitly enabled by the probe, validates
`models/run/stream/cancel`, rotates the capability on restart, and verifies
process cleanup. It is separate from the runtime-only probe so an app that does
not use LLM integration does not receive the Python Gateway or Android Provider.
