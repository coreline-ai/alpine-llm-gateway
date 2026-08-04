from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class MobileOAuthWireContractTest(unittest.TestCase):
    def test_android_ios_and_dart_share_auth_event_wire_values(self) -> None:
        sources = self._sources(
            "packages/mobile_agent_auth/lib/mobile_agent_auth.dart",
            "packages/mobile_agent_auth/android/src/main/kotlin/ai/coreline/mobile_agent_auth/MobileAgentAuthPlugin.kt",
            "packages/mobile_agent_auth/ios/mobile_agent_auth/Sources/mobile_agent_auth/MobileAgentAuthPlugin.swift",
        )
        for value in (
            "protocolVersion",
            "authStateEvents",
            "secureSessionStorage",
            "nativeAuthorizedTransport",
            "restored",
            "signed_in",
            "signed_out",
            "reauthentication_required",
        ):
            for path, source in sources.items():
                self.assertIn(value, source, f"{value!r} missing from {path}")

    def test_android_ios_and_dart_share_cancel_wire_values(self) -> None:
        sources = self._sources(
            "packages/mobile_agent_llm_transport/lib/mobile_agent_llm_transport.dart",
            "packages/mobile_agent_auth/android/src/main/kotlin/ai/coreline/mobile_agent_auth/NativeLlmTransportController.kt",
            "packages/mobile_agent_auth/ios/mobile_agent_auth/Sources/mobile_agent_auth/NativeLlmTransportController.swift",
        )
        for value in (
            "requestState",
            "requestId",
            "localCancelled",
            "serverAcknowledgment",
            "preparing",
            "streaming",
            "cancelling",
            "not_found",
            "accepted",
            "not_active",
            "not_required",
            "unavailable",
        ):
            for path, source in sources.items():
                self.assertIn(value, source, f"{value!r} missing from {path}")

    def test_conversation_vault_method_and_schema_contract_is_shared(self) -> None:
        sources = self._sources(
            "apps/mobile_agent/lib/src/conversation_store.dart",
            "packages/mobile_agent_auth/android/src/main/kotlin/ai/coreline/mobile_agent_auth/MobileAgentAuthPlugin.kt",
            "packages/mobile_agent_auth/android/src/main/kotlin/ai/coreline/mobile_agent_auth/SecureConversationStore.kt",
            "packages/mobile_agent_auth/ios/mobile_agent_auth/Sources/mobile_agent_auth/MobileAgentAuthPlugin.swift",
            "packages/mobile_agent_auth/ios/mobile_agent_auth/Sources/mobile_agent_auth/SecureConversationStore.swift",
        )
        for value in (
            "MAX_CONVERSATIONS = 20",
            "MAX_MESSAGES_PER_CONVERSATION = 64",
            "MAX_MESSAGE_CHARACTERS = 32 * 1024",
        ):
            self.assertIn(value, sources[
                "packages/mobile_agent_auth/android/src/main/kotlin/ai/coreline/mobile_agent_auth/SecureConversationStore.kt"
            ], f"{value!r} missing from Android vault")
        for value in (
            "schemaVersion = 1",
            "maxConversations = 20",
            "maxMessagesPerConversation = 64",
            "maxMessageCharacters = 32 * 1024",
            "AES.GCM",
            "kSecAttrAccessibleWhenUnlockedThisDeviceOnly",
        ):
            self.assertIn(value, sources[
                "packages/mobile_agent_auth/ios/mobile_agent_auth/Sources/mobile_agent_auth/SecureConversationStore.swift"
            ], f"{value!r} missing from iOS vault")
        for path, source in sources.items():
            if path.endswith("SecureConversationStore.kt") or path.endswith("SecureConversationStore.swift"):
                continue
            for value in (
                "mobile_agent_conversations",
                "loadConversationSnapshot",
                "saveConversationSnapshot",
                "clearConversationSnapshot",
            ):
                self.assertIn(value, source, f"{value!r} missing from {path}")

    def _sources(self, *paths: str) -> dict[str, str]:
        return {
            path: (ROOT / path).read_text(encoding="utf-8")
            for path in paths
        }


if __name__ == "__main__":
    unittest.main()
