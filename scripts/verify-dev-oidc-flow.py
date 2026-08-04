#!/usr/bin/env python3
"""Exercise the local Keycloak Authorization Code + PKCE fixture without printing tokens."""

from __future__ import annotations

import base64
import hashlib
import json
import secrets
import sys
from html.parser import HTMLParser
from http.cookiejar import CookieJar
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import (
    HTTPCookieProcessor,
    HTTPRedirectHandler,
    Request,
    build_opener,
    urlopen,
)


ISSUER = "http://127.0.0.1:8080/realms/mobileagent"
CLIENT_ID = "mobile-agent-native"
REDIRECT_URI = "ai.coreline.mobileagent:/oauth/callback"
USERNAME = "mobileagent-tester"
PASSWORD = "mobileagent-local-only"


class _LoginFormParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.action: str | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag != "form":
            return
        values = dict(attrs)
        action = values.get("action") or ""
        if values.get("id") == "kc-form-login" or "login-actions" in action:
            self.action = action


class _CustomSchemeRedirect(HTTPRedirectHandler):
    def __init__(self) -> None:
        self.location: str | None = None

    def redirect_request(self, request, response, code, message, headers, new_url):
        if urlparse(new_url).scheme not in {"http", "https"}:
            self.location = new_url
            return None
        return super().redirect_request(
            request,
            response,
            code,
            message,
            headers,
            new_url,
        )


def _urlsafe_sha256(value: str) -> str:
    digest = hashlib.sha256(value.encode()).digest()
    return base64.urlsafe_b64encode(digest).decode().rstrip("=")


def _decode_claims(token: str) -> dict[str, object]:
    parts = token.split(".")
    if len(parts) != 3:
        raise ValueError("access token is not a JWT")
    payload = parts[1] + "=" * (-len(parts[1]) % 4)
    value = json.loads(base64.urlsafe_b64decode(payload))
    if not isinstance(value, dict):
        raise ValueError("JWT payload is invalid")
    return value


def _token_request(endpoint: str, values: dict[str, str]) -> dict[str, object]:
    request = Request(
        endpoint,
        data=urlencode(values).encode(),
        method="POST",
    )
    with urlopen(request, timeout=10) as response:
        value = json.load(response)
    if not isinstance(value, dict):
        raise ValueError("token response is invalid")
    return value


def main() -> int:
    issuer = urlparse(ISSUER)
    if issuer.scheme != "http" or issuer.hostname not in {"127.0.0.1", "localhost"}:
        raise ValueError("this verifier may only use the local HTTP fixture")

    with urlopen(f"{ISSUER}/.well-known/openid-configuration", timeout=10) as response:
        discovery = json.load(response)
    if discovery.get("issuer") != ISSUER:
        raise ValueError("discovery issuer mismatch")

    verifier = secrets.token_urlsafe(64)
    state = secrets.token_urlsafe(24)
    nonce = secrets.token_urlsafe(24)
    redirect_handler = _CustomSchemeRedirect()
    cookie_jar = CookieJar()
    opener = build_opener(HTTPCookieProcessor(cookie_jar), redirect_handler)
    authorization_query = urlencode(
        {
            "client_id": CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "response_type": "code",
            "scope": "openid profile offline_access",
            "code_challenge": _urlsafe_sha256(verifier),
            "code_challenge_method": "S256",
            "state": state,
            "nonce": nonce,
        }
    )
    with opener.open(
        f"{discovery['authorization_endpoint']}?{authorization_query}",
        timeout=10,
    ) as response:
        login_html = response.read().decode()
    parser = _LoginFormParser()
    parser.feed(login_html)
    if not parser.action:
        raise ValueError("Keycloak login form was not returned")

    # Keycloak marks these localhost fixture cookies Secure. A real browser treats
    # loopback as trustworthy; urllib deliberately does not, so the test supplies
    # the cookies explicitly for this single local POST.
    cookies = "; ".join(f"{cookie.name}={cookie.value}" for cookie in cookie_jar)
    login_request = Request(
        parser.action,
        data=urlencode(
            {
                "username": USERNAME,
                "password": PASSWORD,
                "credentialId": "",
            }
        ).encode(),
        headers={"Cookie": cookies},
        method="POST",
    )
    try:
        opener.open(login_request, timeout=10).read()
    except HTTPError as error:
        if error.code not in {302, 303}:
            raise
        redirect_handler.location = error.headers.get("Location")

    callback = urlparse(redirect_handler.location or "")
    callback_parameters = parse_qs(callback.query)
    if (
        f"{callback.scheme}:{callback.path}" != REDIRECT_URI
        or callback_parameters.get("state") != [state]
        or not callback_parameters.get("code")
    ):
        raise ValueError("OAuth callback, state, or authorization code is invalid")

    token_response = _token_request(
        discovery["token_endpoint"],
        {
            "grant_type": "authorization_code",
            "client_id": CLIENT_ID,
            "redirect_uri": REDIRECT_URI,
            "code": callback_parameters["code"][0],
            "code_verifier": verifier,
        },
    )
    access_token = token_response.get("access_token")
    refresh_token = token_response.get("refresh_token")
    id_token = token_response.get("id_token")
    if not all(isinstance(value, str) and value for value in (access_token, refresh_token, id_token)):
        raise ValueError("code exchange did not return the required token set")

    claims = _decode_claims(access_token)
    audience = claims.get("aud")
    audiences = [audience] if isinstance(audience, str) else audience
    if (
        claims.get("iss") != ISSUER
        or "mobile-agent-bff" not in (audiences or [])
        or claims.get("azp") != CLIENT_ID
        or not claims.get("sub")
        or not claims.get("jti")
        or not isinstance(claims.get("exp"), int)
        or not isinstance(claims.get("iat"), int)
    ):
        raise ValueError("access token claims do not satisfy the BFF contract")

    rotated = _token_request(
        discovery["token_endpoint"],
        {
            "grant_type": "refresh_token",
            "client_id": CLIENT_ID,
            "refresh_token": refresh_token,
        },
    )
    rotated_refresh = rotated.get("refresh_token")
    if not isinstance(rotated.get("access_token"), str) or not isinstance(
        rotated_refresh, str
    ):
        raise ValueError("refresh exchange failed")
    if rotated_refresh == refresh_token:
        raise ValueError("refresh token did not rotate")

    try:
        _token_request(
            discovery["token_endpoint"],
            {
                "grant_type": "refresh_token",
                "client_id": CLIENT_ID,
                "refresh_token": refresh_token,
            },
        )
    except HTTPError as error:
        if error.code != 400:
            raise
    else:
        raise ValueError("the previous refresh token was accepted after rotation")

    revoke_request = Request(
        discovery["revocation_endpoint"],
        data=urlencode(
            {
                "token": rotated_refresh,
                "token_type_hint": "refresh_token",
                "client_id": CLIENT_ID,
            }
        ).encode(),
        method="POST",
    )
    with urlopen(revoke_request, timeout=10) as response:
        if response.status != 200:
            raise ValueError("refresh token revocation failed")

    print(
        "Development OIDC flow PASS: login, PKCE code exchange, BFF audience, "
        "refresh rotation, replay rejection, revoke"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Development OIDC flow FAILED: {type(error).__name__}", file=sys.stderr)
        raise SystemExit(1) from error
