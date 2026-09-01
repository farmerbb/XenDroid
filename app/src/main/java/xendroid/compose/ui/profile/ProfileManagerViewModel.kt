package xendroid.compose.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xendroid.compose.core.ContentPaths
import xendroid.compose.gamepad.GamepadDevice
import xendroid.compose.gamepad.PadProfiles
import xendroid.compose.gamepad.PlayerSetup
import xendroid.compose.gamepad.connectedGamepads
import xendroid.compose.core.EmulatorRuntime
import xendroid.compose.core.Gamertag
import xendroid.compose.core.ProfilePaths
import xendroid.compose.settings.ConfigStore
import java.io.File

class ProfileManagerViewModel(
    private val appContext: Context,
    private val configStore: ConfigStore,
) : ViewModel() {

    data class ProfileEntry(
        val xuid: String,
        val gamertag: String,
        val language: Int,
        val country: Int,
        val hasAvatar: Boolean,
        /** Slot 0 is player 1. */
        val slot: Int?,
        /** Connected controller playing as this profile, null for none. */
        val controller: GamepadDevice? = null,
        /** True when [controller] was attached by hand rather than inferred from
         *  the player slot. Only an attached one can be detached. */
        val controllerAttached: Boolean = false,
    ) {
        val isActive: Boolean get() = slot != null
    }

    companion object {
        /** Xenia signs in at most XUserMaxUserCount profiles, one per guest slot. */
        const val SLOT_COUNT = 4
    }

    sealed interface ListState {
        data object Loading : ListState
        data class Loaded(val profiles: List<ProfileEntry>) : ListState
        data class Error(val message: String) : ListState
    }

    private val _listState = MutableStateFlow<ListState>(ListState.Loading)
    val listState: StateFlow<ListState> = _listState.asStateFlow()

    sealed interface OpState {
        data object Idle : OpState
        data class Busy(val message: String) : OpState
        data class Done(val message: String) : OpState
        data class Failed(val message: String) : OpState
    }

    private val _opState = MutableStateFlow<OpState>(OpState.Idle)
    val opState: StateFlow<OpState> = _opState.asStateFlow()

    init { refresh() }

    fun dismiss() { _opState.value = OpState.Idle }

    fun refresh() = viewModelScope.launch {
        _listState.value = ListState.Loading
        _listState.value = withContext(Dispatchers.IO) {
            EmulatorRuntime.ensureLoaded()
            val emu = EmulatorRuntime.emulator
                ?: return@withContext ListState.Error("Emulator not loaded.")
            val root = ContentPaths.contentRoot().absolutePath
            try {
                val slots = slotXuids()
                val padsBySlot = PlayerSetup.orderedPads(appContext)
                val profiles = emu.list_profiles(root)?.map { info ->
                    val slot = slots.indexOfFirst { it.equals(info.xuid, ignoreCase = true) }
                        .takeIf { it >= 0 }
                    ProfileEntry(
                        xuid = info.xuid,
                        gamertag = info.gamertag ?: "",
                        language = info.language,
                        country = info.country,
                        hasAvatar = info.hasAvatar,
                        slot = slot,
                        controller = controllerFor(info.xuid, slot, padsBySlot),
                        controllerAttached = PadProfiles.padFor(appContext, info.xuid) != null,
                    )
                }?.sortedBy { it.gamertag.lowercase() }
                    ?: return@withContext ListState.Error("Couldn't read profiles.")
                ListState.Loaded(profiles)
            } catch (t: RuntimeException) {
                ListState.Error(t.message ?: "Couldn't read profiles.")
            }
        }
    }

    fun create(gamertag: String, language: Int, country: Int, avatarUri: Uri?) = viewModelScope.launch {
        if (!Gamertag.isValid(gamertag)) {
            _opState.value = OpState.Failed("Enter a valid gamertag (1-15 characters).")
            return@launch
        }
        _opState.value = OpState.Busy("Creating profile…")
        val result = withContext(Dispatchers.IO) {
            EmulatorRuntime.ensureLoaded()
            val emu = EmulatorRuntime.emulator ?: return@withContext null
            val xuid = emu.create_profile(
                ContentPaths.contentRoot().absolutePath, gamertag, language, country)
            if (xuid != null && avatarUri != null) writeAvatar(xuid, avatarUri)
            xuid
        }
        if (result != null) {
            _opState.value = OpState.Done("Created “$gamertag”.")
            refresh()
        } else {
            _opState.value = OpState.Failed("Couldn't create the profile.")
        }
    }

    fun rename(xuid: String, gamertag: String, language: Int, country: Int, avatarUri: Uri?) =
        viewModelScope.launch {
            if (!Gamertag.isValid(gamertag)) {
                _opState.value = OpState.Failed("Enter a valid gamertag (1-15 characters).")
                return@launch
            }
            _opState.value = OpState.Busy("Saving…")
            val status = withContext(Dispatchers.IO) {
                EmulatorRuntime.ensureLoaded()
                val emu = EmulatorRuntime.emulator ?: return@withContext -1
                val st = emu.rename_profile(
                    ContentPaths.contentRoot().absolutePath, xuid, gamertag, language, country)
                if (st == 0 && avatarUri != null) writeAvatar(xuid, avatarUri)
                st
            }
            if (status == 0) {
                _opState.value = OpState.Done("Saved “$gamertag”.")
                refresh()
            } else {
                _opState.value = OpState.Failed(renameReasonFor(status))
            }
        }

    /** A blank xuid empties the slot. Takes effect at the next game start: xenia
     *  signs profiles in while the emulator boots. */
    fun assignSlot(slot: Int, xuid: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val slots = slotXuids().toMutableList()
            val id = xuid.uppercase()
            if (id.isNotEmpty()) {
                for (i in slots.indices) {
                    if (slots[i].equals(id, ignoreCase = true)) slots[i] = ""
                }
            }
            slots[slot] = id
            writeSlots(slots)
        }
        refresh()
    }

    fun delete(xuid: String) = viewModelScope.launch {
        _opState.value = OpState.Busy("Removing…")
        val ok = withContext(Dispatchers.IO) {
            val id = xuid.uppercase()
            if (!ProfilePaths.XUID_REGEX.matches(id)) return@withContext false
            val dir = File(ContentPaths.contentRoot(), id)
            val removed = dir.deleteRecursively()
            val slots = slotXuids().toMutableList()
            var changed = false
            for (i in slots.indices) {
                if (slots[i].equals(id, ignoreCase = true)) { slots[i] = ""; changed = true }
            }
            if (changed) writeSlots(slots)
            removed
        }
        if (ok) {
            _opState.value = OpState.Done("Profile removed.")
            refresh()
        } else {
            _opState.value = OpState.Failed("Couldn't remove the profile.")
        }
    }

    /** The pad playing as this profile: the one attached to it, else whichever
     *  pad drives the player slot it is signed in to - the signed-in profile has a
     *  controller whether or not anyone attached one by hand. A pad already
     *  attached to some other profile never stands in. */
    private fun controllerFor(
        xuid: String,
        slot: Int?,
        padsBySlot: List<GamepadDevice>,
    ): GamepadDevice? {
        PadProfiles.padFor(appContext, xuid)?.let { return it }
        val pad = slot?.let { padsBySlot.getOrNull(it) } ?: return null
        return pad.takeIf { PadProfiles.choiceFor(appContext, it.descriptor) == null }
    }

    /** One controller per profile and one profile per controller, so attaching
     *  takes the pad off whatever it was playing as. */
    fun attachController(xuid: String, descriptor: String?) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            connectedGamepads().forEach { pad ->
                if (PadProfiles.choiceFor(appContext, pad.descriptor).equals(xuid, true)) {
                    PadProfiles.clearChoice(appContext, pad.descriptor)
                }
            }
            if (descriptor != null) PadProfiles.setChoice(appContext, descriptor, xuid)
        }
        refresh()
    }

    fun connectedControllers(): List<GamepadDevice> = connectedGamepads()

    private fun slotKey(slot: Int) = "logged_profile_slot_${slot}_xuid"

    private fun slotXuids(): List<String> {
        val h = configStore.openLiveSnapshot()
        return try {
            (0 until SLOT_COUNT).map { h.getString("Profiles", slotKey(it)) ?: "" }
        } finally {
            h.closeString()
        }
    }

    /** Written as a whole set: a profile occupies at most one slot, so assigning
     *  it has to clear wherever it was. */
    private fun writeSlots(xuids: List<String>) {
        val h = configStore.openLive()
        try {
            for (slot in 0 until SLOT_COUNT) {
                h.putString("Profiles", slotKey(slot), xuids.getOrElse(slot) { "" })
            }
        } finally {
            h.closeFile()
        }
    }

    private fun writeAvatar(xuid: String, uri: Uri) {
        val src = appContext.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return
        val square = centerCropSquare(src)
        val dir = ProfilePaths.profileDir(xuid).also { it.mkdirs() }
        writePng(square, 64, File(dir, "tile_64.png"))
        writePng(square, 32, File(dir, "tile_32.png"))
        if (square != src) square.recycle()
        src.recycle()
    }

    private fun centerCropSquare(bmp: Bitmap): Bitmap {
        val side = minOf(bmp.width, bmp.height)
        if (side == bmp.width && side == bmp.height) return bmp
        val x = (bmp.width - side) / 2
        val y = (bmp.height - side) / 2
        return Bitmap.createBitmap(bmp, x, y, side, side)
    }

    private fun writePng(square: Bitmap, size: Int, dest: File) {
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        dest.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (scaled != square) scaled.recycle()
    }

    private fun renameReasonFor(status: Int): String = when (status) {
        -1 -> "Emulator not loaded."
        0xC000000D.toInt() -> "Enter a valid gamertag (1-15 characters)."
        0xC0000034.toInt() -> "That profile no longer exists."
        0xC0000022.toInt() -> "Couldn't write the profile files."
        else -> "Save failed (0x${status.toUInt().toString(16)})."
    }
}
