package skidworks.app.simpleboot.util

import android.content.Context
import android.os.Environment
import skidworks.app.simpleboot.model.ExtractedIsoType
import skidworks.app.simpleboot.model.IsoFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Locale

/**
 * Handles storage-related operations for the app.
 * Now includes detailed debug logging to track directory creation and ISO discovery.
 */
object StorageManager {

    private val ISO_DIR by lazy { File(Environment.getExternalStorageDirectory(), "SimpleBootISOs") }
    private val EXTRACTED_DIR by lazy { File(Environment.getExternalStorageDirectory(), "SimpleBootables") }
    private val BOOT_IMAGES_DIR by lazy { File(ISO_DIR, "Bootables") }
    private val NETBOOT_DIR by lazy { File(BOOT_IMAGES_DIR, "Netboot") }
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

        if (!EXTRACTED_DIR.isDirectory) {
            val success = EXTRACTED_DIR.mkdirs()
            log("Created extracted ISO directory: ${EXTRACTED_DIR.absolutePath} (success=$success)")
        }

        log("Directory check complete.")
    }

    /**
     * Retrieves a list of ISO/IMG files from the SimpleBootISOs directory.
     * @return List of IsoFile objects representing found files.
     */
    fun getIsoFileList(): List<IsoFile> {
        log("Scanning for ISO/IMG files...")

        val allowed = setOf("iso", "img")
        val isoFiles = ISO_DIR.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in allowed }
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
        val extractedImages = ExtractedIsoType.entries.asSequence().flatMap { type ->
            bootImageFiles(bootImageDirectory(type))
                .map { IsoFile(it.nameWithoutExtension, it.absolutePath, it.length(), type) }
        }
        val netbootImages = bootImageFiles(NETBOOT_DIR)
            .map { IsoFile(it.nameWithoutExtension, it.absolutePath, it.length(), isNetboot = true) }

        val images = (isoFiles + extractedImages + netbootImages)
            .sortedBy { it.name.lowercase(Locale.ROOT) }.toList()
        log("Scan complete - found ${images.size} image(s).")
        return images
    }

    internal fun getExtractedFolders(): List<String> {
        if (!Files.isDirectory(EXTRACTED_DIR.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Could not open /sdcard/SimpleBootables.")
        }
        val folders = EXTRACTED_DIR.listFiles()
            ?: throw IOException("Could not list folders in /sdcard/SimpleBootables.")
        return folders.filter { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
            .map(File::getName)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    @Synchronized
    internal fun importExtractedIso(
        folder: String,
        type: ExtractedIsoType,
        volumeLabel: String,
        checkCancelled: () -> Unit
    ) {
        if (folder !in getExtractedFolders()) {
            throw IOException("The selected folder is no longer available.")
        }
        val label = volumeLabel.trim().uppercase(Locale.ROOT).ifEmpty { type.name }
        if (!Fat32BootImage.isValidLabel(label)) {
            throw IOException("Enter a valid FAT32 volume label of up to 11 characters.")
        }
        saveBootImage(bootImageDirectory(type), folder, checkCancelled) { staged ->
            Fat32BootImage.write(File(EXTRACTED_DIR, folder), staged, label, checkCancelled)
        }
    }

    @Synchronized
    internal fun importNetbootLink(
        context: Context,
        link: String,
        checkCancelled: () -> Unit
    ): String {
        val url = NetbootImage.httpsUri(link) ?: throw IOException("Enter a valid HTTPS link.")
        val workDir = Files.createTempDirectory(context.cacheDir.toPath(), "netboot-").toFile()
        try {
            val payload = File(workDir, "download")
            val downloadedFrom = NetbootImage.download(url, payload, checkCancelled)
            val name = NetbootImage.imageName(url, downloadedFrom)
            saveBootImage(NETBOOT_DIR, name, checkCancelled) { staged ->
                NetbootImage.write(url, downloadedFrom, payload, workDir, staged, checkCancelled)
            }
            return name
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun saveBootImage(
        directory: File,
        name: String,
        checkCancelled: () -> Unit,
        write: (File) -> Unit
    ) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create the bootable image directory.")
        }
        val image = File(directory, "$name.img")
        if (image.exists()) throw IOException("An image named $name already exists in ${directory.name}.")

        // Remove staging files left by a process that exited before its import finished.
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(".simpleboot-") && it.extension == "partial" }
            .filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
            .forEach { it.delete() }
        val staged = File.createTempFile(".simpleboot-", ".partial", directory)
        try {
            write(staged)
            checkCancelled()
            Files.move(staged.toPath(), image.toPath())
        } finally {
            staged.delete()
        }
    }

    private fun bootImageDirectory(type: ExtractedIsoType) = File(BOOT_IMAGES_DIR, type.displayName)

    private fun bootImageFiles(directory: File): Sequence<File> =
        directory.listFiles().orEmpty().asSequence()
            .filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.extension == "img" }
}
