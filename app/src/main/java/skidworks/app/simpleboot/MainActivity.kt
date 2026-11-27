package skidworks.app.simpleboot

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import skidworks.app.simpleboot.model.IsoFile
import skidworks.app.simpleboot.ui.theme.SimpleBootTheme
import skidworks.app.simpleboot.util.LogManager
import skidworks.app.simpleboot.util.MountController
import skidworks.app.simpleboot.util.MountMethod
import skidworks.app.simpleboot.util.MountStateStore
import skidworks.app.simpleboot.util.StorageManager
import skidworks.app.simpleboot.util.UsbMode

class MainActivity : ComponentActivity() {
    private var hasRoot by mutableStateOf<Boolean?>(null)
    private var checkingRoot by mutableStateOf(false)
    private var resumeCount by mutableIntStateOf(0)
    private val bootableImport by viewModels<BootableImportViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SimpleBootTheme {
                if (hasRoot == true) {
                    AppScreen(resumeCount, bootableImport)
                } else {
                    Scaffold(topBar = { SimpleBootTopBar() }) { padding ->
                        SetupPrompt(
                            title = if (checkingRoot) "Checking root access…" else "Root access required",
                            message = "Grant SimpleBoot superuser access in your root manager.",
                            action = "Retry root access",
                            enabled = !checkingRoot,
                            onClick = ::checkRoot,
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
        if (hasRoot != true) checkRoot()
    }

    private fun checkRoot() {
        if (checkingRoot) return
        checkingRoot = true
        lifecycleScope.launch {
            try {
                hasRoot = withContext(Dispatchers.IO) {
                    try {
                        Shell.getCachedShell()?.takeUnless { it.isRoot }?.close()
                        Shell.getShell().isRoot
                    } catch (exception: Exception) {
                        LogManager.logToFile(this@MainActivity, "Root check failed: ${exception.message}")
                        false
                    }
                }
            } finally {
                checkingRoot = false
            }
        }
    }
}

@Composable
private fun AppScreen(resumeCount: Int, bootableImport: BootableImportViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isoList by remember { mutableStateOf<List<IsoFile>>(emptyList()) }
    var currentMount by remember { mutableStateOf<MountStateStore.MountInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var hasStorageAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var selectedIso by remember { mutableStateOf<IsoFile?>(null) }
    var selectedMethod by rememberSaveable { mutableStateOf(MountMethod.CONFIGFS) }
    var selectedUsbMode by rememberSaveable { mutableStateOf(UsbMode.USB_HDD) }
    var showCredits by rememberSaveable { mutableStateOf(false) }
    var bootableSource by rememberSaveable { mutableStateOf<BootableSource?>(null) }
    var operationInProgress by remember { mutableStateOf(false) }
    val busy = operationInProgress || bootableImport.importing

    LaunchedEffect(resumeCount, busy) {
        hasStorageAccess = Environment.isExternalStorageManager()
        if (busy || !hasStorageAccess) return@LaunchedEffect
        val (files, mount) = withContext(Dispatchers.IO) {
            StorageManager.ensureDirectories()
            StorageManager.getIsoFileList() to MountController.currentMount(context)
        }
        isoList = files
        currentMount = mount
    }

    val importMessage = bootableImport.message
    LaunchedEffect(importMessage) {
        if (importMessage != null) {
            bootableImport.clearMessage()
            coroutineScope.launch { snackbarHostState.showSnackbar(importMessage) }
        }
    }

    fun handleMount(iso: IsoFile, method: MountMethod, mode: UsbMode, readOnly: Boolean = true) {
        if (busy) return
        val unmounting = currentMount?.filePath == iso.path
        operationInProgress = true
        coroutineScope.launch {
            val message = try {
                val (result, mount) = withContext(Dispatchers.IO) {
                    val result = if (unmounting) MountController.unmount(context)
                    else MountController.mount(context, iso.path, method, mode, readOnly = readOnly)
                    result to MountController.currentMount(context)
                }
                currentMount = mount
                if (result.success && unmounting) "Unmounted ${iso.name}" else result.message
            } finally {
                operationInProgress = false
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    BackHandler(enabled = showCredits) { showCredits = false }

    if (showCredits) {
        CreditsScreen(onBack = { showCredits = false })
        return
    }

    Scaffold(
        topBar = {
            SimpleBootTopBar(actions = {
                LibraryActions(
                    onShareLog = {
                        val intent = LogManager.exportLogFile(context)
                        if (intent != null) {
                            context.startActivity(Intent.createChooser(intent, "Share SimpleBoot Log"))
                        } else {
                            coroutineScope.launch { snackbarHostState.showSnackbar("No log file available") }
                        }
                    },
                    onCredits = { showCredits = true },
                    onAddBootable = if (hasStorageAccess) ({ bootableSource = BootableSource.CHOOSER }) else null,
                    canAddBootable = !busy
                )
            })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (hasStorageAccess) {
            ImageLibrary(
                images = isoList,
                mounted = currentMount,
                method = selectedMethod,
                usbMode = selectedUsbMode,
                busy = busy,
                importing = bootableImport.importing,
                onMethodChange = { selectedMethod = it },
                onUsbModeChange = { selectedUsbMode = it },
                onImageClick = { selectedIso = it },
                modifier = Modifier.padding(padding)
            )
        } else {
            SetupPrompt(
                title = "Allow file access",
                message = "SimpleBoot needs access to the ISO files, disk images, and extracted ISO folders on your device.",
                action = "Grant all-files access",
                enabled = true,
                onClick = {
                    val settings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    settings.data = "package:${context.packageName}".toUri()
                    context.startActivity(settings)
                },
                modifier = Modifier.padding(padding)
            )
        }
    }

    if (hasStorageAccess) {
        when (bootableSource) {
            BootableSource.CHOOSER -> AddBootableSourceDialog(
                onDismiss = { bootableSource = null },
                onSelect = { bootableSource = it }
            )
            BootableSource.EXTRACTED_ISO -> AddBootableDialog(
                resumeCount = resumeCount,
                onDismiss = { bootableSource = null },
                onAdd = { folder, type, label ->
                    bootableImport.add(context, folder, type, label)
                    bootableSource = null
                }
            )
            BootableSource.NETBOOT_LINK -> AddNetbootDialog(
                onDismiss = { bootableSource = null },
                onAdd = { link ->
                    bootableImport.addNetbootLink(context, link)
                    bootableSource = null
                }
            )
            null -> Unit
        }
    }

    val image = selectedIso
    if (image != null) {
        val mounted = currentMount?.takeIf { it.filePath == image.path }
        val usesUsb = selectedMethod != MountMethod.LOOPBACK
        val mode = if (image.extractedType != null || image.isNetboot) UsbMode.USB_HDD else selectedUsbMode
        val allowsWrite = !usesUsb || mode != UsbMode.CD_ROM
        var readOnly by remember(image.path) { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { selectedIso = null },
            title = { Text(if (mounted == null) "Mount Options" else "Unmount Image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(image.name)
                    if (mounted == null) {
                        Text("Mount method: ${selectedMethod.displayName}")
                        if (usesUsb) Text("USB method: ${mode.displayName}")
                        if (allowsWrite) {
                            Column(Modifier.selectableGroup()) {
                                MountAccessOption("Read-only", readOnly) { readOnly = true }
                                MountAccessOption("Read/write", !readOnly) { readOnly = false }
                            }
                            if (!readOnly) Text("Writes modify the original image.")
                        } else {
                            Text("CD-ROM is read-only. Select USB HDD to enable writes.")
                        }
                    } else if (!mounted.readOnly && mounted.method != MountMethod.LOOPBACK) {
                        Text("Eject the drive on your computer before unmounting.")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    LogManager.logToFile(context, "Mount action confirmed for ${image.name}")
                    handleMount(image, selectedMethod, mode, readOnly = readOnly || !allowsWrite)
                    selectedIso = null
                }) { Text(if (mounted != null) "Unmount" else "Mount") }
            },
            dismissButton = {
                Button(onClick = {
                    LogManager.logToFile(context, "Mount dialog cancelled")
                    selectedIso = null
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MountAccessOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}
