from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass
from urllib.parse import urlparse

import httpx
import jwt
from fastapi import Header, HTTPException, status
from jwt.algorithms import RSAAlgorithm

from .config import Settings
from .models import AuthPrincipal


@dataclass
class _JwksCache:
    keys: dict[str, dict]
    expires_at: float


class OidcVerifier:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self._settings = settings
        self._client = client or httpx.AsyncClient(timeout=10.0, follow_redirects=False)
        self._owns_client = client is None
        self._cache: _JwksCache | None = None
        self._cache_lock = asyncio.Lock()
        self._revoked_jti: dict[str, int] = {}

    async def __call__(self, authorization: str | None = Header(default=None)) -> AuthPrincipal:
        if not authorization or not authorization.startswith("Bearer "):
            raise self._unauthorized("missing_bearer_token")
        token = authorization[7:].strip()
        if not token or len(token) > 16_384:
            raise self._unauthorized("invalid_bearer_token")
        try:
            header = jwt.get_unverified_header(token)
            if header.get("alg") != "RS256" or not isinstance(header.get("kid"), str):
                raise ValueError("unsupported token header")
            keys = await self._jwks()
            jwk = keys.get(header["kid"])
            if jwk is None:
                await self._invalidate_cache()
                jwk = (await self._jwks()).get(header["kid"])
            if jwk is None:
                raise ValueError("unknown signing key")
            claims = jwt.decode(
                token,
                key=RSAAlgorithm.from_jwk(json.dumps(jwk)),
                algorithms=["RS256"],
                audience=self._settings.oidc_audience,
                issuer=self._settings.oidc_issuer,
                leeway=30,
                options={"require": ["exp", "iat", "iss", "sub", "aud"]},
            )
            azp = claims.get("azp") or claims.get("client_id")
            if azp not in self._settings.oidc_allowed_azp:
                raise ValueError("unauthorized party")
            jti = claims.get("jti")
            self._purge_revocations()
            if isinstance(jti, str) and jti in self._revoked_jti:
                raise ValueError("revoked token")
            return AuthPrincipal(
                subject=str(claims["sub"]),
                authorized_party=str(azp),
                expires_at=int(claims["exp"]),
                issued_at=int(claims["iat"]),
                jwt_id=jti if isinstance(jti, str) else None,
            )
        except HTTPException:
            raise
        except Exception as error:
            raise self._unauthorized("invalid_access_token") from error

    def revoke(self, principal: AuthPrincipal) -> bool:
        if principal.jwt_id is None:
            return False
        self._revoked_jti[principal.jwt_id] = principal.expires_at
        self._purge_revocations()
        return True

    async def _jwks(self) -> dict[str, dict]:
        now = time.monotonic()
        if self._cache and self._cache.expires_at > now:
            return self._cache.keys
        async with self._cache_lock:
            now = time.monotonic()
            if self._cache and self._cache.expires_at > now:
                return self._cache.keys
            discovery_url = (
                f"{self._settings.oidc_issuer}/.well-known/openid-configuration"
            )
            discovery_response = await self._client.get(discovery_url)
            discovery_response.raise_for_status()
            discovery = discovery_response.json()
            if discovery.get("issuer") != self._settings.oidc_issuer:
                raise ValueError("discovery issuer mismatch")
            jwks_uri = discovery.get("jwks_uri")
            if not isinstance(jwks_uri, str):
                raise ValueError("discovery is missing jwks_uri")
            issuer_host = urlparse(self._settings.oidc_issuer).hostname
            jwks_url = urlparse(jwks_uri)
            if jwks_url.scheme != "https" or jwks_url.hostname != issuer_host:
                raise ValueError("untrusted jwks_uri")
            jwks_response = await self._client.get(jwks_uri)
            jwks_response.raise_for_status()
            raw_keys = jwks_response.json().get("keys", [])
            keys = {
                key["kid"]: key
                for key in raw_keys
                if isinstance(key, dict)
                and isinstance(key.get("kid"), str)
                and key.get("kty") == "RSA"
                and key.get("use", "sig") == "sig"
            }
            if not keys:
                raise ValueError("no trusted signing keys")
            self._cache = _JwksCache(keys=keys, expires_at=now + 300)
            return keys

    async def _invalidate_cache(self) -> None:
        async with self._cache_lock:
            self._cache = None

    def _purge_revocations(self) -> None:
        now = int(time.time())
        self._revoked_jti = {
            key: expiration
            for key, expiration in self._revoked_jti.items()
            if expiration > now
        }

    def _unauthorized(self, code: str) -> HTTPException:
        return HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": code},
            headers={"WWW-Authenticate": "Bearer"},
        )

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()
