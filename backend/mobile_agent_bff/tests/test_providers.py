import asyncio
import json

import httpx
import pytest

from app.models import ChatStreamRequest
from app.providers import (
    AnthropicAdapter,
    OpenAIAdapter,
    ProviderStreamError,
    XaiAdapter,
)


def request(provider: str, model: str) -> ChatStreamRequest:
    return ChatStreamRequest.model_validate(
        {
            "request_id": "request_1234",
            "provider": provider,
            "model": model,
            "messages": [{"role": "user", "content": "hello"}],
        }
    )


def sse(*payloads: tuple[str, dict]) -> bytes:
    return b"".join(
        f"event: {name}\ndata: {json.dumps(payload)}\n\n".encode()
        for name, payload in payloads
    )


class ChunkedStream(httpx.AsyncByteStream):
    def __init__(self, *chunks: bytes):
        self.chunks = chunks

    async def __aiter__(self):
        for chunk in self.chunks:
            yield chunk

    async def aclose(self) -> None:
        return None


def stream_response(
    incoming: httpx.Request,
    content: bytes,
    *,
    status: int = 200,
    content_type: str = "text/event-stream; charset=utf-8",
) -> httpx.Response:
    return httpx.Response(
        status,
        content=content,
        headers={"content-type": content_type},
        request=incoming,
    )


@pytest.mark.asyncio
async def test_openai_response_delta_is_normalized() -> None:
    transport = httpx.MockTransport(
        lambda incoming: stream_response(
            incoming,
            sse(
                ("response.output_text.delta", {"type": "response.output_text.delta", "delta": "hi"}),
                ("response.completed", {"type": "response.completed", "response": {"usage": {"input_tokens": 2, "output_tokens": 1}}}),
            ),
        )
    )
    async with httpx.AsyncClient(transport=transport) as client:
        adapter = OpenAIAdapter("test-key", ("coding-model",), 10, client)
        events = [event async for event in adapter.stream(request("openai", "coding-model"), asyncio.Event())]

    assert [(event.type, event.payload) for event in events] == [
        ("delta", {"text": "hi"}),
        ("usage", {"inputTokens": 2, "outputTokens": 1}),
    ]


@pytest.mark.asyncio
async def test_anthropic_and_xai_delta_are_normalized() -> None:
    responses = {
        "api.anthropic.com": sse(("content_block_delta", {"type": "content_block_delta", "delta": {"type": "text_delta", "text": "claude"}})),
        "api.x.ai": b'data: {"choices":[{"delta":{"content":"grok"}}]}\n\n',
    }

    def handler(incoming: httpx.Request) -> httpx.Response:
        return stream_response(incoming, responses[incoming.url.host])

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        anthropic = AnthropicAdapter("test-key", ("claude-model",), 10, client)
        xai = XaiAdapter("test-key", ("grok-model",), 10, client)
        anthropic_events = [event async for event in anthropic.stream(request("anthropic", "claude-model"), asyncio.Event())]
        xai_events = [event async for event in xai.stream(request("xai", "grok-model"), asyncio.Event())]

    assert anthropic_events[0].payload == {"text": "claude"}
    assert xai_events[0].payload == {"text": "grok"}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("status", "code", "normalized"),
    [
        (401, "provider_authentication_failed", 401),
        (403, "provider_access_denied", 403),
        (404, "provider_model_not_found", 404),
        (429, "provider_rate_limited", 429),
        (500, "provider_unavailable", 503),
        (502, "provider_unavailable", 503),
        (503, "provider_unavailable", 503),
        (504, "provider_unavailable", 503),
    ],
)
async def test_status_fault_matrix_is_redacted_and_never_retried(
    status: int,
    code: str,
    normalized: int,
) -> None:
    calls = 0

    def handler(incoming: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return stream_response(
            incoming,
            b'{"error":{"message":"provider-secret-body"}}',
            status=status,
            content_type="application/json",
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        adapter = OpenAIAdapter("test-key", ("coding-model",), 10, client)
        with pytest.raises(ProviderStreamError) as raised:
            _ = [
                event
                async for event in adapter.stream(
                    request("openai", "coding-model"),
                    asyncio.Event(),
                )
            ]

    error = raised.value
    assert getattr(error, "code", None) == code
    assert getattr(error, "status_code", None) == normalized
    assert "provider-secret-body" not in str(error)
    assert calls == 1


@pytest.mark.asyncio
async def test_split_utf8_and_multiline_sse_are_parsed_without_chunk_assumptions() -> None:
    raw = (
        'event: response.output_text.delta\n'
        'data: {"type":"response.output_text.delta",\n'
        'data: "delta":"한글"}\n\n'
    ).encode("utf-8")
    split = raw.index("한".encode("utf-8")) + 1

    def handler(incoming: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            stream=ChunkedStream(raw[:split], raw[split:]),
            headers={"content-type": "text/event-stream"},
            request=incoming,
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        adapter = OpenAIAdapter("test-key", ("coding-model",), 10, client)
        events = [
            event
            async for event in adapter.stream(
                request("openai", "coding-model"),
                asyncio.Event(),
            )
        ]

    assert events[0].payload == {"text": "한글"}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("content", "content_type", "event_limit", "stream_limit", "code"),
    [
        (b'data: {"x":1}\n\n', "application/json", 128, 256, "provider_stream_invalid"),
        (b"data: not-json\n\n", "text/event-stream", 128, 256, "provider_stream_invalid"),
        (b"data: [1]\n\n", "text/event-stream", 128, 256, "provider_stream_invalid"),
        (b"data: \xff\n\n", "text/event-stream", 128, 256, "provider_stream_invalid"),
        (b":" + b"x" * 40 + b"\ndata: {}\n\n", "text/event-stream", 24, 256, "provider_stream_too_large"),
        (b"data: {}\n\n" * 8, "text/event-stream", 32, 32, "provider_stream_too_large"),
    ],
)
async def test_malformed_and_oversized_streams_fail_closed(
    content: bytes,
    content_type: str,
    event_limit: int,
    stream_limit: int,
    code: str,
) -> None:
    def handler(incoming: httpx.Request) -> httpx.Response:
        return stream_response(incoming, content, content_type=content_type)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        adapter = OpenAIAdapter(
            "test-key",
            ("coding-model",),
            10,
            client,
            max_event_bytes=event_limit,
            max_stream_bytes=stream_limit,
        )
        with pytest.raises(ProviderStreamError) as raised:
            _ = [
                event
                async for event in adapter.stream(
                    request("openai", "coding-model"),
                    asyncio.Event(),
                )
            ]

    assert getattr(raised.value, "code", None) == code
    assert "not-json" not in str(raised.value)


@pytest.mark.asyncio
async def test_timeout_and_disconnect_are_redacted_and_not_retried() -> None:
    calls = 0

    def handler(incoming: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ReadTimeout("provider-secret-timeout", request=incoming)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        adapter = OpenAIAdapter("test-key", ("coding-model",), 10, client)
        with pytest.raises(ProviderStreamError) as raised:
            _ = [
                event
                async for event in adapter.stream(
                    request("openai", "coding-model"),
                    asyncio.Event(),
                )
            ]

    assert getattr(raised.value, "code", None) == "provider_unavailable"
    assert "provider-secret-timeout" not in str(raised.value)
    assert calls == 1
