package dev.alpine.runtime.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.alpine.runtime.api.RuntimePackageCatalog
import dev.alpine.runtime.host.RuntimeHostState
import dev.alpine.runtime.api.RuntimePackageAction
import dev.alpine.workspace.api.WorkspaceHostState
import dev.alpine.workspace.api.WorkspacePath

@Composable
fun RuntimeWorkspaceScreen(
    state: RuntimeHostState,
    allowlistedPackages: Set<String>,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHealth: () -> Unit,
    onRepair: () -> Unit,
    onReset: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenAdditionalTerminal: () -> Unit,
    onSelectTerminal: (String) -> Unit,
    onRenameTerminal: (String, String) -> Unit,
    onSendTerminal: (String) -> Unit,
    onSendTerminalRaw: (String) -> Unit,
    onInterruptTerminal: () -> Unit,
    onCloseTerminal: () -> Unit,
    onTerminateTerminal: () -> Unit = onCloseTerminal,
    onKillTerminal: () -> Unit = {},
    onInstallPackages: (List<String>) -> Unit,
    packageCatalog: RuntimePackageCatalog = RuntimePackageCatalog(emptyList()),
    removablePackages: Set<String> = emptySet(),
    onMutatePackages: (RuntimePackageAction, List<String>) -> Unit = { _, _ -> },
    onRunToolSmoke: (RuntimeToolProfile) -> Unit = {},
    workspaceState: WorkspaceHostState,
    onWorkspaceRefresh: () -> Unit,
    onWorkspaceNavigate: (WorkspacePath) -> Unit,
    onWorkspaceOpen: (WorkspacePath) -> Unit,
    onWorkspaceSave: (String) -> Unit,
    onWorkspaceCreateFile: (String) -> Unit,
    onWorkspaceCreateDirectory: (String) -> Unit,
    onWorkspaceRenameSelected: (String) -> Unit,
    onWorkspaceDeleteSelected: () -> Unit,
    onWorkspaceSearch: (String) -> Unit,
    onWorkspaceImport: () -> Unit,
    onWorkspaceExport: (WorkspacePath) -> Unit,
    onWorkspaceShare: (WorkspacePath) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RuntimeDashboard(
            state = state,
            onInstall = onInstall,
            onStart = onStart,
            onStop = onStop,
            onHealth = onHealth,
            onRepair = onRepair,
            onReset = onReset,
        )
        RuntimeTerminalPanel(
            state = state,
            onOpen = onOpenTerminal,
            onOpenAdditional = onOpenAdditionalTerminal,
            onSelectTerminal = onSelectTerminal,
            onRenameTerminal = onRenameTerminal,
            onSend = onSendTerminal,
            onSendRaw = onSendTerminalRaw,
            onInterrupt = onInterruptTerminal,
            onClose = onCloseTerminal,
            onTerminate = onTerminateTerminal,
            onKill = onKillTerminal,
        )
        RuntimePackagePanel(
            state = state,
            allowlistedPackages = allowlistedPackages,
            onApprovedInstall = onInstallPackages,
            packageCatalog = packageCatalog,
            removablePackages = removablePackages,
            onApprovedMutation = onMutatePackages,
            onRunToolSmoke = onRunToolSmoke,
        )
        RuntimeWorkspacePanel(
            state = workspaceState,
            onRefresh = onWorkspaceRefresh,
            onNavigate = onWorkspaceNavigate,
            onOpen = onWorkspaceOpen,
            onSave = onWorkspaceSave,
            onCreateFile = onWorkspaceCreateFile,
            onCreateDirectory = onWorkspaceCreateDirectory,
            onRenameSelected = onWorkspaceRenameSelected,
            onDeleteSelected = onWorkspaceDeleteSelected,
            onSearch = onWorkspaceSearch,
            onRequestImport = onWorkspaceImport,
            onRequestExport = onWorkspaceExport,
            onRequestShare = onWorkspaceShare,
        )
    }
}
