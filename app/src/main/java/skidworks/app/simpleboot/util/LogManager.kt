package skidworks.app.simpleboot.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Centralized logger for SimpleBoot.
 * Writes detailed logs to /storage/emulated/0/SimpleBootLogs and handles export.
 * Now includes self-diagnostics and graceful error handling.
 */
object LogManager {

    private const val DATE_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private const val FILE_DATE_PATTERN = "yyyyMMdd"
    private val formatter = DateTimeFormatter.ofPattern(DATE_PATTERN)
    private val LOG_DIR = File(Environment.getExternalStorageDirectory(), "SimpleBootLogs")

    init {
        ensureLogDir()
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private fun ensureLogDir(): Boolean {
        return try {
            if (!LOG_DIR.exists()) {
                val success = LOG_DIR.mkdirs()
                if (!success) {
                    System.err.println("[LogManager] Failed to create log directory: ${LOG_DIR.absolutePath}")
                } else {
                    println("[LogManager] Created log directory: ${LOG_DIR.absolutePath}")
                }
                success
            } else true
        } catch (e: Exception) {
            System.err.println("[LogManager] Exception while creating log directory: ${e.message}")
            false
        }
    }

    private fun getLogFile(): File? {
        return try {
            val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(FILE_DATE_PATTERN))
            val logFile = File(LOG_DIR, "mount_log_$date.txt")
            if (!logFile.exists()) logFile.createNewFile()
            logFile
        } catch (e: IOException) {
            System.err.println("[LogManager] Failed to access log file: ${e.message}")
            null
        }
    }

    private fun safeWrite(file: File?, text: String) {
        if (file == null) return
        try {
            file.appendText(text)
        } catch (e: IOException) {
            System.err.println("[LogManager] Write error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Public logging functions
    // -------------------------------------------------------------------------

    @Suppress("UNUSED_PARAMETER")
    fun logMount(context: Context?, fileName: String, loopDevice: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val entry = "[$timestamp] [Mount] Mounted: $fileName to $loopDevice\n"
        logInternal(entry)
    }

    @Suppress("UNUSED_PARAMETER")
    fun logUnmount(context: Context?, fileName: String, loopDevice: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val entry = "[$timestamp] [Unmount] Unmounted: $fileName from $loopDevice\n"
        logInternal(entry)
    }

    @Suppress("UNUSED_PARAMETER")
    fun logToFile(context: Context?, message: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val entry = "[$timestamp] $message\n"
        logInternal(entry)
    }

    private fun logInternal(entry: String) {
        // Self-checks before writing
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            System.err.println("[LogManager] Skipping write — external storage not mounted.")
            return
        }

        if (!ensureLogDir()) {
            System.err.println("[LogManager] Skipping write — could not ensure log directory.")
            return
        }

        val file = getLogFile()
        if (file == null) {
            System.err.println("[LogManager] Skipping write — log file unavailable.")
            return
        }

        safeWrite(file, entry)
    }

    // -------------------------------------------------------------------------
    // Export function
    // -------------------------------------------------------------------------

    fun exportLogFile(context: Context): Intent? {
        val date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(FILE_DATE_PATTERN))
        val file = File(LOG_DIR, "mount_log_$date.txt")

        if (!file.exists()) {
            logToFile(context, "[LogManager] Export aborted — no log file available for today.")
            return null
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".provider",
            file
        )

        logToFile(context, "[LogManager] Exporting log file: ${file.absolutePath}")

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
