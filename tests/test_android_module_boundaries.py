from __future__ import annotations

import hashlib
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class AndroidModuleBoundaryTests(unittest.TestCase):
    SDK_MODULES = {
        "alpine-runtime-api": set(),
        "alpine-runtime-android": {"alpine-runtime-api"},
        "alpine-runtime-background-android": {"alpine-runtime-api"},
        "alpine-runtime-artifact-play": {"alpine-runtime-api"},
        "alpine-runtime-pack-bundled": {"alpine-runtime-api"},
        "alpine-runtime-pack-x86_64": {"alpine-runtime-api"},
        "alpine-llm-bridge": {"alpine-runtime-api", "android"},
        "alpine-llm-gateway-pack-bundled": {"alpine-llm-bridge"},
        "alpine-runtime-ui-compose": {"alpine-runtime-host"},
        "alpine-runtime-testkit": {"alpine-runtime-api"},
        "alpine-runtime-host": {"alpine-runtime-api"},
        "alpine-chat-routing": set(),
        "alpine-chat-feature": {"alpine-chat-routing"},
        "alpine-chat-provider-android": {"alpine-chat-feature", "android"},
        "alpine-chat-backend-direct": {"alpine-chat-routing", "android"},
        "alpine-chat-backend-alpine": {"alpine-chat-routing", "alpine-llm-bridge"},
        "alpine-workspace-api": set(),
        "alpine-workspace-android": {"alpine-workspace-api"},
    }

    def test_demo_does_not_depend_on_runtime_or_probe_modules(self) -> None:
        build = (ROOT / "demo-chatbot" / "build.gradle.kts").read_text()
        self.assertNotIn('project(":alpine-runtime', build)
        self.assertNotIn('project(":runtime-probe', build)

        source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "demo-chatbot" / "src" / "main").rglob("*.kt")
        )
        self.assertNotIn("AlpineRuntime", source)
        self.assertNotIn("HostBridgeServer", source)

    def test_probe_is_a_separate_host_and_uses_only_runtime_modules(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text()
        build = (ROOT / "alpine-runtime-probe" / "build.gradle.kts").read_text()

        self.assertIn('include(":alpine-runtime-probe")', settings)
        self.assertIn('implementation(project(":alpine-runtime-android"))', build)
        self.assertIn('implementation(project(":alpine-runtime-pack-bundled"))', build)
        self.assertIn('implementation(project(":alpine-runtime-pack-x86_64"))', build)
        self.assertNotIn('implementation(project(":android"))', build)
        self.assertNotIn('implementation(project(":demo-chatbot"))', build)

    def test_bundled_pack_payload_matches_production_lock(self) -> None:
        lock = json.loads(
            (ROOT / "runtime" / "alpine-3.21.3-arm64.lock.json").read_text()
        )
        files = {
            ROOT / "alpine-runtime-pack-bundled/src/main/assets/alpine-minirootfs.tar.gz.asset":
                lock["rootfs"]["sha256"],
            ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libproot.so":
                lock["proot"]["sha256"],
            ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libproot-loader.so":
                lock["loader"]["sha256"],
        }
        for path, expected in files.items():
            self.assertTrue(path.is_file(), path)
            self.assertEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest())

        self.assertFalse(
            (ROOT / "alpine-runtime-probe/src/debug/assets/alpine-minirootfs.tar.gz.asset").exists()
        )

    def test_x86_64_pack_payload_matches_experimental_lock(self) -> None:
        lock = json.loads(
            (ROOT / "runtime" / "alpine-3.21.3-x86_64.lock.json").read_text()
        )
        self.assertEqual("experimental_requires_emulator_e2e", lock["support_status"])
        files = {
            ROOT / "alpine-runtime-pack-x86_64/src/main/assets/alpine-minirootfs-x86_64.tar.gz.asset":
                lock["rootfs"]["sha256"],
            ROOT / "alpine-runtime-pack-x86_64/src/main/jniLibs/x86_64/libproot.so":
                lock["proot"]["sha256"],
            ROOT / "alpine-runtime-pack-x86_64/src/main/jniLibs/x86_64/libproot-loader.so":
                lock["loader"]["sha256"],
        }
        for path, expected in files.items():
            self.assertTrue(path.is_file(), path)
            self.assertEqual(expected, hashlib.sha256(path.read_bytes()).hexdigest())

    def test_runtime_implementation_moved_out_of_provider_module(self) -> None:
        self.assertFalse(
            (ROOT / "android/src/main/java/dev/alpine/llm/AlpineRuntime.kt").exists()
        )
        runtime_source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "alpine-runtime-android/src/main").rglob("*.kt")
        )
        runtime_build = (ROOT / "alpine-runtime-android/build.gradle.kts").read_text()
        self.assertIn("applicationInfo.nativeLibraryDir", runtime_source)
        self.assertIn("RuntimeArtifactInstaller", runtime_source)
        self.assertNotIn('project(":android")', runtime_build)
        for marker in ("dev.alpine.llm", "OAuth", "HostBridgeServer", "@Composable"):
            self.assertNotIn(marker, runtime_source)

    def test_sdk_modules_have_only_approved_project_dependencies(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text()
        project_modules = {*self.SDK_MODULES, "android"}
        for module, expected_dependencies in self.SDK_MODULES.items():
            self.assertIn(f'include(":{module}")', settings)
            build = (ROOT / module / "build.gradle.kts").read_text()
            actual_dependencies = {
                candidate
                for candidate in project_modules
                if f'project(":{candidate}")' in build
            }
            self.assertEqual(expected_dependencies, actual_dependencies, module)
            self.assertNotIn('project(":demo-chatbot")', build)

    def test_core_api_has_no_android_compose_or_provider_types(self) -> None:
        source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "alpine-runtime-api" / "src" / "main").rglob("*.kt")
        )
        forbidden = (
            "android.",
            "androidx.",
            "@Composable",
            "OAuth",
            "HostBridge",
            "ProviderConfig",
        )
        for marker in forbidden:
            self.assertNotIn(marker, source)

        routing_source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "alpine-chat-routing" / "src" / "main").rglob("*.kt")
        )
        for marker in ("android.", "androidx.", "@Composable", "OAuth", "HostBridgeServer"):
            self.assertNotIn(marker, routing_source)

        host_source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "alpine-runtime-host" / "src" / "main").rglob("*.kt")
        )
        for marker in ("android.", "androidx.", "@Composable", "android.app.Activity"):
            self.assertNotIn(marker, host_source)

    def test_android_context_is_confined_to_android_adapter_modules(self) -> None:
        android_adapters = {
            "alpine-runtime-android",
            "alpine-runtime-background-android",
            "alpine-runtime-artifact-play",
            "alpine-runtime-pack-bundled",
            "alpine-runtime-pack-x86_64",
            "alpine-llm-gateway-pack-bundled",
            "alpine-workspace-android",
            "alpine-chat-feature",
            "alpine-chat-provider-android",
        }
        for module in self.SDK_MODULES:
            source_root = ROOT / module / "src" / "main"
            source = "\n".join(
                path.read_text(errors="replace")
                for path in source_root.rglob("*.kt")
            )
            if module in android_adapters:
                self.assertIn("android.content.Context", source)
            else:
                self.assertNotIn("android.content.Context", source)

    def test_public_api_dump_is_checked_in_and_app_neutral(self) -> None:
        dump = ROOT / "alpine-runtime-api" / "api" / "alpine-runtime-api.txt"
        self.assertTrue(dump.is_file())
        text = dump.read_text()
        self.assertIn("dev.alpine.runtime.api.AlpineRuntimeManager", text)
        self.assertNotIn("android.", text)
        self.assertNotIn("androidx.", text)

    def test_common_chat_feature_is_backend_neutral_and_reused_by_demo(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text()
        feature_build = (ROOT / "alpine-chat-feature/build.gradle.kts").read_text()
        demo_build = (ROOT / "demo-chatbot/build.gradle.kts").read_text()
        feature_source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "alpine-chat-feature/src/main").rglob("*.kt")
        )
        demo_source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "demo-chatbot/src/main").rglob("*.kt")
        )

        self.assertIn('include(":alpine-chat-feature")', settings)
        self.assertIn('project(":alpine-chat-feature")', demo_build)
        self.assertIn('project(":alpine-chat-routing")', feature_build)
        for dependency in (":android", ":alpine-runtime", ":alpine-chat-backend"):
            self.assertNotIn(f'project("{dependency}', feature_build)
        for marker in (
            "OAuthManager",
            "ProviderProfile",
            "HostBridgeServer",
            "AlpineRuntime",
            "ChatCompletionSession",
        ):
            self.assertNotIn(marker, feature_source)
        self.assertNotIn("class ChatViewModel", demo_source)
        self.assertNotIn("fun AlpineChatScreen", demo_source)

    def test_provider_host_is_reused_by_demo_and_integrated_app(self) -> None:
        provider_build = (ROOT / "alpine-chat-provider-android/build.gradle.kts").read_text()
        provider_source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "alpine-chat-provider-android/src/main").rglob("*.kt")
        )
        demo_build = (ROOT / "demo-chatbot/build.gradle.kts").read_text()
        integrated_build = (ROOT / "integrated-app/build.gradle.kts").read_text()
        integrated_source = (
            ROOT / "integrated-app/src/main/java/dev/alpine/integrated/IntegratedMainActivity.kt"
        ).read_text()

        self.assertIn('project(":android")', provider_build)
        self.assertIn('project(":alpine-chat-feature")', provider_build)
        self.assertIn('project(":alpine-chat-provider-android")', demo_build)
        self.assertIn('project(":alpine-chat-provider-android")', integrated_build)
        self.assertIn("DirectChatHostController", provider_source)
        self.assertIn("AlpineChatScreen", integrated_source)
        self.assertNotIn("class ProviderProfile", integrated_source)
        self.assertNotIn("OAuthManager", integrated_source)

    def test_llm_bridge_and_gateway_payload_are_optional_boundaries(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text()
        runtime_probe = (ROOT / "alpine-runtime-probe" / "build.gradle.kts").read_text()
        runtime_pack = (ROOT / "alpine-runtime-pack-bundled" / "build.gradle.kts").read_text()

        self.assertIn('include(":alpine-llm-gateway-pack-bundled")', settings)
        self.assertNotIn("alpine-llm-bridge", runtime_probe)
        self.assertNotIn("alpine-llm-gateway", runtime_probe)
        self.assertNotIn("alpine-llm-gateway", runtime_pack)

    def test_host_bridge_implementation_moved_out_of_provider_module(self) -> None:
        for name in ("HostBridgeServer.kt", "AlpineLlmGatewayClient.kt"):
            self.assertFalse((ROOT / "android/src/main/java/dev/alpine/llm" / name).exists())
            self.assertTrue((ROOT / "alpine-llm-bridge/src/main/kotlin/dev/alpine/llm" / name).is_file())

    def test_guest_boundary_contains_no_upstream_oauth_credential_fields(self) -> None:
        controller = (
            ROOT
            / "alpine-llm-bridge/src/main/kotlin/dev/alpine/runtime/bridge/AlpineLlmBridgeController.kt"
        ).read_text()
        environment = (
            ROOT
            / "alpine-llm-bridge/src/main/kotlin/dev/alpine/runtime/bridge/LlmBridgeEnvironmentContributor.kt"
        ).read_text()
        for forbidden in ("accessToken", "refreshToken", "clientSecret"):
            self.assertNotIn(forbidden, controller)
            self.assertNotIn(forbidden, environment)
        self.assertIn('"api_key_file"', controller)
        self.assertNotIn('.put("api_key",', controller)
        self.assertIn("ALPINE_LLM_CREDENTIAL_FILE", environment)

    def test_python_gateway_asset_matches_its_separate_lock(self) -> None:
        lock = json.loads(
            (ROOT / "runtime/alpine-llm-gateway-0.3.0.lock.json").read_text()
        )
        artifact = ROOT / lock["artifact"]["path"]
        self.assertTrue(artifact.is_file())
        self.assertEqual(lock["artifact"]["size_bytes"], artifact.stat().st_size)
        self.assertEqual(
            lock["artifact"]["sha256"],
            hashlib.sha256(artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual("1", lock["protocol_version"])

    def test_chat_router_forbids_post_dispatch_fallback_and_raw_error_payloads(self) -> None:
        contracts = (
            ROOT
            / "alpine-chat-routing/src/main/kotlin/dev/alpine/chat/routing/ChatRoutingContracts.kt"
        ).read_text()
        router = (
            ROOT
            / "alpine-chat-routing/src/main/kotlin/dev/alpine/chat/routing/SafeChatRouter.kt"
        ).read_text()
        self.assertIn("FALLBACK_ELIGIBLE_FAILURES", router)
        self.assertIn("primaryPreparation is ChatBackendPreparation.Unavailable", router)
        self.assertNotIn("exceptionMessage", contracts)
        self.assertNotIn("errorBody", contracts)
        self.assertNotIn("requestJson:", contracts.split("ChatRoutingAuditEvent", 1)[1])
        self.assertIn("DUPLICATE_REJECTED", router)

    def test_phase6_custom_ui_sample_has_no_compose_or_demo_dependency(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text()
        build = (ROOT / "alpine-integration-sample" / "build.gradle.kts").read_text()
        source = "\n".join(
            path.read_text(errors="replace")
            for path in (ROOT / "alpine-integration-sample" / "src" / "main").rglob("*")
            if path.is_file()
        )

        self.assertIn('include(":alpine-integration-sample")', settings)
        for dependency in (
            'project(":alpine-runtime-host")',
            'project(":alpine-runtime-android")',
            'project(":alpine-runtime-pack-bundled")',
        ):
            self.assertIn(dependency, build)
        self.assertNotIn("compose", build.lower())
        self.assertNotIn("androidx.compose", source)
        self.assertNotIn('project(":demo-chatbot")', build)
        self.assertNotIn('project(":alpine-runtime-ui-compose")', build)

    def test_phase6_integrated_shell_assembles_only_reusable_modules(self) -> None:
        settings = (ROOT / "settings.gradle.kts").read_text()
        build = (ROOT / "integrated-app" / "build.gradle.kts").read_text()

        self.assertIn('include(":integrated-app")', settings)
        for dependency in (
            'project(":alpine-runtime-ui-compose")',
            'project(":alpine-chat-routing")',
            'project(":alpine-chat-backend-direct")',
            'project(":alpine-chat-backend-alpine")',
        ):
            self.assertIn(dependency, build)
        self.assertNotIn('project(":demo-chatbot")', build)

    def test_package_install_contract_is_fail_closed_and_ui_neutral(self) -> None:
        packages = (
            ROOT
            / "alpine-runtime-api/src/main/kotlin/dev/alpine/runtime/api/RuntimePackages.kt"
        ).read_text()
        self.assertIn("RuntimePackageAllowlistPolicy", packages)
        self.assertIn("RuntimePackageApproval", packages)
        self.assertIn('executable = "/sbin/apk"', packages)
        self.assertNotIn("/bin/sh", packages)
        self.assertNotIn("android.", packages)
        self.assertNotIn("androidx.", packages)

    def test_proot_and_talloc_remain_outside_android_jni_link_boundary(self) -> None:
        native_bridge = (
            ROOT
            / "alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/NativePtyBridge.kt"
        ).read_text()
        launcher = (
            ROOT
            / "alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/ProotProcessLauncher.kt"
        ).read_text()
        cmake = (ROOT / "alpine-runtime-android/src/main/cpp/CMakeLists.txt").read_text()

        self.assertIn('System.loadLibrary("alpine-runtime-pty")', native_bridge)
        self.assertNotIn('System.loadLibrary("proot")', native_bridge)
        self.assertNotIn('System.loadLibrary("talloc")', native_bridge)
        self.assertIn("ProcessBuilder(command)", launcher)
        self.assertNotIn("proot", cmake.lower())
        self.assertNotIn("talloc", cmake.lower())

    def test_only_explicit_alpine_hosts_consume_runtime_payload_packs(self) -> None:
        allowed = {
            "alpine-integration-sample",
            "alpine-llm-bridge-probe",
            "alpine-runtime-probe",
            "integrated-app",
        }
        consumers = set()
        for build in ROOT.glob("*/build.gradle.kts"):
            text = build.read_text()
            if "alpine-runtime-pack-bundled" in text or "alpine-runtime-pack-x86_64" in text:
                consumers.add(build.parent.name)
        self.assertEqual(allowed, consumers)


if __name__ == "__main__":
    unittest.main()
