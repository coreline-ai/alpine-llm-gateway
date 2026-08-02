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
import dev.alpine.runtime.host.RuntimeHostState

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
    onSendTerminal: (String) -> Unit,
    onInterruptTerminal: () -> Unit,
    onCloseTerminal: () -> Unit,
    onInstallPackages: (List<String>) -> Unit,
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
            onSend = onSendTerminal,
            onInterrupt = onInterruptTerminal,
            onClose = onCloseTerminal,
        )
        RuntimePackagePanel(
            state = state,
            allowlistedPackages = allowlistedPackages,
            onApprovedInstall = onInstallPackages,
        )
    }
}
