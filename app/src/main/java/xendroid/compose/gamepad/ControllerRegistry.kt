package xendroid.compose.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import xendroid.compose.core.EmulatorSession

/**
 * Maps Android input devices onto emulator device slots, and keeps edge-detection
 * state per pad: one shared set let two pads cancel each other's presses.
 *
 * Main thread only: input events and InputManager callbacks both arrive there.
 */
class ControllerRegistry(private val session: EmulatorSession) {

    private var appContext: Context? = null

    class PadState(val deviceSlot: Int) {
        val axisPressed = BooleanArray(24)
        val axisValue = IntArray(24) { Int.MIN_VALUE }
        var hatLeft = false
        var hatUp = false
        var hatRight = false
        var hatDown = false
    }

    private val pads = HashMap<Int, PadState>()
    // Keyboards/remotes raise key events too; remembered so every keystroke does
    // not re-query InputDevice.
    private val nonPads = HashSet<Int>()
    private var touchSlot = -1
    private var listener: InputManager.InputDeviceListener? = null
    private var inputManager: InputManager? = null

    /** Attached up front so touch works with no pad. */
    fun touchSlot(): Int {
        if (touchSlot < 0) {
            touchSlot = session.attachInputDevice(TOUCH_ID, "On-screen controls", SUBTYPE_GAMEPAD, 0)
        }
        return touchSlot
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        touchSlot()
        val manager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        inputManager = manager
        refresh()
        val l = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { padFor(deviceId) }
            override fun onInputDeviceRemoved(deviceId: Int) { remove(deviceId) }
            override fun onInputDeviceChanged(deviceId: Int) {}
        }
        listener = l
        manager.registerInputDeviceListener(l, null)
    }

    fun stop() {
        listener?.let { inputManager?.unregisterInputDeviceListener(it) }
        listener = null
        inputManager = null
    }

    /** Null for the on-screen overlay, whose rumble belongs to the phone itself. */
    fun androidDeviceIdFor(deviceSlot: Int): Int? =
        pads.entries.firstOrNull { it.value.deviceSlot == deviceSlot }?.key

    /** Lowest driver slot held by a physical pad, -1 with none attached. */
    fun firstPadSlot(): Int = pads.values.minOfOrNull { it.deviceSlot } ?: -1

    /** Attach is a no-op once a device is known, and retried while boot is pending. */
    fun refresh() {
        InputDevice.getDeviceIds().forEach { padFor(it) }
    }

    fun padFor(deviceId: Int): PadState? {
        pads[deviceId]?.let { return it }
        if (deviceId in nonPads) return null
        val device = InputDevice.getDevice(deviceId) ?: return null
        if (device.isVirtual || !isGamepad(device)) {
            nonPads.add(deviceId)
            return null
        }
        val stableId = device.descriptor ?: "android-pad-$deviceId"
        val preferred = appContext?.let { PadSlots.preferredSlot(it, stableId) } ?: PadSlots.AUTO
        val slot = session.attachInputDevice(
            stableId, device.name ?: "Controller", SUBTYPE_GAMEPAD, preferred)
        if (slot < 0) return null
        logAxes(device, slot)
        val state = PadState(slot)
        pads[deviceId] = state
        // Binding is explicit, not left to auto-placement: reconciliation only
        // honors a preferred slot while that slot is FREE, and the on-screen
        // overlay already holds player 1 - so an auto-placed pad lands one slot
        // late and pushes the next pad off the end.
        if (preferred != PadSlots.AUTO) {
            session.bindInputSlot(preferred, slot)
        } else if (pads.size == 1) {
                session.bindInputSlot(0, slot)
        }
        logBindings("attach ${device.name}")
        return state
    }

    private fun remove(deviceId: Int) {
        nonPads.remove(deviceId)
        val state = pads.remove(deviceId) ?: return
        session.detachInputDevice(state.deviceSlot)
        if (pads.isEmpty() && touchSlot >= 0) {
            session.bindInputSlot(0, touchSlot)
        }
        logBindings("detach id=$deviceId")
    }

    private fun logBindings(reason: String) {
        val rows = session.listInputDevices().joinToString(", ") { d ->
            "p${d.guest_slot + 1}<-drv${d.device_slot} '${d.display_name ?: "?"}'"
        }
        Log.i(TAG, "bindings ($reason): $rows")
    }

    /** Which axes a pad publishes decides whether its triggers arrive as
     *  LTRIGGER/BRAKE or as Z/RZ (which we treat as the right stick). */
    private fun logAxes(device: InputDevice, slot: Int) {
        val axes = device.motionRanges.joinToString(", ") { range ->
            "${MotionEvent.axisToString(range.axis)}[${range.min}..${range.max} flat=${range.flat}]"
        }
        Log.i(TAG, "pad slot=$slot '${device.name}' id=${device.id} descriptor=${device.descriptor} " +
            "sources=0x${Integer.toHexString(device.sources)} axes: $axes")
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private companion object {
        const val TAG = "XenDroidPads"
        const val TOUCH_ID = "android-touch"
        const val SUBTYPE_GAMEPAD = 0x01
    }
}
