package xendroid.compose.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xendroid.compose.core.ContentPaths
import xendroid.compose.core.EmulatorRuntime
import xendroid.compose.core.ProfilePaths
import xendroid.compose.gamepad.PlayerSetup
import xendroid.compose.gamepad.rememberConnectedPads

/** Gamertag of the signed-in profile, or stacked avatars and a count when several
 *  are. Stands in for the library title and opens the profile manager. */
@Composable
fun SignedInBadge(onClick: () -> Unit) {
    val context = LocalContext.current
    // Signing in happens on other screens, so this re-reads on slot and pad changes.
    val revision by PlayerSetup.revision.collectAsStateWithLifecycle()
    val pads = rememberConnectedPads()
    var users by remember { mutableStateOf<List<SignedInUser>>(emptyList()) }
    LaunchedEffect(revision, pads.size) {
        users = withContext(Dispatchers.IO) { signedInUsers(context) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        if (users.isEmpty()) {
            Icon(Icons.Default.Person, contentDescription = null, Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text("Not signed in", maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@Row
        }
        // Overlapped so four players still read as one control.
        Box {
            users.forEachIndexed { i, user ->
                Avatar(user, Modifier.padding(start = (i * AVATAR_STEP.value).dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        if (users.size == 1) {
            Text(users[0].gamertag, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text("${users.size} players", maxLines = 1)
        }
    }
}

@Composable
private fun Avatar(user: SignedInUser, modifier: Modifier) {
    val context = LocalContext.current
    val ring = Modifier.size(AVATAR_SIZE).clip(CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
    if (user.hasAvatar) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(remember(user.xuid) { ProfilePaths.tile64Path(user.xuid) }).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.then(ring),
        )
    } else {
        Box(
            modifier.then(ring).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Person, contentDescription = null, Modifier.size(18.dp))
        }
    }
}

data class SignedInUser(val xuid: String, val gamertag: String, val hasAvatar: Boolean)

/** The profiles in the player slots, in slot order. */
private fun signedInUsers(context: android.content.Context): List<SignedInUser> {
    val emu = EmulatorRuntime.emulator ?: return emptyList()
    val root = ContentPaths.contentRoot().absolutePath
    val profiles = runCatching { emu.list_profiles(root) }.getOrNull() ?: return emptyList()
    return PlayerSetup.slotXuids(context)
        .filter { it.isNotBlank() }
        .distinctBy { it.uppercase() }
        .mapNotNull { xuid ->
            profiles.firstOrNull { it.xuid.equals(xuid, ignoreCase = true) }
        }
        .map { SignedInUser(it.xuid, it.gamertag ?: it.xuid, it.hasAvatar) }
}

private val AVATAR_SIZE = 28.dp
private val AVATAR_STEP = 11.dp
