package skidworks.app.simpleboot.util

import android.content.Context
import com.topjohnwu.superuser.Shell
import java.io.File
import skidworks.app.simpleboot.model.MountResult

enum class MountMethod { AUTO, CONFIGFS, LEGACY, LOOPBACK, PIXEL }

enum class UsbMode { USB_HDD, CD_ROM }

object MountController {
    private val configFsRoots = listOf("/config/usb_gadget", "/sys/kernel/config/usb_gadget")
    private const val gadgetName = "simpleboot"
    private const val legacyUsb = "/sys/class/android_usb/android0"
    private const val legacyLun = "$legacyUsb/f_mass_storage/lun"
    private val loopPath = Regex("/dev/(?:block/)?loop[0-9]+")
    private const val bootIdPath = "/proc/sys/kernel/random/boot_id"

    @Synchronized
    fun mount(
        context: Context,
        isoPath: String,
        method: MountMethod,
        mode: UsbMode = UsbMode.USB_HDD,
        lun: Int = 0,
        readOnly: Boolean = true
    ): MountResult {
        if (!readOnly && mode == UsbMode.CD_ROM && method != MountMethod.LOOPBACK) {
            return MountResult(false, "CD-ROM is read-only. Select USB HDD for read/write access.")
        }
        if (!File(isoPath).isFile) return MountResult(false, "File does not exist.")
        if (lun < 0) return MountResult(false, "Invalid LUN.")
        if (!Shell.getShell().isRoot) return MountResult(false, "Root access is required.")
        val bootId = read(bootIdPath) ?: return MountResult(false, "Could not identify this boot.")
        if (currentMount(context) != null) {
            return MountResult(false, "Unmount the current image before mounting another.")
        }

        val methods = if (method == MountMethod.AUTO) {
            listOf(MountMethod.CONFIGFS, MountMethod.LEGACY, MountMethod.LOOPBACK)
        } else {
            listOf(method)
        }
        var error = ""
        for (backend in methods) {
            try {
                val state = MountStateStore.MountInfo(
                    filePath = isoPath,
                    loopDevice = "",
                    mountTime = System.currentTimeMillis(),
                    lun = lun.toString(),
                    method = backend,
                    bootId = bootId,
                    readOnly = readOnly
                )
                when (backend) {
                    MountMethod.CONFIGFS, MountMethod.PIXEL -> mountConfigFs(context, state, mode)
                    MountMethod.LEGACY -> mountLegacy(context, state, mode)
                    MountMethod.LOOPBACK -> attachLoop(context, state)
                    MountMethod.AUTO -> error("AUTO must resolve to a concrete mount method")
                }
                val loop = MountStateStore.load(context)?.loopDevice?.ifEmpty { null }
                LogManager.logMount(context, File(isoPath).name, loop ?: backend.name)
                return MountResult(true, "Mounted using ${backend.name}.", loop)
            } catch (failure: MountFailure) {
                error = failure.message.orEmpty()
                LogManager.logToFile(context, "[mount] $backend: $error")
                val state = MountStateStore.load(context)
                if (state != null) {
                    val rollback = release(context, state)
                    if (!rollback.success) return MountResult(false, "$error ${rollback.message}")
                }
            }
        }
        return MountResult(false, error)
    }

    @Synchronized
    fun unmount(context: Context): MountResult {
        if (!Shell.getShell().isRoot) return MountResult(false, "Root access is required.")
        val state = currentMount(context) ?: return MountResult(false, "Nothing is currently mounted.")
        return release(context, state)
    }

    @Synchronized
    fun currentMount(context: Context): MountStateStore.MountInfo? {
        val state = MountStateStore.load(context) ?: return null
        val bootId = read(bootIdPath) ?: return state
        if (state.bootId == null || state.bootId == bootId) return state
        return if (MountStateStore.clear(context)) null else state
    }

