package xendroid.compose.gamepad

import android.content.Context
import xendroid.compose.settings.ConfigStore

/** Which profile each controller plays as, keyed by its stable descriptor.
 *
 *  SharedPreferences for the same reason as [PadSlots]: pads attach from
 *  onCreate, before a coroutine could have loaded anything. */
object PadProfiles {

    private const val PREFS = "pad_profiles"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether to ask who is playing when more than one controller is connected. */
    fun askOnStart(context: Context): Boolean {
        val handle = ConfigStore(context).openLiveSnapshot()
        return try {
            handle.getBool("HID", "ask_players_on_start", true)
        } finally {
            handle.closeString()
        }
    }

    fun choiceFor(context: Context, descriptor: String): String? =
        prefs(context).getString(key(descriptor), null)?.takeIf { it.isNotBlank() }

    fun setChoice(context: Context, descriptor: String, xuid: String) {
        prefs(context).edit().putString(key(descriptor), xuid.uppercase()).apply()
    }

    fun clearChoice(context: Context, descriptor: String) {
        prefs(context).edit().remove(key(descriptor)).apply()
    }

    /** The connected controller playing as [xuid], if any. */
    fun padFor(context: Context, xuid: String): GamepadDevice? =
        connectedGamepads().firstOrNull { choiceFor(context, it.descriptor).equals(xuid, true) }

    private fun key(descriptor: String) = "choice_$descriptor"
}
