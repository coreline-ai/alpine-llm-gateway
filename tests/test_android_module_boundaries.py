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
        "alpine-runtime-ui-compose": {"alpine-runtime-host", "alpine-workspace-api"},
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

    def test_probe_tty_diagnostic_is_locked_and_excluded_from_production_pack(self) -> None:
        lock = json.loads(
            (ROOT / "runtime/probe/tty-diagnostic-artifacts.lock.json").read_text()
        )
        self.assertFalse(lock["proot"]["production_packaging"])
        artifact = ROOT / lock["proot"]["artifact_path"]
        self.assertTrue(artifact.is_file(), artifact)
        self.assertEqual(lock["proot"]["sha256"], hashlib.sha256(artifact.read_bytes()).hexdigest())
        self.assertEqual(lock["proot"]["size_bytes"], artifact.stat().st_size)
        patch = ROOT / lock["proot"]["patch"]["path"]
        self.assertTrue(patch.is_file(), patch)
        self.assertEqual(lock["proot"]["patch"]["sha256"], hashlib.sha256(patch.read_bytes()).hexdigest())
        self.assertFalse(
            (ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libproot_tty_trace.so").exists()
        )
        relay = lock["resize_relay_proot"]
        self.assertFalse(relay["production_packaging"])
        relay_artifact = ROOT / relay["artifact_path"]
        self.assertTrue(relay_artifact.is_file(), relay_artifact)
        self.assertEqual(
            relay["sha256"], hashlib.sha256(relay_artifact.read_bytes()).hexdigest()
        )
        self.assertEqual(relay["size_bytes"], relay_artifact.stat().st_size)
        relay_patch = ROOT / relay["patch"]["path"]
        self.assertTrue(relay_patch.is_file(), relay_patch)
        self.assertEqual(
            relay["patch"]["sha256"], hashlib.sha256(relay_patch.read_bytes()).hexdigest()
        )
        relay_contract = relay["source_level_relay"]
        self.assertEqual(
            "VIRTUAL_GUEST_TIOCGWINSZ_MEMFD_NO_POST_LAUNCH_SIGNAL",
            relay_contract["mode"],
        )
        self.assertEqual(
            "The host bridge sends only a bounded binary frame to a private supervisor; the supervisor stores it in a private memfd and PRoot applies it only when a guest TIOCGWINSZ exits, without host master TIOCSWINSZ or any post-launch signal",
            relay_contract["guest_routing"],
        )
        self.assertIn("Relay21", relay_contract["physical_topology_proof"])
        self.assertIn("host master TIOCSWINSZ after terminal launch", relay_contract["forbidden"])
        self.assertIn("guest FIFO or polling", relay_contract["forbidden"])
        self.assertIn("production terminal use", relay_contract["forbidden"])
        self.assertIn(
            "setpgid(pid, pid)",
            relay_patch.read_text(encoding="utf-8"),
        )
        self.assertNotIn(
            "kill(primary_tracee, SIGWINCH)",
            relay_patch.read_text(encoding="utf-8"),
        )
        self.assertIn("proot_tty_virtual_winsize", relay_patch.read_text(encoding="utf-8"))
        self.assertIn("PROOT_TTY_VIRTUAL_WINSIZE_FD", relay_patch.read_text(encoding="utf-8"))
        self.assertIn("TIOCGWINSZ", relay_patch.read_text(encoding="utf-8"))
        self.assertIn(
            "tcsetpgrp(STDIN_FILENO, pid)",
            relay_patch.read_text(encoding="utf-8"),
        )
        self.assertIn("tcgetpgrp(STDIN_FILENO) != pid", relay_patch.read_text(encoding="utf-8"))
        self.assertIn(
            "primary_tracee_foreground_verified",
            relay_patch.read_text(encoding="utf-8"),
        )
        self.assertIn("active_tty_tracee_foreground", relay_patch.read_text(encoding="utf-8"))
        self.assertTrue(
            lock["session_relay_launcher"]["contract"]
            ["forwards_direct_proot_child_only"]
        )
        self.assertFalse(
            (ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libproot_tty_resize_relay.so").exists()
        )
        ioctl_bypass = lock["ioctl_filter_bypass_proot"]
        self.assertFalse(ioctl_bypass["production_packaging"])
        ioctl_bypass_artifact = ROOT / ioctl_bypass["artifact_path"]
        self.assertTrue(ioctl_bypass_artifact.is_file(), ioctl_bypass_artifact)
        self.assertEqual(
            ioctl_bypass["sha256"], hashlib.sha256(ioctl_bypass_artifact.read_bytes()).hexdigest()
        )
        self.assertEqual(ioctl_bypass["size_bytes"], ioctl_bypass_artifact.stat().st_size)
        ioctl_bypass_patch = ROOT / ioctl_bypass["patch"]["path"]
        self.assertTrue(ioctl_bypass_patch.is_file(), ioctl_bypass_patch)
        self.assertEqual(
            ioctl_bypass["patch"]["sha256"], hashlib.sha256(ioctl_bypass_patch.read_bytes()).hexdigest()
        )
        self.assertIn("PR_ioctl", ioctl_bypass_patch.read_text(encoding="utf-8"))
        post_winsize_input = lock["post_winsize_input_trace_proot"]
        self.assertFalse(post_winsize_input["production_packaging"])
        post_winsize_input_artifact = ROOT / post_winsize_input["artifact_path"]
        self.assertTrue(post_winsize_input_artifact.is_file(), post_winsize_input_artifact)
        self.assertEqual(
            post_winsize_input["sha256"],
            hashlib.sha256(post_winsize_input_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            post_winsize_input["size_bytes"], post_winsize_input_artifact.stat().st_size
        )
        post_winsize_input_patch = ROOT / post_winsize_input["patch"]["path"]
        self.assertTrue(post_winsize_input_patch.is_file(), post_winsize_input_patch)
        self.assertEqual(
            post_winsize_input["patch"]["sha256"],
            hashlib.sha256(post_winsize_input_patch.read_bytes()).hexdigest(),
        )
        self.assertIn("POST_TIOCGWINSZ_INPUT", post_winsize_input_patch.read_text(encoding="utf-8"))
        self.assertIn("read_enter", post_winsize_input_patch.read_text(encoding="utf-8"))
        self.assertIn("production terminal use", post_winsize_input["contract"]["forbidden"])
        self.assertIn("terminal payload logging", post_winsize_input["contract"]["forbidden"])
        self.assertFalse(
            (
                ROOT
                / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/"
                "libproot_tty_post_winsize_input_trace.so"
            ).exists()
        )
        post_winsize_command_flow = lock["post_winsize_command_flow_trace_proot"]
        self.assertFalse(post_winsize_command_flow["production_packaging"])
        post_winsize_command_flow_artifact = ROOT / post_winsize_command_flow["artifact_path"]
        self.assertTrue(post_winsize_command_flow_artifact.is_file(), post_winsize_command_flow_artifact)
        self.assertEqual(
            post_winsize_command_flow["sha256"],
            hashlib.sha256(post_winsize_command_flow_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            post_winsize_command_flow["size_bytes"],
            post_winsize_command_flow_artifact.stat().st_size,
        )
        post_winsize_command_flow_patch = ROOT / post_winsize_command_flow["patch"]["path"]
        self.assertTrue(post_winsize_command_flow_patch.is_file(), post_winsize_command_flow_patch)
        self.assertEqual(
            post_winsize_command_flow["patch"]["sha256"],
            hashlib.sha256(post_winsize_command_flow_patch.read_bytes()).hexdigest(),
        )
        command_flow_patch_text = post_winsize_command_flow_patch.read_text(encoding="utf-8")
        self.assertIn("POST_TIOCGWINSZ_COMMAND_FLOW", command_flow_patch_text)
        self.assertIn("parent_wait_inflight", command_flow_patch_text)
        self.assertIn("parent_read_exit_nonempty", command_flow_patch_text)
        self.assertIn("production terminal use", post_winsize_command_flow["contract"]["forbidden"])
        self.assertIn("terminal payload logging", post_winsize_command_flow["contract"]["forbidden"])
        self.assertFalse(
            (
                ROOT
                / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/"
                "libproot_tty_post_winsize_command_flow_trace.so"
            ).exists()
        )
        post_read_flow = lock["post_winsize_post_read_flow_trace_proot"]
        self.assertFalse(post_read_flow["production_packaging"])
        post_read_flow_artifact = ROOT / post_read_flow["artifact_path"]
        self.assertTrue(post_read_flow_artifact.is_file(), post_read_flow_artifact)
        self.assertEqual(
            post_read_flow["sha256"],
            hashlib.sha256(post_read_flow_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(post_read_flow["size_bytes"], post_read_flow_artifact.stat().st_size)
        post_read_flow_patch = ROOT / post_read_flow["patch"]["path"]
        self.assertTrue(post_read_flow_patch.is_file(), post_read_flow_patch)
        self.assertEqual(
            post_read_flow["patch"]["sha256"],
            hashlib.sha256(post_read_flow_patch.read_bytes()).hexdigest(),
        )
        post_read_flow_patch_text = post_read_flow_patch.read_text(encoding="utf-8")
        self.assertIn("POST_TIOCGWINSZ_POST_READ_FLOW", post_read_flow_patch_text)
        self.assertIn("parent_post_read_write_enter", post_read_flow_patch_text)
        self.assertIn("parent_post_read_write_exit_nonempty", post_read_flow_patch_text)
        self.assertIn("parent_post_read_write_stdout", post_read_flow_patch_text)
        self.assertIn("parent_post_read_read_enter", post_read_flow_patch_text)
        self.assertIn("production terminal use", post_read_flow["contract"]["forbidden"])
        self.assertIn("terminal payload logging", post_read_flow["contract"]["forbidden"])
        self.assertFalse(
            (
                ROOT
                / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/"
                "libproot_tty_post_winsize_post_read_flow_trace.so"
            ).exists()
        )
        second_get = lock["second_tiocgwinsz_trace_proot"]
        self.assertFalse(second_get["production_packaging"])
        second_get_artifact = ROOT / second_get["artifact_path"]
        self.assertTrue(second_get_artifact.is_file(), second_get_artifact)
        self.assertEqual(
            second_get["sha256"],
            hashlib.sha256(second_get_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(second_get["size_bytes"], second_get_artifact.stat().st_size)
        second_get_patch = ROOT / second_get["patch"]["path"]
        self.assertTrue(second_get_patch.is_file(), second_get_patch)
        self.assertEqual(
            second_get["patch"]["sha256"],
            hashlib.sha256(second_get_patch.read_bytes()).hexdigest(),
        )
        second_get_patch_text = second_get_patch.read_text(encoding="utf-8")
        self.assertIn("POST_TIOCGWINSZ_SECOND_GET", second_get_patch_text)
        self.assertIn("second_tiocgwinsz_enter", second_get_patch_text)
        self.assertIn("second_tiocgwinsz_exit_error", second_get_patch_text)
        self.assertNotIn("SYSARG_1", second_get_patch_text)
        self.assertIn("terminal payload logging", second_get["contract"]["forbidden"])
        self.assertTrue(
            any("errno logging" in forbidden for forbidden in second_get["contract"]["forbidden"])
        )
        self.assertFalse(
            (
                ROOT
                / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/"
                "libproot_tty_second_tiocgwinsz_trace.so"
            ).exists()
        )
        second_target_output = lock["second_tiocgwinsz_target_output_trace_proot"]
        self.assertFalse(second_target_output["production_packaging"])
        second_target_output_artifact = ROOT / second_target_output["artifact_path"]
        self.assertTrue(second_target_output_artifact.is_file(), second_target_output_artifact)
        self.assertEqual(
            second_target_output["sha256"],
            hashlib.sha256(second_target_output_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            second_target_output["size_bytes"], second_target_output_artifact.stat().st_size
        )
        second_target_output_patch = ROOT / second_target_output["patch"]["path"]
        self.assertTrue(second_target_output_patch.is_file(), second_target_output_patch)
        self.assertEqual(
            second_target_output["patch"]["sha256"],
            hashlib.sha256(second_target_output_patch.read_bytes()).hexdigest(),
        )
        second_target_output_patch_text = second_target_output_patch.read_text(encoding="utf-8")
        self.assertIn("POST_TIOCGWINSZ_SECOND_TARGET_OUTPUT", second_target_output_patch_text)
        self.assertIn("second_target_write_stdout", second_target_output_patch_text)
        self.assertIn("second_target_write_exit_nonempty", second_target_output_patch_text)
        self.assertIn("second_target_exit_enter", second_target_output_patch_text)
        self.assertIn("terminal payload logging", second_target_output["contract"]["forbidden"])
        self.assertTrue(
            any(
                "errno logging" in forbidden
                for forbidden in second_target_output["contract"]["forbidden"]
            )
        )
        self.assertFalse(
            (
                ROOT
                / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/"
                "libproot_tty_second_tiocgwinsz_target_output_trace.so"
            ).exists()
        )
        virtual_second_target_output = lock[
            "virtual_winsize_second_target_output_trace_proot"
        ]
        self.assertFalse(virtual_second_target_output["production_packaging"])
        virtual_second_target_output_artifact = ROOT / virtual_second_target_output["artifact_path"]
        self.assertTrue(virtual_second_target_output_artifact.is_file(), virtual_second_target_output_artifact)
        self.assertEqual(
            virtual_second_target_output["sha256"],
            hashlib.sha256(virtual_second_target_output_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            virtual_second_target_output["size_bytes"],
            virtual_second_target_output_artifact.stat().st_size,
        )
        virtual_second_target_output_patch = ROOT / virtual_second_target_output["patch"]["path"]
        self.assertTrue(virtual_second_target_output_patch.is_file(), virtual_second_target_output_patch)
        self.assertEqual(
            virtual_second_target_output["patch"]["sha256"],
            hashlib.sha256(virtual_second_target_output_patch.read_bytes()).hexdigest(),
        )
        virtual_second_target_output_patch_text = virtual_second_target_output_patch.read_text(
            encoding="utf-8"
        )
        self.assertIn("proot_tty_virtual_winsize", virtual_second_target_output_patch_text)
        self.assertIn("PROOT_TTY_VIRTUAL_WINSIZE_FD", virtual_second_target_output_patch_text)
        self.assertIn("POST_TIOCGWINSZ_SECOND_TARGET_OUTPUT", virtual_second_target_output_patch_text)
        self.assertIn("second_target_write_exit_nonempty", virtual_second_target_output_patch_text)
        self.assertIn(
            "host master TIOCSWINSZ after terminal launch",
            virtual_second_target_output["contract"]["forbidden"],
        )
        self.assertIn(
            "terminal payload logging",
            virtual_second_target_output["contract"]["forbidden"],
        )
        self.assertFalse(
            (
                ROOT
                / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/"
                "libproot_tty_virtual_winsize_second_target_output_trace.so"
            ).exists()
        )
        session_launcher = lock["session_relay_launcher"]
        self.assertFalse(session_launcher["production_packaging"])
        session_artifact = ROOT / session_launcher["artifact_path"]
        self.assertTrue(session_artifact.is_file(), session_artifact)
        self.assertEqual(
            session_launcher["sha256"],
            hashlib.sha256(session_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(session_launcher["size_bytes"], session_artifact.stat().st_size)
        self.assertTrue((ROOT / session_launcher["source_path"]).is_file())
        self.assertTrue((ROOT / session_launcher["build_script"]).is_file())
        self.assertFalse(
            (ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libtty_session_relay_launcher.so").exists()
        )
        tracee_foreground_launcher = lock["tracee_foreground_session_launcher"]
        self.assertFalse(tracee_foreground_launcher["production_packaging"])
        tracee_foreground_artifact = ROOT / tracee_foreground_launcher["artifact_path"]
        self.assertTrue(tracee_foreground_artifact.is_file(), tracee_foreground_artifact)
        self.assertEqual(
            tracee_foreground_launcher["sha256"],
            hashlib.sha256(tracee_foreground_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            tracee_foreground_launcher["size_bytes"], tracee_foreground_artifact.stat().st_size
        )
        tracee_foreground_source = (
            ROOT / tracee_foreground_launcher["source_path"]
        ).read_text(encoding="utf-8")
        self.assertIn("relay_supervisor_resize_acknowledged", tracee_foreground_source)
        self.assertNotIn("kill(child, SIGWINCH)", tracee_foreground_source)
        self.assertTrue(tracee_foreground_launcher["contract"]["acknowledges_fixed_resize_request"])
        self.assertFalse(tracee_foreground_launcher["contract"]["forwards_direct_proot_child_only"])
        self.assertIn("host SIGWINCH injection", tracee_foreground_launcher["contract"]["forbidden"])
        self.assertFalse(
            (ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libtty_session_tracee_foreground_launcher.so").exists()
        )
        virtual_launcher = lock["virtual_winsize_session_launcher"]
        self.assertFalse(virtual_launcher["production_packaging"])
        virtual_artifact = ROOT / virtual_launcher["artifact_path"]
        self.assertTrue(virtual_artifact.is_file(), virtual_artifact)
        self.assertEqual(
            virtual_launcher["sha256"],
            hashlib.sha256(virtual_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(virtual_launcher["size_bytes"], virtual_artifact.stat().st_size)
        virtual_source = (ROOT / virtual_launcher["source_path"]).read_text(encoding="utf-8")
        self.assertIn("virtual_winsize_supervisor_stored", virtual_source)
        self.assertIn("virtual_winsize_supervisor_no_write_control", virtual_source)
        self.assertIn("PROOT_TTY_VIRTUAL_WINSIZE_FD", virtual_source)
        self.assertNotIn("ioctl(STDIN_FILENO, TIOCSWINSZ", virtual_source)
        self.assertTrue(virtual_launcher["contract"]["uses_private_inherited_memfd"])
        self.assertTrue(virtual_launcher["contract"]["sends_no_host_tiocswinsz"])
        self.assertTrue(virtual_launcher["contract"]["sends_no_guest_sigwinch"])
        self.assertTrue(virtual_launcher["contract"]["sends_no_post_launch_signal"])
        self.assertTrue(virtual_launcher["contract"]["has_no_write_negative_control"])
        self.assertIn("guest FIFO or polling", virtual_launcher["contract"]["forbidden"])
        self.assertFalse(
            (ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libtty_session_virtual_resize_launcher.so").exists()
        )
        host_pty_control = lock["host_pty_resize_control"]
        self.assertFalse(host_pty_control["production_packaging"])
        host_pty_artifact = ROOT / host_pty_control["artifact_path"]
        self.assertTrue(host_pty_artifact.is_file(), host_pty_artifact)
        self.assertEqual(
            host_pty_control["sha256"],
            hashlib.sha256(host_pty_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(host_pty_control["size_bytes"], host_pty_artifact.stat().st_size)
        host_pty_source = (ROOT / host_pty_control["source_path"]).read_text(encoding="utf-8")
        self.assertIn("TIOCSWINSZ", host_pty_source)
        self.assertIn("host_resize_control=PASS", host_pty_source)
        self.assertIn("app terminal payload", host_pty_control["contract"]["forbidden"])
        self.assertFalse(
            (ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libtty_host_resize_control.so").exists()
        )
        helper = lock["guest_winsize_helper"]
        self.assertFalse(helper["production_packaging"])
        helper_artifact = ROOT / helper["artifact_path"]
        self.assertTrue(helper_artifact.is_file(), helper_artifact)
        self.assertEqual(
            helper["sha256"],
            hashlib.sha256(helper_artifact.read_bytes()).hexdigest(),
        )
        self.assertEqual(helper["size_bytes"], helper_artifact.stat().st_size)
        self.assertTrue((ROOT / helper["source_path"]).is_file())
        self.assertTrue((ROOT / helper["build_script"]).is_file())
        self.assertFalse(
            (ROOT / "alpine-runtime-pack-bundled/src/main/jniLibs/arm64-v8a/libtty_winsize_probe.so").exists()
        )
        integrated_build = (ROOT / "integrated-app/build.gradle.kts").read_text()
        self.assertNotIn("alpine-runtime-probe", integrated_build)

    def test_forkpty_probe_exposure_is_debug_gated_and_product_resize_stays_closed(self) -> None:
        configuration = (
            ROOT
            / "alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/"
            "AndroidAlpineRuntimeFactory.kt"
        ).read_text()
        manager = (
            ROOT
            / "alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/"
            "AndroidAlpineRuntimeManager.kt"
        ).read_text()
        launcher = (
            ROOT
            / "alpine-runtime-android/src/main/kotlin/dev/alpine/runtime/android/internal/"
            "ProotProcessLauncher.kt"
        ).read_text()
        bridge = (
            ROOT / "alpine-runtime-android/src/main/cpp/pty_bridge.c"
        ).read_text()
        integrated_manifest = (
            ROOT / "integrated-app/src/main/AndroidManifest.xml"
        ).read_text()

        self.assertIn("val ttyDiagnosticForkPtyDirect: Boolean = false", configuration)
        self.assertIn(
            "require(!ttyDiagnosticForkPtyDirect || enableTtyIoctlDiagnostics)",
            configuration,
        )
        self.assertIn("ApplicationInfo.FLAG_DEBUGGABLE", manager)
        self.assertIn("TTY_DIAGNOSTIC_MANIFEST_KEY", manager)
        self.assertIn(
            "configuration.ttyDiagnosticForkPtyDirect && ttyDiagnosticFile != null",
            manager,
        )
        self.assertNotIn("dev.alpine.runtime.TTY_DIAGNOSTIC_PROBE_ENABLED", integrated_manifest)

        self.assertIn('put("ALPINE_TERMINAL_MODE", "native-pty")', launcher)
        self.assertIn('put("ALPINE_TERMINAL_RESIZE_CHANNEL", "unsupported")', launcher)
        self.assertIn("RuntimeTerminalResizeSupport.INITIAL_SIZE_ONLY", launcher)
        self.assertIn("if (ttyDiagnosticForkPtyDirect) RuntimeTerminalResizeSupport.DYNAMIC", launcher)
        self.assertIn("probe-forkpty-direct", launcher)
        self.assertIn('"COLUMNS",', launcher)
        self.assertIn('"LINES",', launcher)
        host_environment = launcher[launcher.index("private fun hostEnvironment("):]
        self.assertLess(
            host_environment.index("putAll(guestEnvironment)"),
            host_environment.index('remove("COLUMNS")'),
        )
        self.assertLess(
            host_environment.index('remove("COLUMNS")'),
            host_environment.index('remove("LINES")'),
        )
        self.assertNotIn("SIGWINCH", bridge)

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

    def test_runtime_foreground_service_removes_notification_on_direct_stop(self) -> None:
        service = (
            ROOT
            / "alpine-runtime-background-android/src/main/kotlin/dev/alpine/runtime/background/android/"
            "RuntimeForegroundService.kt"
        ).read_text()
        listener = (
            ROOT
            / "alpine-runtime-background-android/src/main/kotlin/dev/alpine/runtime/background/android/"
            "RuntimeForegroundProcessListener.kt"
        ).read_text()
        instrumentation = (
            ROOT
            / "alpine-runtime-background-android/src/androidTest/kotlin/dev/alpine/runtime/background/android/"
            "RuntimeForegroundServiceInstrumentedTest.kt"
        ).read_text()

        destroy_body = service.split("override fun onDestroy()", 1)[1].split("override fun onBind", 1)[0]
        self.assertIn("stopForeground(STOP_FOREGROUND_REMOVE)", destroy_body)
        self.assertIn("RuntimeProcessLeaseAction.STOP_FOREGROUND -> stopForeground()", listener)
        self.assertIn("lastRuntimeProcessClearsForegroundServiceAndNotification", instrumentation)
        self.assertIn("requireNotificationVisibility", instrumentation)

    def test_gateway_recovery_lease_prevents_stale_restart_from_touching_new_owner(self) -> None:
        supervisor = (
            ROOT
            / "alpine-llm-bridge/src/main/kotlin/dev/alpine/runtime/bridge/"
            "AlpineLlmBridgeRecoverySupervisor.kt"
        ).read_text()
        host = (
            ROOT
            / "integrated-app/src/main/java/dev/alpine/integrated/IntegratedAlpineLlmHost.kt"
        ).read_text()

        self.assertIn("fun interface AlpineLlmBridgeRecoveryLease", supervisor)
        self.assertIn("restartGateway(lease)", supervisor)
        self.assertIn("AlpineLlmBridgeRecoveryLease { isRecovering(expectedGeneration) }", supervisor)
        self.assertIn("restartAfterUnexpectedFailure(\n        lease: AlpineLlmBridgeRecoveryLease", host)
        recovery = host.split("private fun restartAfterUnexpectedFailure(", 1)[1].split(
            "private fun ownsActiveRecovery(", 1
        )[0]
        self.assertGreaterEqual(recovery.count("ownsActiveRecovery(active, lease)"), 3)
        self.assertIn("active.stop().toCompletableFuture().join()", recovery)
        self.assertNotIn("completeFromStream", recovery)
        self.assertNotIn("streamForHostBridge", recovery)
        self.assertNotIn("AndroidDirectChatBackend", recovery)

    def test_integrated_ui_regression_wakes_dream_without_keyguard_bypass(self) -> None:
        instrumentation = (
            ROOT
            / "integrated-app/src/androidTest/java/dev/alpine/integrated/"
            "IntegratedFastChatInstrumentedTest.kt"
        ).read_text()

        self.assertIn("wakeScreenForUiTest()", instrumentation)
        self.assertIn('input keyevent KEYCODE_WAKEUP', instrumentation)
        self.assertNotIn("dismiss-keyguard", instrumentation.lower())
        self.assertNotIn("KEYCODE_MENU", instrumentation)

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
