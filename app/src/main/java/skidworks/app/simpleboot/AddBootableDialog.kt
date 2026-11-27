package skidworks.app.simpleboot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import skidworks.app.simpleboot.model.ExtractedIsoType
import skidworks.app.simpleboot.util.Fat32BootImage
import skidworks.app.simpleboot.util.StorageManager

@Composable
internal fun AddBootableDialog(
    resumeCount: Int,
    onDismiss: () -> Unit,
    onAdd: (String, ExtractedIsoType, String) -> Unit
) {
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedType by rememberSaveable { mutableStateOf<ExtractedIsoType?>(null) }
    var volumeLabel by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(resumeCount) {
        loadError = null
        try {
            folders = withContext(Dispatchers.IO) {
                StorageManager.ensureDirectories()
                StorageManager.getExtractedFolders()
            }
        } catch (e: IOException) {
            folders = emptyList()
            loadError = e.message
        } catch (_: SecurityException) {
            folders = emptyList()
            loadError = "Allow file access to read /sdcard/SimpleBootables."
        }
    }

    val availableFolders = folders.orEmpty()
    val folder = selectedFolder?.takeIf { it in availableFolders }
    val type = selectedType
    val label = if (type == ExtractedIsoType.LINUX) volumeLabel.trim().uppercase(Locale.ROOT) else ""
    val validLabel = label.isEmpty() || Fat32BootImage.isValidLabel(label)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add extracted ISO") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    loadError != null -> Text(loadError.orEmpty())
                    folders == null -> Text("Looking for folders…")
                    availableFolders.isEmpty() ->
                        Text("Extract an ISO into its own folder under /sdcard/SimpleBootables, then reopen this dialog.")
                    else -> {
                        Text("Create a USB HDD image for UEFI boot from a folder in /sdcard/SimpleBootables.")
                        MountOptionField<String?>(
                            label = "Extracted ISO folder",
                            value = folder,
                            options = availableFolders,
                            displayName = { it.orEmpty() },
                            enabled = true,
                            onSelected = { selectedFolder = it }
                        )
                        MountOptionField<ExtractedIsoType?>(
                            label = "ISO type",
                            value = type,
                            options = ExtractedIsoType.entries,
                            displayName = { it?.displayName.orEmpty() },
                            enabled = true,
                            onSelected = { selectedType = it }
                        )
                        if (type == ExtractedIsoType.LINUX) {
                            OutlinedTextField(
                                value = volumeLabel,
                                onValueChange = { volumeLabel = it },
                                label = { Text("Volume label (optional)") },
                                supportingText = {
                                    Text(if (validLabel) "Enter the media label if your distribution requires one."
                                    else "Use up to 11 FAT32-compatible characters.")
                                },
                                isError = !validLabel,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (folder != null && type != null) onAdd(folder, type, label) },
                enabled = folder != null && type != null && validLabel
            ) { Text("Add") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}