    private fun mountConfigFs(context: Context, state: MountStateStore.MountInfo, mode: UsbMode) {
        run("modprobe libcomposite")
        run("modprobe usb_f_mass_storage")
        if (configFsRoots.none(::isDirectory)) {
            run("mount -t configfs configfs /sys/kernel/config")
        }

        val gadgets = configFsRoots.flatMap(::children).filter { isDirectory("$it/configs") }
        val bound = gadgets.firstOrNull { !read("$it/UDC").isNullOrEmpty() }
        val existing = bound ?: gadgets.firstOrNull()
        val root = configFsRoots.firstOrNull(::isDirectory)
            ?: throw MountFailure("ConfigFS is unavailable.")
        val previousUdc = existing?.let { read("$it/UDC") }
        if (existing != null && previousUdc == null) {
            throw MountFailure("Could not read Android's USB controller binding.")
        }
        val udc = previousUdc?.ifEmpty { null } ?: output("getprop sys.usb.controller")
            ?.takeIf { it.isNotEmpty() && isDirectory("/sys/class/udc/$it") }
            ?: children("/sys/class/udc").firstOrNull()?.substringAfterLast('/')
            ?: throw MountFailure("No USB device controller found.")

        val gadget = existing ?: "$root/$gadgetName"
        val ownedGadget = existing == null
        val function = "$gadget/functions/mass_storage.simpleboot"
        if (isDirectory(function) || File(existing.orEmpty()).name == gadgetName) {
            throw MountFailure("SimpleBoot's USB gadget already exists.")
        }
        val config = if (ownedGadget) "$gadget/configs/b.1" else {
            children("$gadget/configs").minByOrNull { it.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE }
                ?: throw MountFailure("Android's USB gadget has no configuration.")
        }

        val saved = state.copy(gadget = gadget, ownedGadget = ownedGadget)
        save(context, saved)
        if (ownedGadget) {
            checked("Create the USB gadget",
                "mkdir ${quote(gadget)}",
                "mkdir ${quote("$gadget/strings/0x409")} ${quote(config)}",
                write("$gadget/strings/0x409/manufacturer", "SimpleBoot"),
                write("$gadget/strings/0x409/product", "SimpleBoot USB"),
                write("$gadget/strings/0x409/serialnumber", "SB${state.mountTime}"))
        }
        checked("Create the mass-storage function", "mkdir ${quote(function)}")

        val lun = when {
            state.lun == "0" && isDirectory("$function/lun") -> "$function/lun"
            else -> "$function/lun.${state.lun}"
        }
        if (!isDirectory(lun)) checked("Create the USB LUN", "mkdir ${quote(lun)}")
        configureLun(lun, mode, state.readOnly)
        val attached = run(write("$lun/file", state.filePath)).isSuccess
        // The gadget can open a backing file read-only even when ro was set to 0.
        if (!attached || (!state.readOnly && read("$lun/ro") == "1")) {
            if (attached) checked("Release the read-only USB image", write("$lun/file", ""))
            checked("Attach the image to the USB LUN", write("$lun/file", attachLoop(context, saved)))
        }
        verifyLunAccess(lun, state.readOnly)
        val active = MountStateStore.load(context) ?: throw MountFailure("Mount state is missing.")
        val snapshot = MountStateStore.UsbConfiguration(
            path = config,
            udc = read("$gadget/UDC") ?: throw MountFailure("Could not save the USB controller binding."),
            vendorId = read("$gadget/idVendor") ?: throw MountFailure("Could not save the USB vendor ID."),
            productId = read("$gadget/idProduct") ?: throw MountFailure("Could not save the USB product ID."),
            links = functionLinks(config)
        )
        val storageLink = "$config/simpleboot"
        if (storageLink in snapshot.links) throw MountFailure("The SimpleBoot USB function link already exists.")
        save(context, active.copy(usbConfiguration = snapshot))

        // ConfigFS function instances (including ffs.adb) stay in place.
        disconnectGadget(gadget)
        for (link in snapshot.links.keys) {
            checked("Unlink the Android USB function", "rm ${quote(link)}")
        }
        checked("Connect the USB mass-storage gadget",
            write("$gadget/idVendor", "0x18d1"),
            write("$gadget/idProduct", "0x4e21"),
            "ln -s ${quote(function)} ${quote(storageLink)}",
            write("$gadget/UDC", snapshot.udc.ifEmpty { udc }))
    }

