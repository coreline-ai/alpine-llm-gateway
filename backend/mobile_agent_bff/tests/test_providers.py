import asyncio
import json

import httpx
import pytest

from app.models import ChatStreamRequest
from app.providers import AnthropicAdapter, OpenAIAdapter, XaiAdapter


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


@pytest.mark.asyncio
async def test_openai_response_delta_is_normalized() -> None:
    transport = httpx.MockTransport(
        lambda incoming: httpx.Response(
            200,
            content=sse(
                ("response.output_text.delta", {"type": "response.output_text.delta", "delta": "hi"}),
                ("response.completed", {"type": "response.completed", "response": {"usage": {"input_tokens": 2, "output_tokens": 1}}}),
            ),
            headers={"content-type": "text/event-stream"},
            request=incoming,
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
        return httpx.Response(200, content=responses[incoming.url.host], request=incoming)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        anthropic = AnthropicAdapter("test-key", ("claude-model",), 10, client)
        xai = XaiAdapter("test-key", ("grok-model",), 10, client)
        anthropic_events = [event async for event in anthropic.stream(request("anthropic", "claude-model"), asyncio.Event())]
        xai_events = [event async for event in xai.stream(request("xai", "grok-model"), asyncio.Event())]

    assert anthropic_events[0].payload == {"text": "claude"}
    assert xai_events[0].payload == {"text": "grok"}
