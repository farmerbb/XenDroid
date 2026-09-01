package xendroid.compose.ui.keymap

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xendroid.compose.gamepad.PadSlots

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeymapScreen(vm: KeymapViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var capturing by remember { mutableStateOf<KeymapRow?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshDevices()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Key Mapping") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.onResetDefaults() }) { Text("Reset") }
                },
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                val label = state.selected?.name ?: "All controllers"
                ListItem(
                    headlineContent = { Text(label) },
                    overlineContent = { Text("Mapping for") },
                    supportingContent = {
                        Text(
                            when {
                                state.selected == null ->
                                    "Used by every controller without its own mapping"
                                state.selectedHasOwnMapping -> "This controller has its own mapping"
                                else -> "Inheriting the shared mapping; editing a button makes it its own"
                            }
                        )
                    },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { pickerOpen = true }) { Text("Change") }
                            DropdownMenu(
                                expanded = pickerOpen,
                                onDismissRequest = { pickerOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All controllers") },
                                    onClick = {
                                        pickerOpen = false
                                        vm.onSelectDevice(null)
                                    },
                                )
                                state.devices.forEach { device ->
                                    DropdownMenuItem(
                                        text = { Text(device.name) },
                                        onClick = {
                                            pickerOpen = false
                                            vm.onSelectDevice(device.descriptor)
                                        },
                                    )
                                }
                                if (state.devices.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("No controllers connected") },
                                        enabled = false,
                                        onClick = {},
                                    )
                                }
                            }
                        }
                    },
                )
                if (state.selected != null) {
                    var playerMenu by remember(state.selected) { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text("Player") },
                        supportingContent = {
                            Text(
                                if (state.selectedPlayerSlot == PadSlots.AUTO) {
                                    "Assigned automatically in connection order"
                                } else {
                                    "Applied the next time a game starts"
                                }
                            )
                        },
                        trailingContent = {
                            Box {
                                TextButton(onClick = { playerMenu = true }) {
                                    Text(
                                        if (state.selectedPlayerSlot == PadSlots.AUTO) "Auto"
                                        else "Player ${state.selectedPlayerSlot + 1}"
                                    )
                                }
                                DropdownMenu(
                                    expanded = playerMenu,
                                    onDismissRequest = { playerMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Auto") },
                                        onClick = {
                                            playerMenu = false
                                            vm.onSelectPlayerSlot(PadSlots.AUTO)
                                        },
                                    )
                                    for (slot in 0 until 4) {
                                        DropdownMenuItem(
                                            text = { Text("Player ${slot + 1}") },
                                            onClick = {
                                                playerMenu = false
                                                vm.onSelectPlayerSlot(slot)
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                if (state.selectedHasOwnMapping) {
                    TextButton(
                        onClick = { vm.onUseSharedMapping() },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("Use the shared mapping instead") }
                }
                HorizontalDivider()
            }
            items(state.rows, key = { it.button.index }) { row ->
                ListItem(
                    headlineContent = { Text(row.button.label) },
                    supportingContent = { Text(keyLabel(row.boundKey)) },
                    trailingContent = {
                        TextButton(onClick = { vm.onClear(row.button.index) }) { Text("Clear") }
                    },
                    modifier = Modifier.clickable { capturing = row },
                )
                HorizontalDivider()
            }
        }
    }

    capturing?.let { row ->
        KeyCaptureDialog(
            label = row.button.label,
            onKey = { code -> vm.onKeyCaptured(row.button.index, code); capturing = null },
            onDismiss = { capturing = null },
        )
    }
}

@Composable
private fun KeyCaptureDialog(label: String, onKey: (Int) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(enabled = true, onBack = onDismiss)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Press a key for $label") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .focusable()
                    .onKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown) {
                            onKey(ev.nativeKeyEvent.keyCode); true
                        } else false
                    }
            ) { Text("Waiting for a controller/keyboard button…") }
        },
    )
}

/** Human-readable name for a bound Android keycode (0 = unbound). */
private fun keyLabel(code: Int): String =
    if (code == 0) "(unbound)"
    else AndroidKeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
