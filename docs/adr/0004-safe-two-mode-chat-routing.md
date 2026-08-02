# ADR-0004: Safe two-mode chat routing and fallback boundary

- Status: Implemented in Phase 5
- Date: 2026-08-01
- Governing plan: `dev-plan/implement_20260801_151654.md`

## Context

One host app may offer direct Android Provider chat and Alpine workspace chat. A backend switch
after Provider dispatch can duplicate a response or charge, especially because the supported
Providers do not yet have a verified common idempotency-key contract.

## Decision

1. `:alpine-chat-routing` owns Android-free request, stream, failure, audit, mode-store and request
   ledger contracts.
2. `:alpine-chat-backend-direct` adapts the existing Android OAuth/Provider session without taking
   a runtime dependency.
3. `:alpine-chat-backend-alpine` adapts `AlpineLlmBridgeController` and the loopback Python Gateway.
4. Runtime not-installed, repair-required, busy, and start failures are preparation failures.
   Provider/auth/network/response failures are dispatch or streaming failures.
5. Alpine-to-direct fallback is offered only for an eligible preparation failure and requires a
   host approval callback before direct Provider dispatch.
6. A first delta, or any failure after dispatch even before a delta, permanently forbids automatic
   fallback for that request.
7. A bounded request ledger rejects concurrent and completed request-ID reuse. Routing, fallback,
   backend, mode, model, first delta and terminal state are emitted as closed audit events.
8. Current adapters declare idempotency `NONE`. A Provider-specific key may be enabled only after
   its upstream contract and forwarding behavior are verified.

## Consequences

- Fast-chat-only apps can use the routing core/direct adapter without Alpine modules or payloads.
- Integrated hosts add the Alpine adapter explicitly.
- UI owns the approval dialog and mode selector; the SDK never silently changes mode.
- A declined fallback emits no Provider stream and can be retried with a new request ID.
- Existing saved conversations migrate to `FAST_CHAT`; new conversation and index schemas persist
  execution mode explicitly.