    private fun mountLegacy(context: Context, state: MountStateStore.MountInfo, mode: UsbMode) {
        val functions = read("$legacyUsb/functions") ?: throw MountFailure("Legacy USB is unavailable.")
        val enabled = read("$legacyUsb/enable") ?: throw MountFailure("Legacy USB is unavailable.")
        if (read("$legacyLun/file") != "") throw MountFailure("The legacy USB LUN is unavailable or busy.")
        val legacyState = state.copy(
            legacyFunctions = functions,
            legacyEnabled = enabled,
            legacyCdrom = read("$legacyLun/cdrom"),
            legacyReadOnly = read("$legacyLun/ro")
        )
        val loop = attachLoop(context, legacyState)
        checked("Select legacy USB mass storage",
            write("$legacyUsb/enable", "0"),
            write("$legacyUsb/functions", "mass_storage"))
        configureLun(legacyLun, mode, state.readOnly)
        checked("Attach the image to the legacy USB LUN", write("$legacyLun/file", loop))
        verifyLunAccess(legacyLun, state.readOnly)
        checked("Connect legacy USB mass storage", write("$legacyUsb/enable", "1"))
    }

    private fun configureLun(lun: String, mode: UsbMode, readOnly: Boolean) {
        checked("Configure the USB LUN",
            write("$lun/cdrom", if (mode == UsbMode.CD_ROM) "1" else "0"),
            write("$lun/ro", if (readOnly) "1" else "0"))
        if (run("test -e ${quote("$lun/removable")}").isSuccess) {
            checked("Set removable media", write("$lun/removable", "1"))
        }
    }

    private fun verifyLunAccess(lun: String, readOnly: Boolean) {
        if (!readOnly && read("$lun/ro") != "0") {
            throw MountFailure("The USB gadget could not open the image for writing.")
        }
    }

    private fun attachLoop(context: Context, state: MountStateStore.MountInfo): String {
        run("modprobe loop")
        val losetup = findLosetup()
        val loop = output("$losetup -f")?.takeIf(loopPath::matches)
            ?: throw MountFailure("No available loop device.")
        save(context, state.copy(loopDevice = loop))
        val accessFlag = if (state.readOnly) " -r" else ""
        checked("Attach the loop device", "$losetup$accessFlag ${quote(loop)} ${quote(state.filePath)}")
        val backing = loopBackingFile(loop)
        if (backing != null) save(context, state.copy(loopDevice = loop, loopBackingFile = backing))
        if (!state.readOnly && read("/sys/block/${File(loop).name}/ro") != "0") {
            throw MountFailure("The loop device could not open the image for writing.")
        }
        return loop
    }

    private fun release(context: Context, state: MountStateStore.MountInfo): MountResult {
        return try {
            when (state.method) {
                MountMethod.CONFIGFS, MountMethod.PIXEL -> releaseConfigFs(context, state)
                MountMethod.LEGACY -> releaseLegacy(state)
                MountMethod.LOOPBACK -> Unit
                null -> releaseOlderMount(context, state)
                MountMethod.AUTO -> throw MountFailure("Unknown saved mount backend.")
            }
            detachLoop(state)
            if (!MountStateStore.clear(context)) throw MountFailure("Could not clear mount state.")
            LogManager.logUnmount(context, File(state.filePath).name, state.loopDevice)
            MountResult(true, "Unmount successful.")
        } catch (failure: MountFailure) {
            LogManager.logToFile(context, "[unmount] ${failure.message}")
            MountResult(false, "${failure.message} Retry unmount to finish cleanup.")
        }
    }

