# ADR-0001: Reusable Alpine runtime module boundary

- Status: Implemented through Phase 3
- Date: 2026-08-01
- Governing plan: `dev-plan/implement_20260801_151654.md`

## Context

The repository previously combined reusable OAuth/provider code and the initial
`AlpineRuntime` wrapper in `:android`. The `:demo-chatbot` application uses the
provider code but does not use the runtime. Keeping rootfs, PRoot, terminal, or
package UI in that module would make non-Alpine host apps inherit large native
assets and app-specific UI.

## Decision

The long-term SDK is split along these dependency boundaries:

1. `:alpine-runtime-api` owns app-neutral lifecycle and session contracts.
2. `:alpine-runtime-android` implements Android storage/process behavior.
3. Runtime artifact providers remain optional and store-specific.
4. `:alpine-llm-bridge` is the only module that combines runtime and Android
   OAuth/provider sessions.
5. `:alpine-runtime-ui-compose` is optional and depends on contracts, not on
   provider implementations.
6. `:demo-chatbot` remains the direct-provider reference app and must not
   depend on runtime modules.
7. `:integrated-app` will be a host/composition app, not the owner of reusable
   runtime implementation.

The `:alpine-runtime-probe` app is a disposable product-independent fixture. It
depends only on `:alpine-runtime-android` and `:alpine-runtime-pack-bundled` and
therefore verifies the same public factory path used by an external host.

## Lifecycle ownership

The host application owns user-visible lifecycle decisions. The SDK owns one
runtime process controller per app process and exposes state. Foreground
service, notification, UI, retry, repair, and reset decisions are initiated by
the host through public contracts. An SDK UI is only a convenience client of
the same contracts.

## Consequences

- Apps that only need direct LLM chat keep using `:android` without runtime
  binaries or Compose runtime UI.
- Apps that only need Alpine do not inherit OAuth/provider dependencies.
- No circular dependency from runtime core to Host Bridge is allowed.
- The old `dev.alpine.llm.AlpineRuntime` API was removed rather than retained as
  a facade, because a facade in `:android` would force runtime code or payloads
  into direct-chat-only apps. The 0.x migration is documented separately.
