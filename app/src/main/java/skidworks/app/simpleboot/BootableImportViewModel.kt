package skidworks.app.simpleboot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import skidworks.app.simpleboot.model.ExtractedIsoType
import skidworks.app.simpleboot.util.LogManager
import skidworks.app.simpleboot.util.StorageManager

internal class BootableImportViewModel : ViewModel() {
    var importing by mutableStateOf(false)
        private set

    var message by mutableStateOf<String?>(null)
        private set

    fun add(context: Context, folder: String, type: ExtractedIsoType, volumeLabel: String) {
        importImage(context, "extracted ISO") {
            StorageManager.importExtractedIso(folder, type, volumeLabel) { ensureActive() }
            "Added $folder as a ${type.displayName} bootable image."
        }
    }

    fun addNetbootLink(context: Context, link: String) {
        val appContext = context.applicationContext
        importImage(appContext, "network boot link") {
            val name = StorageManager.importNetbootLink(appContext, link) { ensureActive() }
            "Added $name as a network boot image."
        }
    }

    private fun importImage(context: Context, source: String, import: suspend CoroutineScope.() -> String) {
        if (importing) return
        importing = true
        message = null
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                message = withContext(Dispatchers.IO, block = import)
            } catch (e: IOException) {
                LogManager.logToFile(appContext, "Could not import $source (${e.javaClass.simpleName})")
                message = e.message ?: "Could not create the bootable image."
            } catch (_: SecurityException) {
                LogManager.logToFile(appContext, "Could not access files for $source")
                message = "Could not access the files needed to create the bootable image."
            } finally {
                importing = false
            }
        }
    }

    fun clearMessage() {
        message = null
    }
}
