package xendroid.compose.gamepad

import android.content.Context
import android.preference.PreferenceManager

/** The player slot a controller should take, keyed by its stable descriptor.
 *  Applied when the pad attaches rather than rebound afterwards, so it survives
 *  reconnects and boot order.
 *
 *  SharedPreferences, not the DataStore the key mappings use: ControllerRegistry
 *  attaches pads from onCreate, before any coroutine could have loaded them. */
object PadSlots {
    const val AUTO = -1

    private fun key(descriptor: String) = "pad_slot_$descriptor"

    fun preferredSlot(context: Context, descriptor: String): Int =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(key(descriptor), AUTO)

    fun setPreferredSlot(context: Context, descriptor: String, slot: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        @Suppress("DEPRECATION")
        val editor = prefs.edit()
        editor.apply {
            if (slot == AUTO) {
                remove(key(descriptor))
            } else {
                // A slot holds one pad: drop it from whoever had it.
                for ((k, v) in prefs.all) {
                    if (k.startsWith("pad_slot_") && v == slot) remove(k)
                }
                putInt(key(descriptor), slot)
            }
        }
        editor.apply()
    }
}
