package skidworks.app.simpleboot.util

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Locale
import kotlin.random.Random

/**
 * Writes a partitioned FAT32 disk for UEFI removable-media boot.
 * Contiguous allocation keeps memory use independent of the amount of file data.
 */
internal object Fat32BootImage {
    private const val SECTOR_BYTES = 512
    private const val SECTORS_PER_CLUSTER = 8
    private const val CLUSTER_BYTES = SECTOR_BYTES * SECTORS_PER_CLUSTER
    private const val PARTITION_START = 2048
    private const val RESERVED_SECTORS = 32
    private const val ENTRY_BYTES = 32
    private const val LONG_NAME_CHARS = 13
    private const val END_OF_CHAIN = 0x0fffffff
    private const val MAX_FILE_BYTES = 0xffffffffL
    private const val DIRECTORY = 0x10
    private const val ARCHIVE = 0x20
    private const val VOLUME_LABEL = 0x08
    private const val NAME_PUNCTUATION = "!#$%&'()-@^_\u0060{}~"
    private const val INVALID_NAME_CHARS = "\"*/:<>?\\|"
    private val efiLoaders = setOf("BOOTX64.EFI", "BOOTIA32.EFI", "BOOTAA64.EFI", "BOOTARM.EFI", "BOOTRISCV64.EFI")
    private val longNameOffsets = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)

    fun isValidLabel(label: String): Boolean =
        label.length in 1..11 && label == label.trim() &&
            label.all { it == ' ' || isShortNameChar(it) }

    /** [target] is a new, empty staging file that the caller publishes after this returns. */
    fun write(source: File, target: File, label: String, checkCancelled: () -> Unit) {
        require(isValidLabel(label))
        val root = scanDirectory(source, checkCancelled)
        val loaders = root.children.find { it.file.name.equals("EFI", ignoreCase = true) }
            ?.children?.find { it.file.name.equals("BOOT", ignoreCase = true) }?.children.orEmpty()
        if (loaders.none { it.size > 0 && it.file.name.uppercase(Locale.ROOT) in efiLoaders }) {
            throw IOException("No UEFI bootloader found in EFI/BOOT. Extract the full ISO into this folder.")
        }

        val entries = mutableListOf<Entry>()
        val usedClusters = allocate(root, 2, entries, checkCancelled) - 2
        val geometry = Geometry(maxOf(65_525, usedClusters + maxOf(256, usedClusters / 100)))
        if (target.absoluteFile.parentFile?.usableSpace?.let { it < geometry.diskBytes } == true) {
            throw IOException("Not enough free space to create the bootable image.")
        }

        RandomAccessFile(target, "rw").use { image ->
            image.setLength(geometry.diskBytes)
            writeHeaders(image, geometry, label, usedClusters)
            writeFat(image, geometry, entries, checkCancelled)
            val buffer = ByteArray(256 * 1024)
            for (entry in entries) {
                checkCancelled()
                if (entry.firstCluster != 0) image.seek(geometry.clusterOffset(entry.firstCluster))
                if (entry.isDirectory) writeDirectory(image, entry, label)
                else copyFile(image, entry, buffer, checkCancelled)
            }
            checkCancelled()
            image.fd.sync()
        }
    }

    private class Entry(
        val file: File,
        val size: Long,
        val isDirectory: Boolean,
        val children: List<Entry> = emptyList()
    ) {
        var parent: Entry? = null
        var shortName = ""
        var firstCluster = 0
        var clusterCount = 0

        val longNameCount: Int
            get() {
                val shortDisplay = shortName.take(8).trimEnd() +
                    shortName.drop(8).trimEnd().let { if (it.isEmpty()) "" else ".$it" }
                return if (file.name == shortDisplay) 0
                else (file.name.length + LONG_NAME_CHARS - 1) / LONG_NAME_CHARS
            }
    }

    private class Geometry(val clusters: Int) {
        val fatSectors = ((clusters + 2L) * Int.SIZE_BYTES + SECTOR_BYTES - 1) / SECTOR_BYTES
        val partitionSectors = RESERVED_SECTORS + 2 * fatSectors + clusters.toLong() * SECTORS_PER_CLUSTER
        val diskBytes = (PARTITION_START + partitionSectors) * SECTOR_BYTES
        val fatOffset = (PARTITION_START + RESERVED_SECTORS).toLong() * SECTOR_BYTES

        fun clusterOffset(cluster: Int): Long =
            fatOffset + 2 * fatSectors * SECTOR_BYTES + (cluster - 2L) * CLUSTER_BYTES

        init {
            if (clusters + 2 >= 0x0ffffff0 || partitionSectors + PARTITION_START > 0xffffffffL) {
                throw IOException("The extracted folder is too large for one bootable disk image.")
            }
        }
    }

    private fun scanDirectory(directory: File, checkCancelled: () -> Unit): Entry {
        checkCancelled()
        val files = directory.listFiles()?.sortedBy { it.name }
            ?: throw IOException("Could not read folder ${directory.name}.")
        val names = mutableSetOf<String>()
        val children = files.map { file ->
            checkCancelled()
            val name = file.name
            if (name.length > 255 || name.last() in " ." ||
                name.any { it.code < 32 || it.code == 0xffff || it in INVALID_NAME_CHARS }
            ) {
                throw IOException("FAT32 cannot store the name $name.")
            }
            if (!names.add(name.uppercase(Locale.ROOT))) {
                throw IOException("Case-insensitive filenames conflict in ${directory.name}: $name.")
            }
            when {
                Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) -> scanDirectory(file, checkCancelled)
                Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) -> {
                    val size = Files.size(file.toPath())
                    if (size > MAX_FILE_BYTES) {
                        val hint = if (name.equals("install.wim", ignoreCase = true)) {
                            " Split install.wim into install.swm files first."
                        } else ""
                        throw IOException("$name exceeds the FAT32 per-file limit of 4 GiB minus 1 byte.$hint")
                    }
                    Entry(file, size, isDirectory = false)
                }
                else -> throw IOException("FAT32 cannot store $name as a symbolic link or special file.")
            }
        }
        assignShortNames(children)
        return Entry(directory, 0, isDirectory = true, children).also { parent ->
            children.forEach { it.parent = parent }
        }
    }

    private fun assignShortNames(entries: List<Entry>) {
        val used = mutableSetOf<String>()
        for (entry in entries) {
            val name = entry.file.name.uppercase(Locale.ROOT)
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "")
            if (base.length in 1..8 && extension.length <= 3 && (base + extension).all(::isShortNameChar)) {
                val shortName = base.padEnd(8) + extension.padEnd(3)
                if (used.add(shortName)) entry.shortName = shortName
            }
        }
        var sequence = 1
        for (entry in entries.filter { it.shortName.isEmpty() }) {
            val name = entry.file.name.uppercase(Locale.ROOT)
            val base = name.substringBeforeLast('.', name).filter(::isShortNameChar).ifEmpty { "_" }
            val extension = name.substringAfterLast('.', "").filter(::isShortNameChar).take(3)
            var shortName: String
            do {
                val suffix = "~${sequence++}"
                shortName = (base.take(8 - suffix.length) + suffix).padEnd(8) + extension.padEnd(3)
            } while (!used.add(shortName))
            entry.shortName = shortName
        }
    }

    private fun isShortNameChar(char: Char): Boolean =
        char in 'A'..'Z' || char in '0'..'9' || char in NAME_PUNCTUATION

    private fun allocate(
        entry: Entry,
        start: Int,
        entries: MutableList<Entry>,
        checkCancelled: () -> Unit
    ): Int {
        checkCancelled()
        val bytes = if (entry.isDirectory) {
            val metadataEntries = if (entry.parent == null) 1 else 2
            // Include the zero entry that marks the end of the directory.
            val records = entry.children.sumOf { 1L + it.longNameCount } + metadataEntries + 1
            if (records > 65_536) {
                throw IOException("Folder ${entry.file.name} has too many entries for FAT32.")
            }
            records * ENTRY_BYTES
        } else entry.size
        val clusters = (bytes + CLUSTER_BYTES - 1) / CLUSTER_BYTES
        if (start + clusters >= 0x0ffffff0) {
            throw IOException("The extracted folder is too large for FAT32.")
        }
        entry.clusterCount = clusters.toInt()
        if (clusters > 0) entry.firstCluster = start
        entries.add(entry)
        var next = start + entry.clusterCount
        for (child in entry.children) next = allocate(child, next, entries, checkCancelled)
        return next
    }

    private fun writeHeaders(image: RandomAccessFile, geometry: Geometry, label: String, usedClusters: Int) {
        val signature = Random.nextInt()
        val mbr = bytes(SECTOR_BYTES).apply {
            putInt(440, signature)
            position(446)
            put(0x80.toByte())                         // Active primary partition.
            put(byteArrayOf(0x20, 0x21, 0))            // LBA 2048 at 255 heads / 63 sectors.
            put(0x0c)                                  // FAT32 with LBA addressing.
            put(byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xff.toByte()))
            putInt(PARTITION_START)
            putInt(geometry.partitionSectors.toInt())
            putShort(510, 0xaa55.toShort())
        }
        val boot = bytes(SECTOR_BYTES).apply {
            put(byteArrayOf(0xeb.toByte(), 0x58, 0x90.toByte()))
            putAscii("MSWIN4.1")
            putShort(SECTOR_BYTES.toShort())
            put(SECTORS_PER_CLUSTER.toByte())
            putShort(RESERVED_SECTORS.toShort())
            put(2)                                     // Mirrored FATs.
            position(21)
            put(0xf8.toByte())                          // Fixed disk media.
            position(24)
            putShort(63)
            putShort(255)
            putInt(PARTITION_START)
            putInt(geometry.partitionSectors.toInt())
            putInt(geometry.fatSectors.toInt())
            putInt(44, 2)                              // Root directory cluster.
            putShort(48, 1)                            // FSInfo sector.
            putShort(50, 6)                            // Backup boot sector.
            position(64)
            put(0x80.toByte())
            put(0)
            put(0x29)
            putInt(signature)
            putAscii(label.padEnd(11))
            putAscii("FAT32   ")
            putShort(510, 0xaa55.toShort())
        }
        val fsInfo = bytes(SECTOR_BYTES).apply {
            putInt(0, 0x41615252)
            putInt(484, 0x61417272)
            putInt(488, geometry.clusters - usedClusters)
            putInt(492, usedClusters + 2)
            putInt(508, 0xaa550000.toInt())
        }

        image.write(mbr.array())
        for (sector in listOf(PARTITION_START, PARTITION_START + 6)) {
            image.seek(sector.toLong() * SECTOR_BYTES)
            image.write(boot.array())
            image.write(fsInfo.array())
        }
    }

    private fun writeFat(
        image: RandomAccessFile,
        geometry: Geometry,
        entries: List<Entry>,
        checkCancelled: () -> Unit
    ) {
        val buffer = bytes(64 * 1024)
        for (copy in 0..1) {
            image.seek(geometry.fatOffset + copy * geometry.fatSectors * SECTOR_BYTES)
            buffer.clear()
            buffer.putInt(0x0ffffff8)
            buffer.putInt(END_OF_CHAIN)
            for (entry in entries) {
                val end = entry.firstCluster + entry.clusterCount - 1
                for (cluster in entry.firstCluster..end) {
                    if (!buffer.hasRemaining()) {
                        checkCancelled()
                        image.write(buffer.array())
                        buffer.clear()
                    }
                    buffer.putInt(if (cluster == end) END_OF_CHAIN else cluster + 1)
                }
            }
            image.write(buffer.array(), 0, buffer.position())
        }
    }

    private fun writeDirectory(image: RandomAccessFile, entry: Entry, label: String) {
        val parent = entry.parent
        if (parent == null) {
            image.write(shortEntry(label.padEnd(11), VOLUME_LABEL))
        } else {
            image.write(shortEntry(".".padEnd(11), DIRECTORY, entry.firstCluster))
            image.write(shortEntry("..".padEnd(11), DIRECTORY, if (parent.parent == null) 0 else parent.firstCluster))
        }
        for (child in entry.children) {
            val count = child.longNameCount
            val checksum = child.shortName.fold(0) { sum, char ->
                (((sum and 1) shl 7) + (sum ushr 1) + char.code) and 0xff
            }
            for (index in count downTo 1) {
                val record = bytes(ENTRY_BYTES)
                record.put(0, (if (index == count) index or 0x40 else index).toByte())
                record.put(11, 0x0f)
                record.put(13, checksum.toByte())
                for ((i, offset) in longNameOffsets.withIndex()) {
                    val charIndex = (index - 1) * LONG_NAME_CHARS + i
                    val code = when {
                        charIndex < child.file.name.length -> child.file.name[charIndex].code
                        charIndex == child.file.name.length -> 0
                        else -> 0xffff
                    }
                    record.putShort(offset, code.toShort())
                }
                image.write(record.array())
            }
            val attributes = if (child.isDirectory) DIRECTORY else ARCHIVE
            image.write(shortEntry(child.shortName, attributes, child.firstCluster, child.size))
        }
    }

    private fun shortEntry(name: String, attributes: Int, cluster: Int = 0, size: Long = 0): ByteArray =
        bytes(ENTRY_BYTES).apply {
            putAscii(name)
            put(attributes.toByte())
            putShort(16, 0x21)                          // 1980-01-01.
            putShort(18, 0x21)
            putShort(20, (cluster ushr 16).toShort())
            putShort(24, 0x21)
            putShort(26, cluster.toShort())
            putInt(28, size.toInt())
        }.array()

    private fun copyFile(image: RandomAccessFile, entry: Entry, buffer: ByteArray, checkCancelled: () -> Unit) {
        Files.newInputStream(entry.file.toPath(), NOFOLLOW_LINKS).use { input ->
            var remaining = entry.size
            while (remaining > 0) {
                checkCancelled()
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read == -1) break
                image.write(buffer, 0, read)
                remaining -= read
            }
            if (remaining > 0 || input.read() != -1) {
                throw IOException("${entry.file.name} changed during import. Try again.")
            }
        }
    }

    private fun bytes(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(LITTLE_ENDIAN)
    private fun ByteBuffer.putAscii(value: String) = put(value.toByteArray(Charsets.US_ASCII))
}
