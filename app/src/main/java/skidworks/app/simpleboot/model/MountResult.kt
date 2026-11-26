package skidworks.app.simpleboot.model

import android.content.Context
import skidworks.app.simpleboot.util.LogManager

/**
 * Represents the result of a mount or unmount operation.
 * Now includes built-in debug logging and a readable string format.
 *
 * @param success     Whether the operation was successful.
 * @param message     A user-friendly message explaining the outcome.
 * @param loopDevice  Optional: the loop device used (if applicable).
 */
data class MountResult(
    val success: Boolean,
    val message: String,
    val loopDevice: String? = null
) {

    /**
     * Logs this MountResult to the debug log file.
     */
    fun log(context: Context? = null, tag: String = "MountResult") {
        try {
            LogManager.logToFile(context, "[$tag] success=$success, message='$message', loop=${loopDevice ?: "N/A"}")
        } catch (e: Exception) {
            LogManager.logToFile(context, "[$tag] Failed to log result: ${e.message}")
        }
    }

    /**
     * Returns a human-readable string representation for debug output.
     */
    override fun toString(): String {
        return "MountResult(success=$success, message='$message', loop=${loopDevice ?: "N/A"})"
    }

    /**
     * Convenience function for creating and logging a result in one call.
     */
    companion object {
        fun create(
            context: Context? = null,
            success: Boolean,
            message: String,
            loopDevice: String? = null,
            tag: String = "MountResult"
        ): MountResult {
            val result = MountResult(success, message, loopDevice)
            result.log(context, tag)
            return result
        }
    }
}
