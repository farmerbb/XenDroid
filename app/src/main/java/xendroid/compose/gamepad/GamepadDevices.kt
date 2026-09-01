package xendroid.compose.gamepad

import android.view.InputDevice

/** [descriptor] is stable across reconnects, which is what mappings are keyed on. */
data class GamepadDevice(val descriptor: String, val name: String)

/** Deduplicated by descriptor: Android reports one physical pad as several
 *  device ids (gamepad and joystick sources). */
fun connectedGamepads(): List<GamepadDevice> {
    val out = LinkedHashMap<String, GamepadDevice>()
    for (id in InputDevice.getDeviceIds()) {
        val device = InputDevice.getDevice(id) ?: continue
        if (device.isVirtual || !isGamepadSource(device.sources)) continue
        val descriptor = device.descriptor ?: continue
        out.getOrPut(descriptor) {
            GamepadDevice(descriptor, device.name ?: "Controller")
        }
    }
    return out.values.toList()
}

fun isGamepadSource(sources: Int): Boolean =
    sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
