import unittest
import json
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.request import Request, urlopen
from urllib.error import HTTPError

from alpine_llm.config import Settings
from alpine_llm.gateway import GatewayService, make_handler
from alpine_llm.protocol import CompletionRequest, CompletionResult
from alpine_llm.protocol import Usage
from alpine_llm.providers.base import ProviderError


class FakeProvider:
    def complete(self, request):
        return CompletionResult(request.model, "fake response", usage=Usage(1, 2))

    def stream(self, request):
        from alpine_llm.protocol import CompletionDelta
        yield CompletionDelta(text="fake ")
        yield CompletionDelta(text="stream")


class FailingProvider:
    def complete(self, request):
        raise ProviderError(
            "provider-secret-error-body",
            status_code=503,
            retryable=True,
        )

    def stream(self, request):
        raise ProviderError(
            "provider-secret-stream-error",
            status_code=503,
            retryable=True,
        )


class GatewayTests(unittest.TestCase):
    def test_server_header_uses_package_version(self):
        service = GatewayService(Settings(default_model="fake", allowed_models=("fake",)))
        self.assertEqual("AlpineLLMGateway/0.3.0", make_handler(service).server_version)

    def test_service_applies_policy_before_provider(self):
        service = GatewayService(Settings(default_model="fake", allowed_models=("fake",)))
        service.provider = FakeProvider()
        request = CompletionRequest.from_dict({
            "model": "auto",
            "messages": [{"role": "user", "content": "hello"}],
        })
        result = service.complete(request)
        self.assertEqual(result.text, "fake response")
        self.assertEqual(result.model, "fake")

    def test_models_are_allowlisted(self):
        settings = Settings(
            default_model="fake",
            allowed_models=("fake",),
            model_catalog=({"id": "fake", "modalities": ["text_output"]},),
        )
        service = GatewayService(settings)
        self.assertEqual(service.models()[0]["id"], "fake")

    def test_service_honors_explicit_model_passthrough(self):
        service = GatewayService(Settings(
            default_model="fake",
            allowed_models=("fake",),
            allow_passthrough=True,
        ))
        service.provider = FakeProvider()
        request = CompletionRequest.from_dict({
            "model": "dynamic-model",
            "messages": [{"role": "user", "content": "hello"}],
        })
        result = service.complete(request)
        self.assertEqual("dynamic-model", result.model)

    def test_http_completion_and_streaming(self):
        service = GatewayService(Settings(default_model="fake", allowed_models=("fake",)))
        service.provider = FakeProvider()
        try:
            server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(service))
        except PermissionError:
            self.skipTest("sandbox does not allow binding a local test socket")
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/v1/chat/completions"
            body = {"model": "auto", "messages": [{"role": "user", "content": "hello"}]}
            request = Request(
                url,
                data=json.dumps(body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urlopen(request) as response:
                value = json.loads(response.read().decode("utf-8"))
            self.assertEqual(value["choices"][0]["message"]["content"], "fake response")

            body["stream"] = True
            request = Request(
                url,
                data=json.dumps(body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urlopen(request) as response:
                stream = response.read().decode("utf-8")
            self.assertIn('"text": "fake "', stream)
            self.assertIn('"type": "done"', stream)
            self.assertLess(stream.index('"type": "start"'), stream.index('"type": "delta"'))
            self.assertLess(stream.index('"type": "delta"'), stream.index('"type": "done"'))
            self.assertLess(stream.index('"type": "done"'), stream.index("data: [DONE]"))
        finally:
            server.shutdown()
            server.server_close()

    def test_stream_policy_error_is_rejected_before_sse_headers(self):
        service = GatewayService(Settings(default_model="fake", allowed_models=("fake",)))
        service.provider = FakeProvider()
        with self._server(service) as base_url:
            request = Request(
                base_url + "/v1/chat/completions",
                data=json.dumps({
                    "model": "not-allowed",
                    "messages": [{"role": "user", "content": "hello"}],
                    "stream": True,
                }).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with self.assertRaises(HTTPError) as raised:
                urlopen(request)
            error = raised.exception
            status = error.code
            content_type = error.headers["Content-Type"]
            error.close()
        self.assertEqual(400, status)
        self.assertEqual("application/json; charset=utf-8", content_type)

    def test_provider_errors_are_redacted_for_completion_and_stream(self):
        service = GatewayService(Settings(default_model="fake", allowed_models=("fake",)))
        service.provider = FailingProvider()
        with self._server(service) as base_url:
            body = {"model": "fake", "messages": [{"role": "user", "content": "hello"}]}
            request = Request(
                base_url + "/v1/chat/completions",
                data=json.dumps(body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with self.assertRaises(HTTPError) as raised:
                urlopen(request)
            error = raised.exception
            completion_error = error.read().decode("utf-8")
            error.close()

            body["stream"] = True
            request = Request(
                base_url + "/v1/chat/completions",
                data=json.dumps(body).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            with urlopen(request) as response:
                stream = response.read().decode("utf-8")

        self.assertNotIn("provider-secret", completion_error)
        self.assertIn('"message": "provider request failed"', completion_error)
        self.assertNotIn("provider-secret", stream)
        self.assertIn('"code": "provider_error"', stream)
        self.assertIn("data: [DONE]", stream)

    class _ServerContext:
        def __init__(self, test, service):
            self.test = test
            self.service = service

        def __enter__(self):
            try:
                self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.service))
            except PermissionError:
                self.test.skipTest("sandbox does not allow binding a local test socket")
            self.thread = Thread(target=self.server.serve_forever, daemon=True)
            self.thread.start()
            return f"http://127.0.0.1:{self.server.server_port}"

        def __exit__(self, exc_type, exc_value, traceback):
            self.server.shutdown()
            self.server.server_close()

    def _server(self, service):
        return self._ServerContext(self, service)
