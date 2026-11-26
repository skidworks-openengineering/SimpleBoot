package skidworks.app.simpleboot.util

import android.content.Context
import android.os.Environment
import skidworks.app.simpleboot.model.IsoFile
import java.io.File
import java.util.Locale

/**
 * Handles storage-related operations for the app.
 * Now includes detailed debug logging to track directory creation and ISO discovery.
 */
object StorageManager {

    private val ISO_DIR by lazy { File(Environment.getExternalStorageDirectory(), "SimpleBootISOs") }
    private val LOG_DIR by lazy { File(Environment.getExternalStorageDirectory(), "SimpleBootLogs") }

    private fun log(message: String) {
        try {
            LogManager.logToFile(null as Context?, "[StorageManager] $message")
        } catch (_: Exception) {
            // Ignore early calls or missing context
        }
    }

    /**
     * Ensures required directories exist in external storage.
     */
    fun ensureDirectories() {
        log("Ensuring required directories...")

        if (!ISO_DIR.exists()) {
            val success = ISO_DIR.mkdirs()
            log("Created ISO directory: ${ISO_DIR.absolutePath} (success=$success)")
        } else {
            log("ISO directory already exists: ${ISO_DIR.absolutePath}")
        }

        if (!LOG_DIR.exists()) {
            val success = LOG_DIR.mkdirs()
            log("Created Log directory: ${LOG_DIR.absolutePath} (success=$success)")
        } else {
            log("Log directory already exists: ${LOG_DIR.absolutePath}")
        }

        log("Directory check complete.")
    }

    /**
     * Retrieves a list of ISO/IMG files from the SimpleBootISOs directory.
     * @return List of IsoFile objects representing found files.
     */
    fun getIsoFileList(): List<IsoFile> {
        log("Scanning for ISO/IMG files...")

        if (!ISO_DIR.exists() || !ISO_DIR.isDirectory) {
            log("ISO directory does not exist or is invalid: ${ISO_DIR.absolutePath}")
            return emptyList()
        }

        val allowed = setOf("iso", "img")
        val files = ISO_DIR.listFiles()

        if (files == null) {
            log("Failed to list files in ISO directory (permission issue or empty folder).")
            return emptyList()
        }

        val isoFiles = files.asSequence()
            .filter { it.isFile }
            .filter { allowed.contains(it.extension.lowercase(Locale.getDefault())) }
            .map { file ->
                IsoFile(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length()
                ).also { iso ->
                    // Log each discovered ISO for debugging
                    iso.logInfo()
                }
            }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
            .toList()

        log("Scan complete - found ${isoFiles.size} ISO(s).")
        return isoFiles
    }
}
