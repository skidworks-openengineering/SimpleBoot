package skidworks.app.simpleboot

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.io.File
import skidworks.app.simpleboot.model.IsoFile
import skidworks.app.simpleboot.ui.theme.SimpleBootTheme
import skidworks.app.simpleboot.util.MountMethod
import skidworks.app.simpleboot.util.MountStateStore
import skidworks.app.simpleboot.util.UsbMode

private val panelShape = RoundedCornerShape(12.dp)

internal val MountMethod.displayName: String
    get() = when (this) {
        MountMethod.AUTO -> "Auto"
        MountMethod.CONFIGFS -> "ConfigFS"
        MountMethod.LEGACY -> "Legacy"
        MountMethod.LOOPBACK -> "Loopback"
        MountMethod.PIXEL -> "Pixel"
    }

internal val UsbMode.displayName: String
    get() = when (this) {
        UsbMode.USB_HDD -> "USB HDD"
        UsbMode.CD_ROM -> "CD-ROM"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimpleBootTopBar(
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = "SimpleBoot",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@Composable
internal fun LibraryActions(onShareLog: () -> Unit, onCredits: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = onShareLog) {
        Icon(Icons.Outlined.Share, contentDescription = "Export log")
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Credits") },
                onClick = {
                    expanded = false
                    onCredits()
                }
            )
        }
    }
}

@Composable
internal fun ImageLibrary(
    images: List<IsoFile>,
    mounted: MountStateStore.MountInfo?,
    method: MountMethod,
    usbMode: UsbMode,
    busy: Boolean,
    onMethodChange: (MountMethod) -> Unit,
    onUsbModeChange: (UsbMode) -> Unit,
    onImageClick: (IsoFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val missingMount = mounted?.takeUnless { mount -> images.any { it.path == mount.filePath } }
    val visibleImages = if (missingMount == null) images else {
        images + IsoFile(File(missingMount.filePath).name, missingMount.filePath, 0)
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                MountOptionsCard(method, usbMode, !busy, onMethodChange, onUsbModeChange)
            }
            item { MountStatus(mounted, busy) }
            item {
                Text(
                    text = "Available Images",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() }
                )
            }
            if (visibleImages.isEmpty()) {
                item {
                    OutlinedCard(
                        shape = panelShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("No images found", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Add ISO or IMG files to /storage/emulated/0/SimpleBootISOs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            items(visibleImages, key = { it.path }) { image ->
                ImageRow(
                    image = image,
                    mounted = mounted?.filePath == image.path,
                    enabled = !busy,
                    onClick = { onImageClick(image) }
                )
            }
        }
    }
}

@Composable
private fun MountOptionsCard(
    method: MountMethod,
    usbMode: UsbMode,
    enabled: Boolean,
    onMethodChange: (MountMethod) -> Unit,
    onUsbModeChange: (UsbMode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = panelShape
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Mount Options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MountOptionField(
                    label = "Mount Method",
                    value = method,
                    options = MountMethod.entries,
                    displayName = { it.displayName },
                    enabled = enabled,
                    onSelected = onMethodChange,
                    modifier = Modifier.weight(1f)
                )
                MountOptionField(
                    label = "USB Method",
                    value = usbMode,
                    options = UsbMode.entries,
                    displayName = { it.displayName },
                    enabled = enabled && method != MountMethod.LOOPBACK,
                    onSelected = onUsbModeChange,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> MountOptionField(
    label: String,
    value: T,
    options: List<T>,
    displayName: (T) -> String,
    enabled: Boolean,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = enabled && expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayName(value),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = enabled && expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
        )
        ExposedDropdownMenu(expanded = enabled && expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(displayName(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun MountStatus(mounted: MountStateStore.MountInfo?, busy: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = panelShape,
        color = if (mounted == null) colors.surface else colors.primaryContainer,
        contentColor = if (mounted == null) colors.onSurfaceVariant else colors.onPrimaryContainer
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (mounted == null) Icons.Outlined.Usb else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Column(Modifier.weight(1f)) {
                    val label = when {
                        busy -> "Updating mount…"
                        mounted == null -> "No image mounted"
                        mounted.readOnly -> "Mounted read-only"
                        else -> "Mounted read/write"
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    if (!busy && mounted != null) {
                        Text(
                            File(mounted.filePath).name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ImageRow(image: IsoFile, mounted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        shape = panelShape,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (mounted) colors.secondaryContainer else colors.background
        ),
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Outlined.Description,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (mounted) colors.primary else colors.onSurfaceVariant
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = image.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = image.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (mounted) {
                Surface(color = colors.primary, contentColor = colors.onPrimary, shape = RoundedCornerShape(6.dp)) {
                    Text("Mounted", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp))
                }
            }
        }
    }
}

@Composable
internal fun SetupPrompt(
    title: String,
    message: String,
    action: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}

@Preview(name = "v3.0 dark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "v3.0 light", widthDp = 412, heightDp = 915)
@Composable
private fun ImageLibraryPreview() {
    val folder = "/storage/emulated/0/SimpleBootISOs"
    val files = listOf(
        IsoFile("debian-13.7.0-amd64-netinst.iso", "$folder/debian-13.7.0-amd64-netinst.iso", 0),
        IsoFile("debian-live-13.7.0-amd64-xfce.iso", "$folder/debian-live-13.7.0-amd64-xfce.iso", 0),
        IsoFile("xubuntu-26.10-desktop-amd64.iso", "$folder/xubuntu-26.10-desktop-amd64.iso", 0)
    )
    SimpleBootTheme {
        val snackbar = remember { SnackbarHostState() }
        Scaffold(
            topBar = { SimpleBootTopBar(actions = { LibraryActions({}, {}) }) },
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            ImageLibrary(
                images = files,
                mounted = MountStateStore.MountInfo(
                    filePath = files.last().path,
                    loopDevice = "",
                    mountTime = 0,
                    lun = "0",
                    method = MountMethod.CONFIGFS
                ),
                method = MountMethod.CONFIGFS,
                usbMode = UsbMode.CD_ROM,
                busy = false,
                onMethodChange = {},
                onUsbModeChange = {},
                onImageClick = {},
                modifier = Modifier.padding(padding)
            )
        }
    }
}
