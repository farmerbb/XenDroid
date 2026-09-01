package xendroid.compose.ui.keymap

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import xendroid.compose.data.GameButton
import xendroid.compose.data.GameButtons
import xendroid.compose.data.KeymapStore
import xendroid.compose.gamepad.GamepadDevice
import xendroid.compose.gamepad.PadSlots
import xendroid.compose.gamepad.connectedGamepads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class KeymapRow(val button: GameButton, val boundKey: Int)   // boundKey 0 = cleared

data class KeymapUiState(
    val rows: List<KeymapRow> = emptyList(),
    val vibrate: Boolean = false,
    val devices: List<GamepadDevice> = emptyList(),
    val selected: GamepadDevice? = null,
    val selectedHasOwnMapping: Boolean = false,
    val selectedPlayerSlot: Int = PadSlots.AUTO,
)

@OptIn(ExperimentalCoroutinesApi::class)
class KeymapViewModel(
    private val appContext: Context,
    private val store: KeymapStore,
) : ViewModel() {

    private val devices = MutableStateFlow(connectedGamepads())
    private val selected = MutableStateFlow<String?>(null)
    // Bumped on write so the state flow re-reads the (synchronous) pad slots.
    private val padSlotEpoch = MutableStateFlow(0)

    val state: StateFlow<KeymapUiState> =
        combine(devices, selected, padSlotEpoch) { devices, selected, _ -> devices to selected }
            .flatMapLatest { (devices, selectedDescriptor) ->
                val device = devices.firstOrNull { it.descriptor == selectedDescriptor }
                // A selected pad that has since disconnected falls back to shared.
                val target = device?.descriptor
                combine(
                    store.bindingsFor(target ?: ""),
                    store.vibrateEnabled,
                    if (target != null) store.hasOwnMapping(target) else flowOf(false),
                ) { bindings, vibrate, own ->
                    KeymapUiState(
                        rows = GameButtons.ALL.map { b ->
                            KeymapRow(b, bindings[b.index] ?: b.defaultAndroidKey)
                        },
                        vibrate = vibrate,
                        devices = devices,
                        selected = device,
                        selectedHasOwnMapping = target != null && own,
                        selectedPlayerSlot = target?.let { PadSlots.preferredSlot(appContext, it) }
                            ?: PadSlots.AUTO,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeymapUiState())

    fun refreshDevices() {
        devices.value = connectedGamepads()
    }

    fun onSelectDevice(descriptor: String?) {
        selected.value = descriptor
    }

    fun onKeyCaptured(index: Int, androidKeyCode: Int) =
        viewModelScope.launch { store.setBinding(selected.value, index, androidKeyCode) }

    fun onClear(index: Int) = viewModelScope.launch { store.clearBinding(selected.value, index) }

    fun onResetDefaults() = viewModelScope.launch { store.resetToDefaults(selected.value) }

    /** Applied when the pad next attaches, so it takes effect on the next launch. */
    fun onSelectPlayerSlot(slot: Int) {
        val device = selected.value ?: return
        PadSlots.setPreferredSlot(appContext, device, slot)
        padSlotEpoch.value += 1
    }

    fun onUseSharedMapping() {
        val device = selected.value ?: return
        viewModelScope.launch { store.useSharedMapping(device) }
    }

    fun onVibrateChanged(enabled: Boolean) = viewModelScope.launch { store.setVibrate(enabled) }
}
