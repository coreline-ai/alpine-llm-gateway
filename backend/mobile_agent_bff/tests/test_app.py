import asyncio

import httpx
import pytest

from app.config import Settings
from app.main import create_app
from app.models import AuthPrincipal, ProviderName
from app.providers import ProviderEvent, ProviderStreamError


def settings(**updates) -> Settings:
    values = {
        "oidc_issuer": "https://auth.mobileagent.example/realms/mobileagent",
        "oidc_audience": "mobile-agent-bff",
        "oidc_allowed_azp": ("mobile-agent-native",),
    }
    values.update(updates)
    return Settings(**values)


@pytest.mark.asyncio
async def test_app_has_public_health_and_protected_session() -> None:
    app = create_app(settings())
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="https://bff.test",
        ) as client:
            health = await client.get("/healthz")
            unauthorized = await client.get("/v1/session")

    assert health.json() == {"status": "ok"}
    assert unauthorized.status_code == 401
    assert unauthorized.json() == {"detail": {"code": "missing_bearer_token"}}


@pytest.mark.asyncio
async def test_provider_catalog_is_redacted_and_authenticated() -> None:
    app = create_app(
        settings(openai_api_key="unit-test-only", openai_models=("coding-model",))
    )
    verifier = app.state.verifier

    async def fake_principal() -> AuthPrincipal:
        return AuthPrincipal(
            subject="user-1",
            authorized_party="mobile-agent-native",
            expires_at=2_000_000_000,
            issued_at=1_900_000_000,
            jwt_id="jwt-1",
        )

    app.dependency_overrides[verifier] = fake_principal
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="https://bff.test",
        ) as client:
            response = await client.get("/v1/providers")

    assert response.status_code == 200
    assert response.json()[0] == {
        "name": "openai",
        "configured": True,
        "models": ["coding-model"],
    }
    assert "unit-test-only" not in response.text


class _BlockingProvider:
    configured = True
    models = ("coding-model",)

    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.cancelled = asyncio.Event()

    def validate(self, request) -> None:
        assert request.model == "coding-model"

    async def stream(self, request, cancel):
        del request, cancel
        self.started.set()
        try:
            await asyncio.sleep(60)
        except asyncio.CancelledError:
            self.cancelled.set()
            raise
        yield ProviderEvent("delta", {"text": "unreachable"})

    async def close(self) -> None:
        return None


class _FaultProvider:
    configured = True
    models = ("coding-model",)

    def validate(self, request) -> None:
        assert request.model == "coding-model"

    async def stream(self, request, cancel):
        del request, cancel
        raise ProviderStreamError("provider_unavailable", 503) from RuntimeError(
            "provider-secret-cause"
        )
        yield ProviderEvent("delta", {"text": "unreachable"})

    async def close(self) -> None:
        return None


@pytest.mark.asyncio
async def test_cancel_endpoint_aborts_upstream_task_and_finishes_stream() -> None:
    app = create_app(
        settings(openai_api_key="unit-test-only", openai_models=("coding-model",))
    )
    verifier = app.state.verifier
    provider = _BlockingProvider()
    app.state.adapters[ProviderName.OPENAI] = provider

    async def fake_principal() -> AuthPrincipal:
        return AuthPrincipal(
            subject="user-1",
            authorized_party="mobile-agent-native",
            expires_at=2_000_000_000,
            issued_at=1_900_000_000,
            jwt_id="jwt-1",
        )

    app.dependency_overrides[verifier] = fake_principal
    payload = {
        "request_id": "request_1234",
        "provider": "openai",
        "model": "coding-model",
        "messages": [{"role": "user", "content": "hello"}],
    }
    transport = httpx.ASGITransport(app=app)
    async with app.router.lifespan_context(app):
        async with (
            httpx.AsyncClient(transport=transport, base_url="https://bff.test") as stream_client,
            httpx.AsyncClient(transport=transport, base_url="https://bff.test") as cancel_client,
        ):
            stream_task = asyncio.create_task(
                stream_client.post("/v1/chat/stream", json=payload)
            )
            await asyncio.wait_for(provider.started.wait(), timeout=0.5)
            cancel_response = await cancel_client.post(
                "/v1/requests/request_1234/cancel"
            )
            stream_response = await asyncio.wait_for(stream_task, timeout=0.5)

    assert cancel_response.status_code == 202
    assert provider.cancelled.is_set()
    assert "event: cancelled" in stream_response.text
    assert await app.state.registry.active_count() == 0


@pytest.mark.asyncio
async def test_cancel_endpoint_reports_an_already_inactive_request() -> None:
    app = create_app(settings())
    verifier = app.state.verifier

    async def fake_principal() -> AuthPrincipal:
        return AuthPrincipal(
            subject="user-1",
            authorized_party="mobile-agent-native",
            expires_at=2_000_000_000,
            issued_at=1_900_000_000,
            jwt_id="jwt-1",
        )

    app.dependency_overrides[verifier] = fake_principal
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="https://bff.test",
        ) as client:
            response = await client.post("/v1/requests/request_1234/cancel")

    assert response.status_code == 404
    assert response.json() == {"detail": {"code": "request_not_active"}}


@pytest.mark.asyncio
async def test_rejected_request_does_not_echo_prompt_or_provider_secret(caplog) -> None:
    provider_secret = "unit-test-provider-secret-must-not-leak"
    prompt_marker = "private-prompt-marker-must-not-leak"
    app = create_app(
        settings(openai_api_key=provider_secret, openai_models=("coding-model",))
    )
    verifier = app.state.verifier

    async def fake_principal() -> AuthPrincipal:
        return AuthPrincipal(
            subject="user-1",
            authorized_party="mobile-agent-native",
            expires_at=2_000_000_000,
            issued_at=1_900_000_000,
            jwt_id="jwt-1",
        )

    app.dependency_overrides[verifier] = fake_principal
    payload = {
        "request_id": "request_1234",
        "provider": "openai",
        "model": "not-allowed",
        "messages": [{"role": "user", "content": prompt_marker}],
    }
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="https://bff.test",
        ) as client:
            response = await client.post("/v1/chat/stream", json=payload)

    assert response.status_code == 400
    combined_output = response.text + caplog.text
    assert prompt_marker not in combined_output
    assert provider_secret not in combined_output
    assert response.json() == {"detail": {"code": "model_not_allowed"}}


@pytest.mark.asyncio
async def test_stream_fault_after_http_200_is_redacted_and_registry_is_released(caplog) -> None:
    prompt_marker = "private-stream-prompt-must-not-leak"
    app = create_app(
        settings(openai_api_key="unit-test-only", openai_models=("coding-model",))
    )
    verifier = app.state.verifier
    app.state.adapters[ProviderName.OPENAI] = _FaultProvider()

    async def fake_principal() -> AuthPrincipal:
        return AuthPrincipal(
            subject="user-1",
            authorized_party="mobile-agent-native",
            expires_at=2_000_000_000,
            issued_at=1_900_000_000,
            jwt_id="jwt-1",
        )

    app.dependency_overrides[verifier] = fake_principal
    payload = {
        "request_id": "request_1234",
        "provider": "openai",
        "model": "coding-model",
        "messages": [{"role": "user", "content": prompt_marker}],
    }
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="https://bff.test",
        ) as client:
            response = await client.post("/v1/chat/stream", json=payload)

    assert response.status_code == 200
    assert "event: start" in response.text
    assert 'event: error\ndata: {"code":"provider_unavailable"' in response.text
    combined_output = response.text + caplog.text
    assert "provider-secret-cause" not in combined_output
    assert prompt_marker not in combined_output
    assert await app.state.registry.active_count() == 0