    private fun releaseConfigFs(context: Context, state: MountStateStore.MountInfo) {
        val gadget = state.gadget ?: throw MountFailure("The saved USB gadget path is missing.")
        if (state.ownedGadget && !isDirectory(gadget)) return
        val function = "$gadget/functions/mass_storage.simpleboot"
        state.usbConfiguration?.let { snapshot ->
            val storageLink = "${snapshot.path}/simpleboot"
            val currentLinks = functionLinks(snapshot.path)
            val currentUdc = read("$gadget/UDC")
                ?: throw MountFailure("Could not read the USB controller binding.")
            // Once Android has taken over again, leave its active configuration alone.
            val needsRestore = storageLink in currentLinks || currentUdc.isEmpty()
            if (needsRestore) {
                disconnectGadget(gadget)
                checked("Unlink the SimpleBoot USB function", "rm -f ${quote(storageLink)}")
                for ((link, target) in snapshot.links) {
                    if (currentLinks[link] != target) {
                        checked("Restore the Android USB function",
                            "rm -f ${quote(link)}", "ln -s ${quote(target)} ${quote(link)}")
                    }
                }
                if (read("$gadget/idVendor") != snapshot.vendorId) {
                    checked("Restore Android's USB vendor ID", write("$gadget/idVendor", snapshot.vendorId))
                }
                if (read("$gadget/idProduct") != snapshot.productId) {
                    checked("Restore Android's USB product ID", write("$gadget/idProduct", snapshot.productId))
                }
                if (snapshot.udc.isNotEmpty()) {
                    checked("Reconnect Android's USB gadget", write("$gadget/UDC", snapshot.udc))
                }
            }
            save(context, state.copy(usbConfiguration = null))
        }

        val lun = "$function/lun.${state.lun}"
        val file = if (state.lun == "0" && isDirectory("$function/lun")) "$function/lun/file" else "$lun/file"
        if (!read(file).isNullOrEmpty()) checked("Release the USB image", write(file, ""))
        checked("Remove the SimpleBoot USB function",
            removeDirectory(lun, optionalDefault = state.lun == "0"),
            removeDirectory(function))
        if (state.ownedGadget) {
            checked("Remove the SimpleBoot USB gadget",
                removeDirectory("$gadget/configs/b.1"),
                removeDirectory("$gadget/strings/0x409"),
                removeDirectory(gadget))
        }
    }

    private fun disconnectGadget(gadget: String) {
        val udc = read("$gadget/UDC") ?: throw MountFailure("Could not read the USB controller binding.")
        if (udc.isNotEmpty()) {
            checked("Disconnect the USB gadget", write("$gadget/UDC", ""), "sleep 0.15")
        }
    }

    private fun functionLinks(config: String): Map<String, String> {
        val entries = output("ls -1 ${quote(config)}")
            ?: throw MountFailure("Could not read the USB configuration.")
        val links = mutableMapOf<String, String>()
        for (entry in entries.lineSequence().filter(String::isNotEmpty)) {
            val path = "$config/$entry"
            if (run("test -L ${quote(path)}").isSuccess) {
                links[path] = output("readlink -f ${quote(path)}")
                    ?: throw MountFailure("Could not read the USB function link $path.")
            }
        }
        return links
    }

    private fun releaseLegacy(state: MountStateStore.MountInfo) {
        val functions = state.legacyFunctions ?: throw MountFailure("Saved legacy USB functions are missing.")
        val enabled = state.legacyEnabled ?: throw MountFailure("Saved legacy USB state is missing.")
        val backing = read("$legacyLun/file")
        val ownsBacking = isBackingFile(backing, state)
        if (!backing.isNullOrEmpty() && !ownsBacking) {
            throw MountFailure("The legacy USB LUN is in use by another mount.")
        }
        val cdrom = state.legacyCdrom
        val readOnly = state.legacyReadOnly
        val mediaChanged = (cdrom != null && read("$legacyLun/cdrom") != cdrom) ||
            (readOnly != null && read("$legacyLun/ro") != readOnly)
        if (ownsBacking || read("$legacyUsb/functions") != functions || mediaChanged) {
            checked("Disable legacy USB", write("$legacyUsb/enable", "0"))
            if (ownsBacking) checked("Release the legacy USB image", write("$legacyLun/file", ""))
            if (cdrom != null) checked("Restore legacy CD-ROM mode", write("$legacyLun/cdrom", cdrom))
            if (readOnly != null) checked("Restore legacy write protection", write("$legacyLun/ro", readOnly))
            checked("Restore legacy USB functions", write("$legacyUsb/functions", functions))
        }
        if (read("$legacyUsb/enable") != enabled) {
            checked("Restore legacy USB connection", write("$legacyUsb/enable", enabled))
        }
    }

