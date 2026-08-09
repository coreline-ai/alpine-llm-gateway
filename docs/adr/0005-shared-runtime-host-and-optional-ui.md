# ADR-0005: Shared runtime host controller and optional UI

- Status: Implemented in Phase 6
- Date: 2026-08-01
- Governing plan: `dev-plan/implement_20260801_151654.md`

## Context

SDK Compose UI and a fully custom XML/View host must produce the same lifecycle result without
copying Activity-specific state logic into each application. Rotation must not stop a runtime, and a
new process must not restore a stale RUNNING flag from UI persistence.

## Decision

1. `:alpine-runtime-host` is an Android-free controller over `AlpineRuntimeManager`.
2. The controller owns only session references, bounded terminal/command presentation state, and
   closed error codes. The host Application or Service owns the manager lifetime.
3. `:alpine-runtime-ui-compose` is a stateless optional renderer of `RuntimeHostState`.
4. `:alpine-integration-sample` proves XML/View integration without Compose or demo code.
5. `:integrated-app` proves Compose assembly and explicit `FAST_CHAT`/`ALPINE_WORKSPACE` selection.
6. Package mutation accepts validated package names, an exact allowlist, and explicit approval;
   a fixed non-mutating `apk --simulate` runs first and a fixed mutation argv can be dispatched only
   after that simulation succeeds. A simulation cannot be interpreted as a fresh-index, network,
   capacity, or full transaction guarantee.
7. Closing or rotating a screen removes listeners but never silently stops the runtime.

## Consequences

- Product apps can replace all SDK UI without replacing lifecycle behavior.
- Compose remains absent from runtime core and custom hosts.
- A Foreground Service and notification remain host policy because Android execution purpose and
  user-facing text are product-specific.
- Terminal output is bounded. The Android adapter opens `/dev/ptmx`, launches PRoot under a
  controlling terminal, and applies its initial window size; a pipe fallback remains fail-safe when
  the supported native ABI is unavailable. The pinned PRoot does not propagate a later master
  `TIOCSWINSZ` to the guest, so the public session reports `INITIAL_SIZE_ONLY` and rejects dynamic
  resize rather than returning a misleading success.
