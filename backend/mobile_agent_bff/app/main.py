from __future__ import annotations

import asyncio
import json
from contextlib import suppress
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import StreamingResponse

from .auth import OidcVerifier
from .config import Settings
from .models import AuthPrincipal, ChatStreamRequest, ProviderDescription, ProviderName
from .providers import (
    AnthropicAdapter,
    OpenAIAdapter,
    ProviderAdapter,
    ProviderEvent,
    ProviderStreamError,
    XaiAdapter,
)
from .registry import DuplicateRequestError, RequestLimitError, RequestRegistry


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    verifier = OidcVerifier(settings)
    registry = RequestRegistry(settings.max_concurrent_requests)
    adapters: dict[ProviderName, ProviderAdapter] = {
        ProviderName.OPENAI: OpenAIAdapter(
            settings.openai_api_key,
            settings.openai_models,
            settings.request_timeout_seconds,
        ),
        ProviderName.ANTHROPIC: AnthropicAdapter(
            settings.anthropic_api_key,
            settings.anthropic_models,
            settings.request_timeout_seconds,
        ),
        ProviderName.XAI: XaiAdapter(
            settings.xai_api_key,
            settings.xai_models,
            settings.request_timeout_seconds,
        ),
    }

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        yield
        await verifier.close()
        for adapter in adapters.values():
            await adapter.close()

    application = FastAPI(
        title="MobileAgent LLM BFF",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    application.state.settings = settings
    application.state.verifier = verifier
    application.state.registry = registry
    application.state.adapters = adapters

    @application.get("/healthz")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @application.get("/v1/session")
    async def session(principal: AuthPrincipal = Depends(verifier)) -> dict[str, object]:
        return {
            "authenticated": True,
            "subject": principal.subject,
            "expiresAt": principal.expires_at,
        }

    @application.get("/v1/providers", response_model=list[ProviderDescription])
    async def providers(
        principal: AuthPrincipal = Depends(verifier),
    ) -> list[ProviderDescription]:
        del principal
        return [
            ProviderDescription(
                name=name,
                configured=adapter.configured,
                models=adapter.models,
            )
            for name, adapter in adapters.items()
        ]

    @application.get("/v1/models/{provider}")
    async def models(
        provider: ProviderName,
        principal: AuthPrincipal = Depends(verifier),
    ) -> dict[str, object]:
        del principal
        adapter = adapters[provider]
        return {
            "provider": provider.value,
            "configured": adapter.configured,
            "models": adapter.models,
        }

    @application.post("/v1/chat/stream")
    async def chat_stream(
        body: ChatStreamRequest,
        request: Request,
        principal: AuthPrincipal = Depends(verifier),
    ) -> StreamingResponse:
        if body.prompt_chars > settings.max_prompt_chars:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail={"code": "prompt_too_large"},
            )
        adapter = adapters[body.provider]
        try:
            adapter.validate(body)
            cancel = await registry.register(principal.subject, body.request_id)
        except ProviderStreamError as error:
            raise HTTPException(error.status_code, detail={"code": error.code}) from error
        except DuplicateRequestError as error:
            raise HTTPException(409, detail={"code": "duplicate_request"}) from error
        except RequestLimitError as error:
            raise HTTPException(429, detail={"code": "concurrency_limit"}) from error

        async def event_stream():
            yield _sse(
                ProviderEvent(
                    "start",
                    {"requestId": body.request_id, "provider": body.provider.value},
                )
            )
            queue: asyncio.Queue[ProviderEvent | ProviderStreamError | object] = (
                asyncio.Queue()
            )
            finished = object()

            async def pump_provider() -> None:
                try:
                    async for event in adapter.stream(body, cancel):
                        queue.put_nowait(event)
                except ProviderStreamError as error:
                    queue.put_nowait(error)
                finally:
                    queue.put_nowait(finished)

            upstream_task = asyncio.create_task(
                pump_provider(),
                name=f"provider:{body.provider.value}:{body.request_id}",
            )
            await registry.attach_upstream_task(
                principal.subject,
                body.request_id,
                upstream_task,
            )
            cancel_wait = asyncio.create_task(cancel.wait())
            disconnect_wait = asyncio.create_task(_wait_for_disconnect(request))
            queue_wait: asyncio.Task | None = None
            try:
                while True:
                    queue_wait = asyncio.create_task(queue.get())
                    completed, _ = await asyncio.wait(
                        {queue_wait, cancel_wait, disconnect_wait},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    if disconnect_wait in completed:
                        cancel.set()
                        upstream_task.cancel()
                        return
                    if cancel_wait in completed:
                        upstream_task.cancel()
                        yield _sse(
                            ProviderEvent("cancelled", {}),
                            body.request_id,
                        )
                        return
                    item = queue_wait.result()
                    queue_wait = None
                    if item is finished:
                        yield _sse(ProviderEvent("done", {}), body.request_id)
                        return
                    if isinstance(item, ProviderStreamError):
                        yield _sse(
                            ProviderEvent("error", {"code": item.code}),
                            body.request_id,
                        )
                        return
                    if isinstance(item, ProviderEvent):
                        yield _sse(item, body.request_id)
            finally:
                if queue_wait is not None:
                    queue_wait.cancel()
                cancel_wait.cancel()
                disconnect_wait.cancel()
                if not upstream_task.done():
                    upstream_task.cancel()
                with suppress(asyncio.CancelledError):
                    await upstream_task
                await registry.unregister(principal.subject, body.request_id)

        return StreamingResponse(
            event_stream(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-store",
                "X-Accel-Buffering": "no",
                "X-Content-Type-Options": "nosniff",
            },
        )

    @application.post("/v1/requests/{request_id}/cancel", status_code=202)
    async def cancel_request(
        request_id: str,
        principal: AuthPrincipal = Depends(verifier),
    ) -> dict[str, object]:
        if not request_id or len(request_id) > 80:
            raise HTTPException(400, detail={"code": "invalid_request_id"})
        accepted = await registry.cancel(principal.subject, request_id)
        if not accepted:
            raise HTTPException(404, detail={"code": "request_not_active"})
        return {"requestId": request_id, "cancelAccepted": True}

    @application.post("/v1/session/revoke")
    async def revoke_session(
        principal: AuthPrincipal = Depends(verifier),
    ) -> dict[str, bool]:
        return {"revoked": verifier.revoke(principal)}

    return application


async def _wait_for_disconnect(request: Request) -> None:
    while not await request.is_disconnected():
        await asyncio.sleep(0.05)


def _sse(event: ProviderEvent, request_id: str | None = None) -> bytes:
    payload = dict(event.payload)
    if request_id is not None:
        payload["requestId"] = request_id
    data = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event.type}\ndata: {data}\n\n".encode()


app = create_app()
