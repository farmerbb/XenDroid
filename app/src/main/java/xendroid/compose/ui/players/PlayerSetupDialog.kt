package xendroid.compose.ui.players

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xendroid.compose.gamepad.GamepadDevice
import xendroid.compose.gamepad.PlayerAssignment

/**
 * Who plays as whom, shown at start-up while more than one controller is
 * connected. It opens already filled in, so confirming is the whole interaction.
 */
@Composable
fun PlayerSetupDialog(
    pads: List<GamepadDevice>,
    profiles: List<Pair<String, String>>,
    initial: List<PlayerAssignment>,
    onConfirm: (List<PlayerAssignment>) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember { mutableStateListOf<PlayerAssignment>().apply { addAll(initial) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Players") },
        text = {
            Column {
                Text(
                    "${pads.size} controllers connected. Each player signs in with " +
                        "their own profile.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                // Four players do not fit the dialog on a handheld in landscape.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rows.forEachIndexed { slot, row ->
                        PlayerCard(
                            slot = slot,
                            row = row,
                            pads = pads,
                            profiles = profiles,
                            onPad = { descriptor ->
                                // A pad drives one player: hand it over rather than
                                // leaving it on two rows.
                                rows.forEachIndexed { i, other ->
                                    if (i != slot && other.descriptor == descriptor) {
                                        rows[i] = other.copy(descriptor = row.descriptor)
                                    }
                                }
                                rows[slot] = rows[slot].copy(descriptor = descriptor)
                            },
                            onProfile = { rows[slot] = rows[slot].copy(xuid = it) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(rows.toList()) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PlayerCard(
    slot: Int,
    row: PlayerAssignment,
    pads: List<GamepadDevice>,
    profiles: List<Pair<String, String>>,
    onPad: (String?) -> Unit,
    onProfile: (String) -> Unit,
) {
    // Fixed height, and single-line labels to keep it: pad names run long.
    Card(Modifier.fillMaxWidth().height(CARD_HEIGHT)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Player ${slot + 1}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val profileName = profiles.firstOrNull { it.first.equals(row.xuid, true) }?.second
                Picker(
                    modifier = Modifier.weight(1f),
                    label = profileName ?: "No profile",
                    placeholder = profileName == null,
                    options = profiles,
                    onPick = { onProfile(it ?: "") },
                )
                Spacer(Modifier.width(8.dp))
                val padName = pads.firstOrNull { it.descriptor == row.descriptor }?.name
                Picker(
                    modifier = Modifier.weight(1f),
                    label = padName ?: "No controller",
                    placeholder = padName == null,
                    options = pads.map { it.descriptor to it.name },
                    onPick = onPad,
                )
            }
        }
    }
}

private val CARD_HEIGHT = 92.dp

@Composable
private fun Picker(
    modifier: Modifier,
    label: String,
    placeholder: Boolean,
    options: List<Pair<String, String>>,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // Outlined and carated so it reads as a field to tap.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onPick(value); open = false },
                )
            }
        }
    }
}
