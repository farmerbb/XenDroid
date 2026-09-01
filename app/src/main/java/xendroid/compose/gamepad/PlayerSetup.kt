package xendroid.compose.gamepad

import android.content.Context
import xendroid.compose.core.ContentPaths
import xendroid.compose.core.EmulatorRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import xendroid.compose.settings.ConfigStore
import xendroid.compose.settings.Setting
import xendroid.compose.settings.SettingsSchema

/** A player slot's controller and the profile it plays as. */
data class PlayerAssignment(val descriptor: String?, val xuid: String)

/** Who plays as whom, settled before a title launches. Slots are written as the
 *  logged-in profile cvars, so the kernel signs them in as the title boots. */
object PlayerSetup {

    const val MAX_PLAYERS = 4

    /** Raised when the slots are rewritten: the start-up dialog can land while the
     *  profile list is already open. */
    val revision = MutableStateFlow(0)
    private const val PREFS = "pad_profiles"
    private const val MAIN_PROFILE = "main_profile_xuid"
    private const val MAIN_PAD = "main_pad_descriptor"

    fun slotKey(slot: Int) = "logged_profile_slot_${slot}_xuid"

    /** Pads in the order they would attach: pinned pads take their slot, the rest
     *  fill what is left in enumeration order. */
    fun orderedPads(context: Context): List<GamepadDevice> {
        val pads = connectedGamepads().take(MAX_PLAYERS)
        val slots = arrayOfNulls<GamepadDevice>(MAX_PLAYERS)
        val loose = ArrayList<GamepadDevice>()
        for (pad in pads) {
            val pinned = PadSlots.preferredSlot(context, pad.descriptor)
            if (pinned in 0 until MAX_PLAYERS && slots[pinned] == null) {
                slots[pinned] = pad
            } else {
                loose.add(pad)
            }
        }
        val out = ArrayList<GamepadDevice>(pads.size)
        var next = 0
        for (slot in 0 until MAX_PLAYERS) {
            val pad = slots[slot] ?: loose.getOrNull(next)?.also { next++ }
            if (pad != null) out.add(pad)
        }
        return out
    }

    /** The main profile goes to the pad that owns it, or to the first pad when
     *  that one is absent - taking it over, which leaves the stand-in's own profile
     *  signed out. Every other pad keeps the profile it was last set to. */
    fun defaults(context: Context): List<PlayerAssignment> {
        val pads = orderedPads(context)
        if (pads.isEmpty()) return emptyList()
        val main = mainProfile(context)
        val holder = pads.firstOrNull { it.descriptor == mainPad(context) } ?: pads.first()
        val known = profiles().map { it.first.uppercase() }
        val rows = pads.map { pad ->
            // A pairing whose profile was deleted counts as none, so the pad picks
            // up a live one below.
            val xuid = if (main.isNotEmpty() && pad === holder) main
            else PadProfiles.choiceFor(context, pad.descriptor)?.uppercase()
                ?.takeIf { it in known }.orEmpty()
            PlayerAssignment(pad.descriptor, xuid)
        }.toMutableList()
        // An unpaired pad takes a profile no other player is on, so the dialog
        // opens ready to confirm.
        val used = rows.map { it.xuid.uppercase() }.filter { it.isNotEmpty() }.toMutableSet()
        val spare = known.filter { it !in used }.toMutableList()
        rows.forEachIndexed { i, row ->
            if (row.xuid.isEmpty() && spare.isNotEmpty()) {
                val xuid = spare.removeAt(0)
                used.add(xuid)
                rows[i] = row.copy(xuid = xuid)
            }
        }
        return rows
    }

    /** Fewer than two pads: the main profile plays and nothing else is signed in. */
    fun applySolo(context: Context) {
        val main = mainProfile(context)
        if (main.isEmpty()) return
        val handle = ConfigStore(context).openLive()
        try {
            handle.putString("Profiles", slotKey(0), main)
            for (slot in 1 until MAX_PLAYERS) handle.putString("Profiles", slotKey(slot), "")
        } finally {
            handle.closeFile()
        }
        revision.value += 1
    }

