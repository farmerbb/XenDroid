package xendroid.compose.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists the 16-button hardware-key mapping + the vibrate toggle in the shared
 *  "xendroid_prefs" DataStore. Keyed by STABLE button index (keymap_0..keymap_15),
 *  NOT the legacy resId-as-string keys. Value = bound Android keycode (0 = cleared).
 *
 *  A controller may override the shared mapping: its rows live under
 *  keymap_<device>_<index>, where <device> is the sanitized InputDevice
 *  descriptor (stable across reconnects). Absent rows inherit the shared
 *  mapping, so existing users keep theirs and a new pad starts from it. */
class KeymapStore(private val appContext: Context) {

    private fun key(index: Int) = intPreferencesKey("keymap_$index")
    private fun key(device: String, index: Int) =
        intPreferencesKey("keymap_${sanitize(device)}_$index")
    private fun overrideFlag(device: String) =
        booleanPreferencesKey("keymap_${sanitize(device)}_own")
    private val vibrateKey = booleanPreferencesKey("enable_vibrator")

    companion object {
        /** Descriptors are opaque and may contain characters we would rather not
         *  concatenate into a key; the mapping only has to be stable and unique. */
        fun sanitize(device: String): String =
            device.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }

    /** A blank descriptor means the shared mapping itself. */
    fun bindingsFor(device: String): Flow<Map<Int, Int>> =
        appContext.dataStore.data.map { prefs ->
            readBindings(prefs, device.ifBlank { null })
        }

    fun hasOwnMapping(device: String): Flow<Boolean> =
        appContext.dataStore.data.map { it[overrideFlag(device)] ?: false }

    /** Android keycode -> game KEY_CODE, ready for the host lookup. Unbound (0) dropped. */
    val androidToGameKey: Flow<Map<Int, Int>> =
        appContext.dataStore.data.map { prefs -> lookupFrom(readBindings(prefs, null)) }

    /** The host resolves a key event through its device's entry, else the shared one. */
    val androidToGameKeyByDevice: Flow<Map<String, Map<Int, Int>>> =
        appContext.dataStore.data.map { prefs ->
            val out = HashMap<String, Map<Int, Int>>()
            for ((k, v) in prefs.asMap()) {
                val name = k.name
                if (!name.startsWith("keymap_") || !name.endsWith("_own")) continue
                if (v != true) continue
                val device = name.removePrefix("keymap_").removeSuffix("_own")
                out[device] = lookupFrom(readBindingsSanitized(prefs, device))
            }
            out
        }

    private fun lookupFrom(bindings: Map<Int, Int>): Map<Int, Int> {
        val out = HashMap<Int, Int>(GameButtons.ALL.size)
        for (b in GameButtons.ALL) {
            val code = bindings[b.index] ?: b.defaultAndroidKey
            if (code != 0) out[code] = b.keyCode
        }
        return out
    }

    val vibrateEnabled: Flow<Boolean> =
        appContext.dataStore.data.map { it[vibrateKey] ?: false }

    /** [device] null edits the shared mapping; a controller's own is seeded from
     *  the shared rows on first touch, so the screen never jumps. */
    suspend fun setBinding(device: String?, index: Int, androidKeyCode: Int) {
        appContext.dataStore.edit { prefs ->
            if (device == null) {
                prefs[key(index)] = androidKeyCode
                return@edit
            }
            if (prefs[overrideFlag(device)] != true) {
                for (b in GameButtons.ALL) {
                    prefs[key(device, b.index)] = prefs[key(b.index)] ?: b.defaultAndroidKey
                }
                prefs[overrideFlag(device)] = true
            }
            prefs[key(device, index)] = androidKeyCode
        }
    }

    /** Clear a single binding (stores 0 = unbound, matching legacy "Clear"). */
    suspend fun clearBinding(device: String?, index: Int) = setBinding(device, index, 0)

    /** Reset all 16 rows to KeyMapConfig.DEFAULT_KEYMAPPERS. */
    suspend fun resetToDefaults(device: String?) {
        appContext.dataStore.edit { prefs ->
            for (b in GameButtons.ALL) {
                if (device == null) {
                    prefs[key(b.index)] = b.defaultAndroidKey
                } else {
                    prefs[key(device, b.index)] = b.defaultAndroidKey
                }
            }
            if (device != null) prefs[overrideFlag(device)] = true
        }
    }

    suspend fun useSharedMapping(device: String) {
        appContext.dataStore.edit { prefs ->
            prefs.remove(overrideFlag(device))
            for (b in GameButtons.ALL) prefs.remove(key(device, b.index))
        }
    }

    suspend fun setVibrate(enabled: Boolean) {
        appContext.dataStore.edit { it[vibrateKey] = enabled }
    }

    private fun readBindings(prefs: Preferences, device: String?): Map<Int, Int> =
        readBindingsSanitized(prefs, device?.let { sanitize(it) })

    private fun readBindingsSanitized(prefs: Preferences, device: String?): Map<Int, Int> {
        val out = HashMap<Int, Int>(GameButtons.ALL.size)
        for (b in GameButtons.ALL) {
            val shared = prefs[key(b.index)] ?: b.defaultAndroidKey
            out[b.index] = if (device == null) shared
            else prefs[intPreferencesKey("keymap_${device}_${b.index}")] ?: shared
        }
        return out
    }
}
