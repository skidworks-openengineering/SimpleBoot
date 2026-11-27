package skidworks.app.simpleboot.util

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

internal object NetbootImage {
    private const val MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private val redirects = setOf(301, 302, 303, 307, 308)
    private val legacyLoaders = setOf("pxe", "kpxe", "kkpxe", "lkrn")
    private val invalidNameChars = Regex("[^a-zA-Z0-9._-]+")
    private val scriptLoader = URI("https://boot.ipxe.org/x86_64-efi/ipxe.efi")

    fun httpsUri(link: String): URI? {
        val uri = try {
            URI(link.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        return uri.takeIf {
            it.scheme.equals("https", ignoreCase = true) && it.host != null &&
                it.userInfo == null && it.port in -1..65535
        }
    }

    fun download(url: URI, file: File, checkCancelled: () -> Unit): URI {
        var current = url
        val started = System.nanoTime()
        repeat(MAX_REDIRECTS + 1) {
            checkCancelled()
            val connection = (current.toURL().openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                val status = connection.responseCode
                when {
                    status == 200 -> {
                        if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) throw downloadTooLarge()
                        connection.inputStream.use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var downloaded = 0L
                                while (true) {
                                    checkCancelled()
                                    if (System.nanoTime() - started > 90_000_000_000L) {
                                        throw IOException("The boot file download timed out.")
                                    }
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    downloaded += count
                                    if (downloaded > MAX_DOWNLOAD_BYTES) throw downloadTooLarge()
                                    output.write(buffer, 0, count)
                                }
                                if (downloaded == 0L) throw IOException("The link returned an empty file.")
                            }
                        }
                        return current
                    }
                    status in redirects -> {
                        val location = connection.getHeaderField("Location")
                        current = location?.let { resolveRedirect(current, it) }
                            ?: throw IOException("The server redirected to an invalid or non-HTTPS link.")
                    }
                    else -> throw IOException("Could not download the boot file (HTTP $status).")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("The boot file link redirected too many times.")
    }

    private fun resolveRedirect(url: URI, location: String): URI? {
        val relative = try {
            URI(location)
        } catch (_: URISyntaxException) {
            return null
        }
        return httpsUri(url.resolve(relative).toString())
    }

    private fun downloadTooLarge() = IOException("The boot file exceeds the 32 MiB download limit.")

    fun imageName(requested: URI, downloaded: URI): String {
        val original = filename(requested)
        val name = original.ifEmpty { filename(downloaded) }.substringBeforeLast('.')
        return name.replace(invalidNameChars, "-").take(80).trim('.', '-', '_').ifEmpty { "netboot" }
    }

    fun write(
        source: URI,
        downloadedFrom: URI,
        payload: File,
        directory: File,
        target: File,
        checkCancelled: () -> Unit
    ) {
        val extensions = listOf(source, downloadedFrom).map { filename(it).substringAfterLast('.', "").lowercase(Locale.ROOT) }
        val fallback = fallbackName(payload)
        when {
            fallback != null -> writeEfiDisk(payload, fallback, null, directory, target, checkCancelled)
            isScript(payload) -> {
                val loader = File(directory, "ipxe.efi")
                download(scriptLoader, loader, checkCancelled)
                if (fallbackName(loader) != "BOOTX64.EFI") {
                    throw IOException("The iPXE server did not return the x86-64 UEFI loader.")
                }
                writeEfiDisk(loader, "BOOTX64.EFI", source, directory, target, checkCancelled)
            }
            "usb" in extensions -> {
                if (!hasBootSector(payload)) throw IOException("The link did not return a bootable .usb disk image.")
                checkCancelled()
                target.outputStream().use { output ->
                    payload.inputStream().use { it.copyTo(output) }
                    output.fd.sync()
                }
            }
            extensions.any { it in legacyLoaders } ->
                throw IOException("This loader needs a PXE server or chainloader. Use a UEFI .efi or bootable .usb link.")
            else -> throw IOException("Use a direct link to a UEFI .efi, iPXE script, or bootable .usb disk image.")
        }
    }

    private fun writeEfiDisk(
        loader: File,
        name: String,
        script: URI?,
        directory: File,
        target: File,
        checkCancelled: () -> Unit
    ) {
        val root = File(directory, "root")
        val boot = File(root, "EFI/BOOT")
        if (!boot.mkdirs()) throw IOException("Could not prepare the EFI boot folder.")
        loader.copyTo(File(boot, name))
        if (script != null) {
            // Preserve the script's network location so it can resolve relative URLs.
            // The typed setting keeps URL characters out of the iPXE command syntax.
            val urlHex = script.toASCIIString().encodeToByteArray().joinToString(":") { "%02x".format(it) }
            File(root, "autoexec.ipxe").writeText(
                "#!ipxe\nset simpleboot-url:hex $urlHex || shell\ndhcp && chain \${simpleboot-url:string} || shell\n"
            )
        }
        Fat32BootImage.write(root, target, "NETBOOT", checkCancelled)
    }

    fun fallbackName(payload: File): String? = RandomAccessFile(payload, "r").use { file ->
        if (file.length() < 64 || file.readUnsignedShort() != 0x4d5a) return null
        file.seek(0x3c)
        val pe = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
        if (pe < 64 || pe > file.length() - 24) throw invalidEfi()
        file.seek(pe)
        if (file.readInt() != 0x50450000) throw invalidEfi()
        val machine = file.readLittleShort()
        file.seek(pe + 20)
        val optionalSize = file.readLittleShort()
        val optionalHeader = pe + 24
        if (optionalSize < 70 || optionalHeader + optionalSize > file.length()) throw invalidEfi()
        file.seek(optionalHeader)
        if (file.readLittleShort() !in setOf(0x10b, 0x20b)) throw invalidEfi()
        file.seek(optionalHeader + 68)
        if (file.readLittleShort() != 10) throw invalidEfi() // EFI application subsystem.

        when (machine) {
            0x014c -> "BOOTIA32.EFI"
            0x8664 -> "BOOTX64.EFI"
            0x01c0, 0x01c2, 0x01c4 -> "BOOTARM.EFI"
            0xaa64 -> "BOOTAA64.EFI"
            0x5064 -> "BOOTRISCV64.EFI"
            else -> throw IOException("This EFI application's CPU architecture is unsupported.")
        }
    }

    private fun invalidEfi() = IOException("The downloaded file is not a valid UEFI application.")

    private fun RandomAccessFile.readLittleShort(): Int =
        java.lang.Short.reverseBytes(readShort()).toInt() and 0xffff

    private fun filename(uri: URI): String = uri.path.orEmpty().substringAfterLast('/')

    private fun isScript(payload: File): Boolean = RandomAccessFile(payload, "r").use { file ->
        if (file.length() < 7) return false
        val magic = ByteArray(7)
        file.readFully(magic)
        magic.contentEquals("#!ipxe\n".encodeToByteArray()) ||
            magic.contentEquals("#!ipxe\r".encodeToByteArray())
    }

    private fun hasBootSector(payload: File): Boolean = RandomAccessFile(payload, "r").use { file ->
        if (file.length() < 512 || file.length() % 512 != 0L) return false
        file.seek(510)
        file.readUnsignedShort() == 0x55aa
    }
}
