# ADR-0003: Secure Host Bridge and Python Gateway lifecycle boundary

- Status: Implemented in Phase 4
- Date: 2026-08-01

## Context

Alpine tools need LLM access, but Android OAuth access/refresh tokens and
Keystore keys must not cross into the guest. The runtime-only SDK must also
remain usable without any LLM/provider dependency.

## Decision

1. `:alpine-llm-bridge` is the only composition module that depends on the
   Android LLM/provider library and runtime API.
2. `AlpineLlmBridgeController` is the single owner of Host Bridge, runtime
   session, Python Gateway process, capability file, health, stop and restart.
3. The guest receives only a loopback URL and the path of a short-lived
   capability file. OAuth tokens never enter guest environment or config.
4. The Python package is a separate optional artifact with its own version,
   protocol version, checksum and size lock. It is not part of the rootfs.
5. Host Bridge and Gateway protocol version `1` must match before activation.
6. Model selection is fail-closed. Retry count is one for the Gateway-to-Host
   hop to avoid duplicating Android provider retries and charges.
7. PRoot cancellation terminates tracked guest processes, not only the PRoot
   wrapper, so restart cannot leave a stale Gateway on the loopback port.

## Consequences

- Runtime-only apps keep building without Python Gateway and OAuth/provider code.
- LLM-enabled hosts add the bridge and one Gateway artifact provider explicitly.
- Python may be prepackaged by a host or installed deliberately; implicit package
  installation is disabled by default.
- A capability leak grants only temporary access to the app-local bridge, not an
  upstream provider credential; restart rotates it and stop deletes it.
