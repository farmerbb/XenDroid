package xendroid.compose.gamepad

import android.content.Context
import android.hardware.input.InputManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** The connected controllers, kept current as they come and go. */
@Composable
fun rememberConnectedPads(): List<GamepadDevice> {
    val context = LocalContext.current
    var pads by remember { mutableStateOf(connectedGamepads()) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val listener = object : InputManager.InputDeviceListener {
            // Rebuilt rather than edited by id: a pad can be reported before its
            // sources are readable.
            override fun onInputDeviceAdded(deviceId: Int) { pads = connectedGamepads() }
            override fun onInputDeviceRemoved(deviceId: Int) { pads = connectedGamepads() }
            override fun onInputDeviceChanged(deviceId: Int) { pads = connectedGamepads() }
        }
        manager?.registerInputDeviceListener(listener, null)
        onDispose { manager?.unregisterInputDeviceListener(listener) }
    }
    return pads
}
