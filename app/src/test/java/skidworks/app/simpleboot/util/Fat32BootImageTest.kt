package skidworks.app.simpleboot.util

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Fat32BootImageTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun writesPartitionedImageWithMirroredFatAndRequestedLabel() {
        val source = temp.newFolder("source")
        addEfiLoader(source)
        val target = temp.newFile("bootable.img")

        Fat32BootImage.write(source, target, "ARCH_202611") {}

        RandomAccessFile(target, "r").use { image ->
            val mbr = image.readSector(0)
            val partitionStart = mbr.getInt(454).toLong()
            val partitionSectors = mbr.getInt(458).toLong()
            assertEquals(0xaa55.toShort(), mbr.getShort(510))
            assertEquals(0x0c.toByte(), mbr.get(450))
            assertEquals(2048L, partitionStart)
            assertEquals((partitionStart + partitionSectors) * 512, image.length())

            val boot = image.readSector(partitionStart)
            assertEquals(512.toShort(), boot.getShort(11))
            assertEquals(partitionStart, boot.getInt(28).toLong())
            assertEquals(partitionSectors, boot.getInt(32).toLong())
            assertEquals("ARCH_202611", String(boot.array(), 71, 11, Charsets.US_ASCII))
            assertEquals(boot, image.readSector(partitionStart + boot.getShort(50)))

            val firstFat = partitionStart + boot.getShort(14)
            val secondFat = firstFat + boot.getInt(36)
            assertEquals(image.readSector(firstFat), image.readSector(secondFat))
        }
    }

    @Test
    fun rejectsFolderWithoutRemovableMediaBootloader() {
        val source = temp.newFolder("source")
        val target = temp.newFile("bootable.img")

        val failure = assertThrows(IOException::class.java) {
            Fat32BootImage.write(source, target, "LINUX") {}
        }

        assertTrue(failure.message.orEmpty().contains("EFI/BOOT"))
        assertEquals(0L, target.length())
    }

    @Test
    fun rejectsOversizedWindowsImageBeforeWriting() {
        val source = temp.newFolder("source")
        addEfiLoader(source)
        val sources = File(source, "sources").apply { mkdirs() }
        RandomAccessFile(File(sources, "install.wim"), "rw").use { it.setLength(1L shl 32) }
        val target = temp.newFile("bootable.img")

        val failure = assertThrows(IOException::class.java) {
            Fat32BootImage.write(source, target, "WINDOWS") {}
        }

        assertTrue(failure.message.orEmpty().contains("install.swm"))
        assertEquals(0L, target.length())
    }

    private fun addEfiLoader(source: File) {
        val bootDirectory = File(source, "efi/boot").apply { mkdirs() }
        File(bootDirectory, "BootX64.efi").writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun RandomAccessFile.readSector(sector: Long): ByteBuffer {
        seek(sector * 512)
        val bytes = ByteArray(512)
        readFully(bytes)
        return ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
    }
}
