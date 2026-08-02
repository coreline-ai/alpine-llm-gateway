"""llmctl and llm-gatewayd command-line interface."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from .config import Settings
from .gateway import serve


MAX_CLI_RESPONSE_BYTES = 8 * 1024 * 1024
MAX_CLI_STREAM_EVENT_BYTES = 1 * 1024 * 1024
MAX_CLI_STREAM_BYTES = 32 * 1024 * 1024


def main(argv: list[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    if args.command == "serve":
        settings = Settings.from_file(args.config)
        if args.host:
            settings = settings.__class__(**{**settings.__dict__, "host": args.host})
        if args.port:
            settings = settings.__class__(**{**settings.__dict__, "port": args.port})
        serve(settings)
        return 0
    if args.command == "health":
        try:
            value = _get_json(
                args.base_url.rstrip("/") + "/healthz",
                _resolve_session_token(args.session_token, args.credential_file),
            )
            print(json.dumps(value, ensure_ascii=False, indent=2))
            return 0
        except (HTTPError, URLError, OSError, ValueError):
            print("llmctl: health request failed", file=sys.stderr)
            return 1
    if args.command == "models":
        try:
            value = _get_json(
                args.base_url.rstrip("/") + "/v1/models",
                _resolve_session_token(args.session_token, args.credential_file),
            )
            print(json.dumps(value, ensure_ascii=False, indent=2))
            return 0
        except (HTTPError, URLError, OSError, ValueError):
            print("llmctl: models request failed", file=sys.stderr)
            return 1
    if args.command == "run":
        return _run(args)
    parser.print_help()
    return 2


def _run(args: argparse.Namespace) -> int:
    if args.prompt is not None and args.input is not None:
        print("--prompt and --input are mutually exclusive", file=sys.stderr)
        return 2
    if args.prompt is not None:
        payload: dict[str, object] = {"messages": [{"role": "user", "content": args.prompt}]}
    elif args.input:
        payload = _load_input(args.input)
    elif not sys.stdin.isatty():
        payload = _parse_text_or_json(sys.stdin.read())
    else:
        print("provide --prompt, --input, or pipe input on stdin", file=sys.stderr)
        return 2

    payload["model"] = args.model
    payload["stream"] = bool(args.stream)
    if args.system:
        payload["system"] = args.system
    if args.max_tokens:
        payload["max_tokens"] = args.max_tokens
    if args.temperature is not None:
        payload["temperature"] = args.temperature

    url = args.base_url.rstrip("/") + "/v1/chat/completions"
    try:
        session_token = _resolve_session_token(args.session_token, args.credential_file)
        if args.stream:
            return _stream_request(url, payload, args.format, args.output, session_token)
        value = _post_json(url, payload, session_token)
        text = value.get("choices", [{}])[0].get("message", {}).get("content", "")
        if args.output:
            Path(args.output).write_text(str(text), encoding="utf-8")
        elif args.format == "json":
            print(json.dumps(value, ensure_ascii=False, indent=2))
        else:
            print(text)
        return 0
    except (HTTPError, URLError, OSError, ValueError):
        print("llmctl: request failed", file=sys.stderr)
        return 1


def _stream_request(
    url: str,
    payload: dict[str, object],
    output_format: str,
    output_path: str | None,
    session_token: str | None,
) -> int:
    request = Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=_headers(session_token, accept="text/event-stream"),
        method="POST",
    )
    collected: list[str] = []
    stream_error: str | None = None
    total_bytes = 0
    with urlopen(request, timeout=180) as response:
        for raw_line in response:
            total_bytes += len(raw_line)
            if len(raw_line) > MAX_CLI_STREAM_EVENT_BYTES:
                raise ValueError("stream event exceeds limit")
            if total_bytes > MAX_CLI_STREAM_BYTES:
                raise ValueError("stream exceeds limit")
            line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            event = json.loads(data)
            if event.get("type") == "delta":
                text = str(event.get("text", ""))
                collected.append(text)
                if output_format == "jsonl":
                    print(json.dumps(event, ensure_ascii=False), flush=True)
                else:
                    print(text, end="", flush=True)
            elif output_format == "jsonl":
                print(json.dumps(event, ensure_ascii=False), flush=True)
            if event.get("type") == "error":
                stream_error = "gateway stream failed"
    if output_format != "jsonl":
        print()
    if output_path:
        Path(output_path).write_text("".join(collected), encoding="utf-8")
    if stream_error:
        print(f"llmctl: {stream_error}", file=sys.stderr)
        return 1
    return 0


def _load_input(path: str) -> dict[str, object]:
    return _parse_text_or_json(Path(path).read_text(encoding="utf-8"))


def _parse_text_or_json(text: str) -> dict[str, object]:
    stripped = text.strip()
    if not stripped:
        raise ValueError("input is empty")
    if stripped.startswith("{") or stripped.startswith("["):
        value = json.loads(stripped)
        if isinstance(value, list):
            return {"messages": value}
        if isinstance(value, dict) and "messages" in value:
            return value
    return {"messages": [{"role": "user", "content": text}]}


def _get_json(url: str, session_token: str | None = None) -> dict:
    request = Request(url, headers=_headers(session_token), method="GET")
    with urlopen(request, timeout=10) as response:
        return json.loads(_read_response_limited(response).decode("utf-8"))


def _post_json(url: str, value: dict, session_token: str | None = None) -> dict:
    request = Request(
        url,
        data=json.dumps(value, ensure_ascii=False).encode("utf-8"),
        headers=_headers(session_token),
        method="POST",
    )
    with urlopen(request, timeout=180) as response:
        return json.loads(_read_response_limited(response).decode("utf-8"))


def _read_response_limited(response) -> bytes:
    raw = response.read(MAX_CLI_RESPONSE_BYTES + 1)
    if len(raw) > MAX_CLI_RESPONSE_BYTES:
        raise ValueError("response exceeds limit")
    return raw


def _headers(session_token: str | None, *, accept: str | None = None) -> dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if accept:
        headers["Accept"] = accept
    if session_token:
        headers["Authorization"] = f"Bearer {session_token}"
    return headers


def _resolve_session_token(
    session_token: str | None,
    credential_file: str | None,
) -> str | None:
    if session_token:
        return session_token
    if not credential_file:
        return None
    path = Path(credential_file)
    if not path.is_file():
        raise ValueError("credential file is unavailable")
    raw = path.read_bytes()
    if len(raw) > 8 * 1024:
        raise ValueError("credential file is too large")
    try:
        value = raw.decode("utf-8").strip()
    except UnicodeDecodeError as error:
        raise ValueError("credential file is invalid") from error
    if not value or "\x00" in value or "\n" in value or "\r" in value:
        raise ValueError("credential file is invalid")
    return value


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="llmctl")
    sub = parser.add_subparsers(dest="command")

    serve_parser = sub.add_parser("serve", help="start the local gateway")
    serve_parser.add_argument("--config", default=os.environ.get("ALPINE_LLM_CONFIG", "config.json"))
    serve_parser.add_argument("--host")
    serve_parser.add_argument("--port", type=int)

    for name, help_text in (("health", "check gateway health"), ("models", "list allowed models")):
        command = sub.add_parser(name, help=help_text)
        command.add_argument("--base-url", default=os.environ.get("ALPINE_LLM_URL", "http://127.0.0.1:8787"))
        command.add_argument("--session-token", default=os.environ.get("ALPINE_LLM_SESSION_TOKEN"))
        command.add_argument(
            "--credential-file",
            default=os.environ.get("ALPINE_LLM_CREDENTIAL_FILE"),
        )

    run = sub.add_parser("run", help="send a completion request")
    run.add_argument("--base-url", default=os.environ.get("ALPINE_LLM_URL", "http://127.0.0.1:8787"))
    run.add_argument("--session-token", default=os.environ.get("ALPINE_LLM_SESSION_TOKEN"))
    run.add_argument(
        "--credential-file",
        default=os.environ.get("ALPINE_LLM_CREDENTIAL_FILE"),
    )
    run.add_argument("--model", default="auto")
    run.add_argument("--prompt")
    run.add_argument("--input")
    run.add_argument("--system")
    run.add_argument("--max-tokens", type=int, default=1024)
    run.add_argument("--temperature", type=float)
    run.add_argument("--stream", action="store_true")
    run.add_argument("--format", choices=("text", "json", "jsonl"), default="text")
    run.add_argument("--output")
    return parser


if __name__ == "__main__":
    raise SystemExit(main())
