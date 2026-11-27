package skidworks.app.simpleboot.model

import skidworks.app.simpleboot.util.LogManager
import android.content.Context
import java.io.File
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

enum class ExtractedIsoType(val displayName: String) {
    WINDOWS("Windows"),
    LINUX("Linux")
}

/**
 * Represents a disk image available for mounting.
 * Includes debug utilities for diagnostics and readable logging.
 *
 * @param name  The display name of the file (e.g., "ubuntu.iso").
 * @param path  Full absolute path to the file on the device.
 * @param size  File size in bytes.
 * @param extractedType  OS type for an image built from an extracted ISO.
 * @param isNetboot  Whether the image was imported from a network boot link.
 */
data class IsoFile(
    val name: String,
    val path: String,
    val size: Long,
    val extractedType: ExtractedIsoType? = null,
    val isNetboot: Boolean = false
) {
    /**
     * Converts file size in bytes to a human-readable string (e.g., "2.3 GB").
     */
    fun readableSize(): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())) + " " + units[digitGroups]
    }

    /**
     * Logs diagnostic information about this ISO file to LogManager.
     * Context is optional since LogManager ignores it internally.
     */
    fun logInfo(context: Context? = null) {
        try {
            val fileExists = File(path).exists()
            LogManager.logToFile(context, "[IsoFile] Info -> name=$name, path=$path, size=${readableSize()}, exists=$fileExists")
        } catch (e: Exception) {
            LogManager.logToFile(context, "[IsoFile] Error while logging info: ${e.message}")
        }
    }

    /**
     * Provides a simplified and clear representation for debugging or logs.
     */
    override fun toString(): String {
        val fileExists = File(path).exists()
        return "IsoFile(name='$name', path='$path', size=${readableSize()}, exists=$fileExists)"
    }
}
