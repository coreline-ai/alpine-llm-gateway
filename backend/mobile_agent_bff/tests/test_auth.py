import json
import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from jwt.algorithms import RSAAlgorithm

from app.auth import OidcVerifier
from app.config import Settings


ISSUER = "https://auth.mobileagent.example/realms/mobileagent"


def settings() -> Settings:
    return Settings(
        oidc_issuer=ISSUER,
        oidc_audience="mobile-agent-bff",
        oidc_allowed_azp=("mobile-agent-native",),
    )


def signing_material(kid: str):
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_jwk = json.loads(RSAAlgorithm.to_jwk(private_key.public_key()))
    public_jwk.update({"kid": kid, "use": "sig", "alg": "RS256"})
    return private_key, public_jwk


def token(private_key, kid: str, **updates) -> str:
    now = int(time.time())
    claims = {
        "iss": ISSUER,
        "aud": "mobile-agent-bff",
        "azp": "mobile-agent-native",
        "sub": "user-123",
        "iat": now,
        "exp": now + 300,
        "jti": "token-123",
    }
    claims.update(updates)
    return jwt.encode(claims, private_key, algorithm="RS256", headers={"kid": kid})


def mock_client(keys: list[dict]) -> httpx.AsyncClient:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/.well-known/openid-configuration"):
            return httpx.Response(
                200,
                json={"issuer": ISSUER, "jwks_uri": f"{ISSUER}/protocol/openid-connect/certs"},
                request=request,
            )
        return httpx.Response(200, json={"keys": keys}, request=request)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


@pytest.mark.asyncio
async def test_valid_keycloak_style_access_token_is_verified() -> None:
    private_key, jwk = signing_material("key-1")
    async with mock_client([jwk]) as client:
        verifier = OidcVerifier(settings(), client)
        principal = await verifier(f"Bearer {token(private_key, 'key-1')}")

    assert principal.subject == "user-123"
    assert principal.authorized_party == "mobile-agent-native"
    assert principal.jwt_id == "token-123"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "claim,value",
    [
        ("aud", "another-api"),
        ("azp", "foreign-mobile-app"),
        ("iss", "https://attacker.invalid"),
    ],
)
async def test_wrong_issuer_audience_or_azp_is_rejected(claim: str, value: str) -> None:
    private_key, jwk = signing_material("key-1")
    async with mock_client([jwk]) as client:
        verifier = OidcVerifier(settings(), client)
        with pytest.raises(HTTPException) as raised:
            await verifier(f"Bearer {token(private_key, 'key-1', **{claim: value})}")

    assert raised.value.status_code == 401
    assert raised.value.detail == {"code": "invalid_access_token"}


@pytest.mark.asyncio
async def test_revoked_jti_is_rejected() -> None:
    private_key, jwk = signing_material("key-1")
    encoded = token(private_key, "key-1")
    async with mock_client([jwk]) as client:
        verifier = OidcVerifier(settings(), client)
        principal = await verifier(f"Bearer {encoded}")
        assert verifier.revoke(principal) is True
        with pytest.raises(HTTPException):
            await verifier(f"Bearer {encoded}")


@pytest.mark.asyncio
async def test_unknown_kid_refetches_jwks_for_key_rotation() -> None:
    first_private, first_jwk = signing_material("key-1")
    second_private, second_jwk = signing_material("key-2")
    jwks_calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal jwks_calls
        if request.url.path.endswith("/.well-known/openid-configuration"):
            return httpx.Response(
                200,
                json={
                    "issuer": ISSUER,
                    "jwks_uri": f"{ISSUER}/protocol/openid-connect/certs",
                },
                request=request,
            )
        jwks_calls += 1
        return httpx.Response(
            200,
            json={"keys": [first_jwk] if jwks_calls == 1 else [second_jwk]},
            request=request,
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        verifier = OidcVerifier(settings(), client)
        first = await verifier(f"Bearer {token(first_private, 'key-1')}")
        second = await verifier(
            f"Bearer {token(second_private, 'key-2', jti='token-rotated')}"
        )

    assert first.subject == second.subject
    assert second.jwt_id == "token-rotated"
    assert jwks_calls == 2


@pytest.mark.asyncio
async def test_expired_access_token_is_rejected() -> None:
    private_key, jwk = signing_material("key-1")
    now = int(time.time())
    async with mock_client([jwk]) as client:
        verifier = OidcVerifier(settings(), client)
        with pytest.raises(HTTPException) as raised:
            await verifier(
                f"Bearer {token(private_key, 'key-1', iat=now - 120, exp=now - 60)}"
            )

    assert raised.value.status_code == 401


@pytest.mark.asyncio
async def test_access_token_inside_clock_skew_boundary_is_accepted() -> None:
    private_key, jwk = signing_material("key-1")
    now = int(time.time())
    async with mock_client([jwk]) as client:
        verifier = OidcVerifier(settings(), client)
        principal = await verifier(
            f"Bearer {token(private_key, 'key-1', iat=now - 120, exp=now - 20)}"
        )

    assert principal.subject == "user-123"


@pytest.mark.asyncio
async def test_access_token_outside_clock_skew_boundary_is_rejected() -> None:
    private_key, jwk = signing_material("key-1")
    now = int(time.time())
    async with mock_client([jwk]) as client:
        verifier = OidcVerifier(settings(), client)
        with pytest.raises(HTTPException) as raised:
            await verifier(
                f"Bearer {token(private_key, 'key-1', iat=now - 120, exp=now - 31)}"
            )

    assert raised.value.status_code == 401
    assert raised.value.detail == {"code": "invalid_access_token"}
