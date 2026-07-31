import unittest
import json
from http.server import ThreadingHTTPServer
from threading import Thread
from urllib.request import Request, urlopen

from alpine_llm.config import Settings
from alpine_llm.gateway import GatewayService, make_handler
from alpine_llm.protocol import CompletionRequest, CompletionResult
from alpine_llm.protocol import Usage


class FakeProvider:
    def complete(self, request):
        return CompletionResult(request.model, "fake response", usage=Usage(1, 2))

    def stream(self, request):
        from alpine_llm.protocol import CompletionDelta
        yield CompletionDelta(text="fake ")
        yield CompletionDelta(text="stream")


class GatewayTests(unittest.TestCase):
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
        finally:
            server.shutdown()
            server.server_close()