    /** Remembered on the first setup: writing the slots later would otherwise make
     *  a generated profile look like the account the user plays as. */
    fun mainProfile(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(MAIN_PROFILE, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val current = readSlot(context, 0).ifBlank { firstProfileXuid() }
        if (current.isNotBlank()) {
            prefs.edit().putString(MAIN_PROFILE, current.uppercase()).apply()
        }
        return current.uppercase()
    }

    fun mainPad(context: Context): String? =
        prefs(context).getString(MAIN_PAD, null)?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun apply(context: Context, rows: List<PlayerAssignment>) {
        val main = mainProfile(context)
        val remembered = mainPad(context)
        val connected = orderedPads(context).map { it.descriptor }
        rows.forEachIndexed { slot, row ->
            val descriptor = row.descriptor ?: return@forEachIndexed
            PadSlots.setPreferredSlot(context, descriptor, slot)
            // Tracked on its own, so a pad standing in for an absent one does not
            // inherit the account for good.
            if (row.xuid.equals(main, true)) {
                if (remembered == null || remembered in connected) {
                    prefs(context).edit().putString(MAIN_PAD, descriptor).apply()
                }
            } else {
                PadProfiles.setChoice(context, descriptor, row.xuid)
            }
        }
        val handle = ConfigStore(context).openLive()
        try {
            for (slot in 0 until MAX_PLAYERS) {
                handle.putString("Profiles", slotKey(slot),
                    rows.getOrNull(slot)?.xuid?.uppercase() ?: "")
            }
        } finally {
            handle.closeFile()
        }
        revision.value += 1
    }

    /** Covers the shortfall only: with more pads than profiles some players would
     *  have nobody to sign in as. Nothing is created for a particular pad. */
    fun ensureEnoughProfiles(context: Context): Int {
        val emu = EmulatorRuntime.emulator ?: return 0
        val wanted = orderedPads(context).size
        if (wanted < 2) return 0
        val root = ContentPaths.contentRoot().absolutePath
        val existing = profiles()
        // Count profiles, not names: two may share a gamertag, and a name set would
        // collapse them and under-count.
        var have = existing.size
        val taken = existing.map { it.second.lowercase() }.toMutableSet()
        var created = 0
        // Player 1 is the account the user already has.
        var next = 2
        while (have < wanted) {
            while ("player $next" in taken) next++
            val tag = "Player $next"
            runCatching {
                emu.create_profile(root, tag, defaultLanguage(), defaultCountry())
            }.getOrNull() ?: break
            taken.add(tag.lowercase())
            have++
            created++
        }
        if (created > 0) revision.value += 1
        return created
    }

    private fun defaultLanguage() =
        (SettingsSchema.byKey["Console|user_language"] as? Setting.ListChoice)
            ?.default?.toIntOrNull() ?: 1

    private fun defaultCountry() =
        (SettingsSchema.byKey["Console|user_country"] as? Setting.ListChoice)
            ?.default?.toIntOrNull() ?: 103

    fun profiles(): List<Pair<String, String>> {
        val emu = EmulatorRuntime.emulator ?: return emptyList()
        val root = ContentPaths.contentRoot().absolutePath
        return runCatching { emu.list_profiles(root) }.getOrNull()
            ?.map { it.xuid to (it.gamertag ?: it.xuid) } ?: emptyList()
    }

    private fun firstProfileXuid() = profiles().firstOrNull()?.first ?: ""

    /** The profile in each player slot, blank where nobody is signed in. */
    fun slotXuids(context: Context): List<String> {
        val handle = ConfigStore(context).openLiveSnapshot()
        return try {
            (0 until MAX_PLAYERS).map { handle.getString("Profiles", slotKey(it)) ?: "" }
        } finally {
            handle.closeString()
        }
    }

    private fun readSlot(context: Context, slot: Int): String {
        val handle = ConfigStore(context).openLiveSnapshot()
        return try {
            handle.getString("Profiles", slotKey(slot)) ?: ""
        } finally {
            handle.closeString()
        }
    }
}
