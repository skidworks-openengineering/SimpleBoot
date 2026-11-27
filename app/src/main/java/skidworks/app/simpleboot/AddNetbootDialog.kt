package skidworks.app.simpleboot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import skidworks.app.simpleboot.util.NetbootImage

internal enum class BootableSource { CHOOSER, EXTRACTED_ISO, NETBOOT_LINK }

@Composable
internal fun AddBootableSourceDialog(onDismiss: () -> Unit, onSelect: (BootableSource) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bootable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSelect(BootableSource.EXTRACTED_ISO) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Extracted ISO") }
                Button(
                    onClick = { onSelect(BootableSource.NETBOOT_LINK) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Network boot link") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun AddNetbootDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    val valid = NetbootImage.httpsUri(link) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add network boot link") },
        text = {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("HTTPS link") },
                supportingText = { Text("Use a direct link to an .efi file, iPXE script, or bootable .usb image.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onAdd(link.trim()) }, enabled = valid) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