    private fun releaseOlderMount(context: Context, state: MountStateStore.MountInfo) {
        val functionNames = setOf("mass_storage.0", "mass_storage.usb0", "msc.0")
        for (gadget in configFsRoots.flatMap(::children)) {
            for (function in children("$gadget/functions").filter { File(it).name in functionNames }) {
                val lun = listOf("$function/lun.${state.lun}", "$function/lun")
                    .firstOrNull { isBackingFile(read("$it/file"), state) } ?: continue
                save(context, state.copy(recoverUsb = true))
                checked("Disconnect the older USB mount",
                    "if [ -n \"$(cat ${quote("$gadget/UDC")})\" ]; then ${write("$gadget/UDC", "")}; fi")
                val target = output("readlink -f ${quote(function)}")
                for (link in children("$gadget/configs").flatMap(::children)) {
                    if (target != null && run("test -L ${quote(link)}").isSuccess &&
                        output("readlink -f ${quote(link)}") == target) {
                        checked("Remove the older mass-storage link", "rm ${quote(link)}")
                    }
                }
                checked("Release the older USB LUN", write("$lun/file", ""))
                restoreOlderUsb()
                return
            }
        }
        if (isBackingFile(read("$legacyLun/file"), state)) {
            save(context, state.copy(recoverUsb = true))
            checked("Disconnect the older legacy USB mount",
                write("$legacyUsb/enable", "0"), write("$legacyLun/file", ""))
            restoreOlderUsb()
        } else if (state.recoverUsb) {
            restoreOlderUsb()
        }
    }

    private fun restoreOlderUsb() {
        // Earlier versions did not save the original mode. Reapply Android's current policy.
        val normalFunctions = setOf("mtp", "ptp", "rndis", "midi", "ncm", "uvc")
        val functions = output("svc usb getFunctions 2>&1")
            ?: throw MountFailure("Could not read Android's USB functions.")
        val framework = functions.split(',').filter { it in normalFunctions }.joinToString(",")
        checked("Reset Android USB functions", "svc usb setFunctions", "sleep 1")
        if (framework.isNotEmpty()) {
            checked("Restore Android USB functions", "svc usb setFunctions ${quote(framework)}")
        }
    }

    private fun detachLoop(state: MountStateStore.MountInfo) {
        val loop = state.loopDevice
        if (!loopPath.matches(loop)) return
        val backing = loopBackingFile(loop) ?: return
        val expected = state.loopBackingFile ?: output("readlink -f ${quote(state.filePath)}")
        if (backing == state.filePath || backing == expected) {
            checked("Detach the loop device", "${findLosetup()} -d ${quote(loop)}")
        }
    }

    private fun loopBackingFile(loop: String): String? = read("/sys/block/${File(loop).name}/loop/backing_file")

    private fun isBackingFile(backing: String?, state: MountStateStore.MountInfo): Boolean {
        return !backing.isNullOrEmpty() && (backing == state.filePath || backing == state.loopDevice)
    }

    private fun findLosetup(): String {
        return listOf("losetup", "toybox losetup", "busybox losetup").firstOrNull {
            val result = run("$it -h")
            (result.out + result.err).any { line -> line.contains("usage:", ignoreCase = true) }
        } ?: throw MountFailure("No losetup binary found.")
    }



    private fun children(path: String): List<String> =
        output("ls -1 ${quote(path)}").orEmpty().lines().filter { it.isNotEmpty() }.map { "$path/$it" }

    private fun read(path: String): String? = output("cat ${quote(path)}")
    private fun isDirectory(path: String): Boolean = run("test -d ${quote(path)}").isSuccess

    private fun removeDirectory(path: String, optionalDefault: Boolean = false): String {
        val remove = "rmdir ${quote(path)}"
        return "if [ -d ${quote(path)} ]; then $remove${if (optionalDefault) " 2>/dev/null || true" else ""}; fi"
    }

    private fun write(path: String, value: String): String = "printf '%s\\n' ${quote(value)} > ${quote(path)}"
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    private fun run(command: String): Shell.Result = Shell.cmd(command).exec()

    private fun output(command: String): String? {
        val result = run(command)
        return if (result.isSuccess) result.out.joinToString("\n").trimEnd() else null
    }

    private fun checked(action: String, vararg commands: String) {
        val result = run(commands.joinToString(" && "))
        if (!result.isSuccess) {
            val detail = (result.err + result.out).joinToString("\n").trim()
            throw MountFailure(if (detail.isEmpty()) "$action failed." else "$action failed: $detail")
        }
    }

    private fun save(context: Context, state: MountStateStore.MountInfo) {
        if (!MountStateStore.save(context, state)) throw MountFailure("Could not save mount state.")
    }

    private class MountFailure(message: String) : Exception(message)
}
