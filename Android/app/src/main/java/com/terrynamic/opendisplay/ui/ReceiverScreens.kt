package com.terrynamic.opendisplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.terrynamic.opendisplay.AppSettings
import com.terrynamic.opendisplay.ReceiverController
import com.terrynamic.opendisplay.ReceiverPhase
import com.terrynamic.opendisplay.UiState
import com.terrynamic.opendisplay.VirtualDesktopSize
import com.terrynamic.opendisplay.video.DecodeCeiling

@Composable
fun ReceiverRoot(
    state: UiState,
    controller: ReceiverController,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    settingsOpen: Boolean,
) {
    ReceiverTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            when {
                state.phase == ReceiverPhase.BLOCKED && state.updateMessage != null -> {
                    BlockedScreen(message = state.updateMessage)
                }
                state.phase == ReceiverPhase.STREAMING -> {
                    StreamingScreen(state = state, controller = controller, onOpenSettings = onOpenSettings)
                }
                else -> {
                    IdleScreen(state = state, onOpenSettings = onOpenSettings)
                }
            }
            if (settingsOpen) {
                SettingsSheet(
                    settings = state.settings,
                    onDismiss = onCloseSettings,
                    onSave = {
                        controller.updateSettings(it)
                        onCloseSettings()
                    },
                )
            }
        }
    }
}

@Composable
private fun IdleScreen(state: UiState, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("OpenDisplay", style = MaterialTheme.typography.headlineMedium)
        Text(state.status, style = MaterialTheme.typography.bodyLarge)
        Meta("Service", state.registeredName)
        Meta("Install", state.installIdShort)
        Meta("Port", state.port.toString())
        Meta("USB helper", if (state.usbHelperActive) "active" else "inactive")
        if (state.senderTooOld) {
            Text("Update the Mac app to continue.", color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "On the Mac, pick this device in OpenDisplay. Wi-Fi uses Bonjour; USB uses the Mac helper (adb forward + Bonjour proxy).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenSettings) { Text("Settings") }
    }
}

@Composable
private fun StreamingScreen(
    state: UiState,
    controller: ReceiverController,
    onOpenSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                VideoSurface(context).also { it.controller = controller }
            },
            update = { view ->
                view.controller = controller
                view.videoWidth = state.videoWidth
                view.videoHeight = state.videoHeight
                view.updateCursor(state.cursor)
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (state.showStats && state.statsText != null) {
            Text(
                text = state.statsText,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd),
        ) { Text("Settings") }
    }
}

@Composable
private fun BlockedScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Update required", style = MaterialTheme.typography.headlineMedium)
        Text(message, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SettingsSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
) {
    var name by remember { mutableStateOf(settings.serviceName) }
    var ceiling by remember { mutableStateOf(settings.decodeCeiling) }
    var desktop by remember { mutableStateOf(settings.virtualDesktop) }
    var stats by remember { mutableStateOf(settings.showStats) }
    var customW by remember {
        mutableStateOf((settings.decodeCeiling as? DecodeCeiling.Custom)?.width?.toString() ?: "2560")
    }
    var customH by remember {
        mutableStateOf((settings.decodeCeiling as? DecodeCeiling.Custom)?.height?.toString() ?: "1440")
    }

    LaunchedEffect(settings) {
        name = settings.serviceName
        ceiling = settings.decodeCeiling
        desktop = settings.virtualDesktop
        stats = settings.showStats
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .padding(28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Service name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("Decode ceiling", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CeilingChip("Auto", ceiling is DecodeCeiling.Auto) { ceiling = DecodeCeiling.Auto }
            CeilingChip("Panel", ceiling is DecodeCeiling.Panel) { ceiling = DecodeCeiling.Panel }
            CeilingChip("1080p", ceiling is DecodeCeiling.Fhd) { ceiling = DecodeCeiling.Fhd }
            CeilingChip("Custom", ceiling is DecodeCeiling.Custom) {
                ceiling = DecodeCeiling.Custom(customW.toIntOrNull() ?: 2560, customH.toIntOrNull() ?: 1440)
            }
            CeilingChip("Off", ceiling is DecodeCeiling.Off) { ceiling = DecodeCeiling.Off }
        }
        if (ceiling is DecodeCeiling.Custom) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = customW,
                    onValueChange = { customW = it },
                    label = { Text("Width") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = customH,
                    onValueChange = { customH = it },
                    label = { Text("Height") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }
        }
        Text("Virtual desktop", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopChip("100%", VirtualDesktopSize.P100, desktop) { desktop = it }
            DesktopChip("125%", VirtualDesktopSize.P125, desktop) { desktop = it }
            DesktopChip("150%", VirtualDesktopSize.P150, desktop) { desktop = it }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Stats overlay")
            Switch(checked = stats, onCheckedChange = { stats = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val resolvedCeiling = if (ceiling is DecodeCeiling.Custom) {
                    DecodeCeiling.Custom(
                        (customW.toIntOrNull() ?: 2560).coerceAtLeast(16),
                        (customH.toIntOrNull() ?: 1440).coerceAtLeast(16),
                    )
                } else {
                    ceiling
                }
                onSave(
                    settings.copy(
                        serviceName = name.trim().ifEmpty { settings.serviceName },
                        decodeCeiling = resolvedCeiling,
                        virtualDesktop = desktop,
                        showStats = stats,
                    ),
                )
            }) { Text("Save") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
}

@Composable
private fun CeilingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun DesktopChip(
    label: String,
    value: VirtualDesktopSize,
    current: VirtualDesktopSize,
    onClick: (VirtualDesktopSize) -> Unit,
) {
    FilterChip(selected = current == value, onClick = { onClick(value) }, label = { Text(label) })
}

@Composable
private fun Meta(label: String, value: String) {
    Text("$label  $value", style = MaterialTheme.typography.bodyLarge)
}
