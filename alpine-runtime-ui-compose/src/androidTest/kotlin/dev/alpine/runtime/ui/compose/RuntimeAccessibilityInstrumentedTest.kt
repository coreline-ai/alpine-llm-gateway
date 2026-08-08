package dev.alpine.runtime.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import dev.alpine.runtime.api.RuntimeLifecycleState
import dev.alpine.runtime.api.RuntimePackageCatalog
import dev.alpine.runtime.api.RuntimePackageMetadata
import dev.alpine.runtime.api.RuntimeState
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.host.RuntimeTerminalExit
import dev.alpine.workspace.api.WorkspaceEntry
import dev.alpine.workspace.api.WorkspaceEntryType
import dev.alpine.workspace.api.WorkspaceHostState
import dev.alpine.workspace.api.WorkspacePath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RuntimeAccessibilityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun talkBackStateAndTerminalOutputSemanticsExistAtTwoHundredPercentFont() {
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING, activeVersion = "test"),
            sessionActive = true,
            terminalActive = true,
            terminalText = "Alpine ready",
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    androidx.compose.foundation.layout.Column {
                        RuntimeDashboard(state, {}, {}, {}, {}, {}, {})
                        RuntimeTerminalPanel(state, {}, {}, {}, {})
                    }
                }
            }
        }

        compose.onNode(hasStateDescription("실행 중")).assertExists()
        compose.onNode(hasContentDescription("Alpine 터미널 출력")).assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun koreanImeAndExternalEnterSubmitTerminalCommands() {
        val sent = mutableListOf<String>()
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING),
            sessionActive = true,
            terminalActive = true,
        )
        compose.setContent {
            MaterialTheme {
                RuntimeTerminalPanel(state, {}, { sent += it }, {}, {})
            }
        }
        val input = compose.onNode(hasSetTextAction())

        input.performTextInput("한글 확인")
        input.performImeAction()
        compose.runOnIdle { assertEquals(listOf("한글 확인"), sent) }

        input.performTextInput("external-keyboard")
        input.performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
        compose.runOnIdle { assertEquals(listOf("한글 확인", "external-keyboard"), sent) }
    }

    @Test
    fun developerToolSmokeActionExposesOnlyTheSelectedFixedProfile() {
        var executedProfile: RuntimeToolProfile? = null
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING),
            sessionActive = true,
        )
        compose.setContent {
            MaterialTheme {
                RuntimePackagePanel(
                    state = state,
                    allowlistedPackages = setOf("git", "python3", "py3-pip", "openssh-client", "nodejs", "npm"),
                    onApprovedInstall = {},
                    onRunToolSmoke = { executedProfile = it },
                )
            }
        }

        compose.onNode(hasTestTag("runtime_tool_smoke_git")).performClick()
        compose.runOnIdle {
            assertEquals("git", executedProfile?.id)
            assertEquals("/usr/bin/git", executedProfile?.smokeRequest?.executable)
            assertEquals(listOf("--version"), executedProfile?.smokeRequest?.arguments)
        }
    }

    @Test
    fun packageSnapshotShowsLicenseSizeAndNetworkLimitsBeforeApproval() {
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING),
            sessionActive = true,
        )
        val catalog = RuntimePackageCatalog(
            listOf(
                RuntimePackageMetadata(
                    packageName = "git",
                    version = "2.47.3-r0",
                    licenseExpression = "GPL-2.0-only",
                    downloadBytes = 3_414_900,
                    installedBytes = 6_997_971,
                    repository = "main",
                    architecture = "aarch64",
                    snapshotId = "Alpine v3.21 aarch64 APKINDEX · 2026-08-08",
                    sourceUrl = "https://example.test/alpine-index",
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                RuntimePackagePanel(
                    state = state,
                    allowlistedPackages = setOf("git"),
                    onApprovedInstall = {},
                    packageCatalog = catalog,
                )
            }
        }

        compose.onNode(hasTestTag("runtime_package_selection")).performTextInput("git")
        compose.onNode(hasText("GPL-2.0-only", substring = true)).assertExists()
        compose.onNode(hasText("의존성, index, cache", substring = true)).assertExists()
        compose.onNode(hasTestTag("runtime_package_review")).performClick()
        // The modal intentionally repeats the snapshot shown in the background selection summary.
        compose.onAllNodes(hasText("Alpine v3.21 aarch64 APKINDEX", substring = true)).assertCountEquals(2)
    }

    @Test
    fun workspaceImportExportAndShareActionsForwardOnlySelectedWorkspacePath() {
        val selected = WorkspacePath("notes/today.txt")
        var importRequested = false
        var exportRequested: WorkspacePath? = null
        var shareRequested: WorkspacePath? = null
        compose.setContent {
            MaterialTheme {
                RuntimeWorkspacePanel(
                    state = WorkspaceHostState(
                        entries = listOf(
                            WorkspaceEntry(selected, WorkspaceEntryType.FILE, 12, 1),
                        ),
                        selectedFile = selected,
                        editorText = "workspace text",
                    ),
                    onRefresh = {},
                    onNavigate = {},
                    onOpen = {},
                    onSave = {},
                    onCreateFile = {},
                    onCreateDirectory = {},
                    onRenameSelected = {},
                    onDeleteSelected = {},
                    onSearch = {},
                    onRequestImport = { importRequested = true },
                    onRequestExport = { exportRequested = it },
                    onRequestShare = { shareRequested = it },
                )
            }
        }

        compose.onNode(hasTestTag("workspace_import")).performClick()
        compose.onNode(hasTestTag("workspace_export")).performClick()
        compose.onNode(hasTestTag("workspace_share")).performClick()
        compose.runOnIdle {
            assertEquals(true, importRequested)
            assertEquals(selected, exportRequested)
            assertEquals(selected, shareRequested)
        }
    }

    @Test
    fun terminalExitSummaryIsAccessibleWithoutReplayingGuestOutput() {
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING),
            sessionActive = true,
            lastTerminalExit = RuntimeTerminalExit(
                terminalId = "closed-terminal",
                title = "빌드 셸",
                exitCode = 23,
            ),
        )
        compose.setContent {
            MaterialTheme {
                RuntimeTerminalPanel(state, {}, {}, {}, {})
            }
        }

        compose.onNode(hasContentDescription("마지막 터미널 종료 상태")).assertExists()
        compose.onNode(hasText("빌드 셸 세션이 종료되었습니다. 종료 코드 23", substring = true)).assertExists()
    }

    @Test
    fun terminalTerminateAndKillAreConfirmedAndForwardOnlyExplicitActions() {
        var terminateCount = 0
        var killCount = 0
        val state = RuntimeHostState(
            runtimeState = RuntimeState(RuntimeLifecycleState.RUNNING),
            sessionActive = true,
            terminalActive = true,
        )
        compose.setContent {
            MaterialTheme {
                RuntimeTerminalPanel(
                    state = state,
                    onOpen = {},
                    onSend = {},
                    onInterrupt = {},
                    onClose = {},
                    onTerminate = { terminateCount += 1 },
                    onKill = { killCount += 1 },
                )
            }
        }

        compose.onNode(hasTestTag("runtime_terminal_kill")).performClick()
        compose.runOnIdle { assertEquals(0, killCount) }
        compose.onNode(hasTestTag("runtime_terminal_signal_cancel")).performClick()
        compose.runOnIdle { assertEquals(0, killCount) }

        compose.onNode(hasTestTag("runtime_terminal_terminate")).performClick()
        compose.onNode(hasTestTag("runtime_terminal_terminate_confirm")).performClick()
        compose.runOnIdle { assertEquals(1, terminateCount) }

        compose.onNode(hasTestTag("runtime_terminal_kill")).performClick()
        compose.onNode(hasTestTag("runtime_terminal_kill_confirm")).performClick()
        compose.runOnIdle { assertEquals(1, killCount) }
    }
}
