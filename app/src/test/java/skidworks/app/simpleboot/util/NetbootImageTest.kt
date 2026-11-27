package skidworks.app.simpleboot.util

import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetbootImageTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun matchesUefiFallbackNameToTargetArchitecture() {
        val payload = temp.newFile()
        payload.writeBytes(efiHeader(machine = 0x8664))
        assertEquals("BOOTX64.EFI", NetbootImage.fallbackName(payload))

        payload.writeBytes(efiHeader(machine = 0xaa64))
        assertEquals("BOOTAA64.EFI", NetbootImage.fallbackName(payload))
    }

    @Test
    fun rejectsPeDriversAndTruncatedHeaders() {
        val payload = temp.newFile()
        payload.writeBytes(efiHeader(machine = 0x8664, subsystem = 11))
        assertThrows(IOException::class.java) { NetbootImage.fallbackName(payload) }

        payload.writeBytes(efiHeader(machine = 0x8664).copyOf(120))
        assertThrows(IOException::class.java) { NetbootImage.fallbackName(payload) }
    }

    @Test
    fun packagesEfiAndKeepsPrebuiltUsbImageBytes() {
        val workDir = temp.newFolder()
        val payload = temp.newFile()
        val target = temp.newFile()
        val efi = URI("https://archlinux.org/static/netboot/ipxe-arch.efi")
        payload.writeBytes(efiHeader(machine = 0x8664))

        NetbootImage.write(efi, efi, payload, workDir, target) {}
        assertEquals("ipxe-arch", NetbootImage.imageName(efi, efi))
        assertArrayEquals(payload.readBytes(), workDir.resolve("root/EFI/BOOT/BOOTX64.EFI").readBytes())
        assertTrue(target.length() > 512)

        val usb = URI("https://example.net/netboot.usb")
        val disk = ByteArray(512).apply {
            this[510] = 0x55
            this[511] = 0xaa.toByte()
        }
        payload.writeBytes(disk)
        NetbootImage.write(usb, usb, payload, workDir, target) {}
        assertArrayEquals(disk, target.readBytes())
    }

    @Test
    fun reportsLegacyLoaderAndRejectsNonHttpsLinks() {
        val bootFile = temp.newFile().apply { writeText("legacy loader fixture") }
        val target = temp.newFile()
        val pxe = URI("https://example.net/undionly.kpxe")

        val error = assertThrows(IOException::class.java) {
            NetbootImage.write(pxe, pxe, bootFile, temp.newFolder(), target) {}
        }

        assertTrue(error.message.orEmpty().contains("PXE server"))
        assertEquals(0L, target.length())
        assertNull(NetbootImage.httpsUri("http://example.net/ipxe.efi"))
        assertNull(NetbootImage.httpsUri("https://user@example.net/ipxe.efi"))
        assertEquals(pxe, NetbootImage.httpsUri("  $pxe  "))
    }

    private fun efiHeader(machine: Int, subsystem: Int = 10): ByteArray =
        ByteBuffer.allocate(512).order(LITTLE_ENDIAN).apply {
            putShort(0, 0x5a4d)
            putInt(0x3c, 64)
            putInt(64, 0x4550)
            putShort(68, machine.toShort())
            putShort(84, 112)
            putShort(88, 0x20b)
            putShort(156, subsystem.toShort())
        }.array()
}
