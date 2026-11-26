package skidworks.app.simpleboot.util

import android.content.Context
import org.json.JSONObject

object MountStateStore {
    private fun preferences(context: Context) =
        context.getSharedPreferences("SimpleBootPrefs", Context.MODE_PRIVATE)

    data class MountInfo(
        val filePath: String,
        val loopDevice: String,
        val mountTime: Long,
        val lun: String,
        val method: MountMethod? = null,
        val bootId: String? = null,
        val loopBackingFile: String? = null,
        val gadget: String? = null,
        val ownedGadget: Boolean = false,
        val usbConfiguration: UsbConfiguration? = null,
        val legacyFunctions: String? = null,
        val legacyEnabled: String? = null,
        val recoverUsb: Boolean = false,
        val readOnly: Boolean = true,
        val legacyCdrom: String? = null,
        val legacyReadOnly: String? = null
    )

    data class UsbConfiguration(
        val path: String,
        val udc: String,
        val vendorId: String,
        val productId: String,
        val links: Map<String, String>
    )

    fun save(context: Context, state: MountInfo): Boolean {
        val config = state.usbConfiguration
        return preferences(context).edit()
            .putString("mounted_file_path", state.filePath)
            .putString("loop_device", state.loopDevice)
            .putString("loop_backing_file", state.loopBackingFile)
            .putLong("mount_time", state.mountTime)
            .putString("lun_used", state.lun)
            .putString("mount_method", state.method?.name)
            .putString("boot_id", state.bootId)
            .putString("gadget", state.gadget)
            .putBoolean("owned_gadget", state.ownedGadget)
            .putString("usb_config_path", config?.path)
            .putString("usb_config_udc", config?.udc)
            .putString("usb_vendor_id", config?.vendorId)
            .putString("usb_product_id", config?.productId)
            .putString("usb_function_links", config?.let { JSONObject(it.links).toString() })
            .putString("legacy_functions", state.legacyFunctions)
            .putString("legacy_enabled", state.legacyEnabled)
            .putBoolean("recover_usb", state.recoverUsb)
            .putBoolean("read_only", state.readOnly)
            .putString("legacy_cdrom", state.legacyCdrom)
            .putString("legacy_read_only", state.legacyReadOnly)
            .commit()
    }

    fun load(context: Context): MountInfo? {
        val prefs = preferences(context)
        val filePath = prefs.getString("mounted_file_path", null) ?: return null
        val method = prefs.getString("mount_method", null)
        val config = prefs.getString("usb_config_path", null)?.let { path ->
            val links = JSONObject(prefs.getString("usb_function_links", "{}").orEmpty())
            UsbConfiguration(
                path = path,
                udc = prefs.getString("usb_config_udc", "").orEmpty(),
                vendorId = prefs.getString("usb_vendor_id", "").orEmpty(),
                productId = prefs.getString("usb_product_id", "").orEmpty(),
                links = links.keys().asSequence().associateWith(links::getString)
            )
        }
        return MountInfo(
            filePath = filePath,
            loopDevice = prefs.getString("loop_device", "").orEmpty(),
            mountTime = prefs.getLong("mount_time", 0),
            lun = prefs.getString("lun_used", null)?.toIntOrNull()?.takeIf { it >= 0 }?.toString() ?: "0",
            method = MountMethod.entries.firstOrNull { it.name == method },
            bootId = prefs.getString("boot_id", null),
            loopBackingFile = prefs.getString("loop_backing_file", null),
            gadget = prefs.getString("gadget", null),
            ownedGadget = prefs.getBoolean("owned_gadget", false),
            usbConfiguration = config,
            legacyFunctions = prefs.getString("legacy_functions", null),
            legacyEnabled = prefs.getString("legacy_enabled", null),
            recoverUsb = prefs.getBoolean("recover_usb", false),
            readOnly = prefs.getBoolean("read_only", true),
            legacyCdrom = prefs.getString("legacy_cdrom", null),
            legacyReadOnly = prefs.getString("legacy_read_only", null)
        )
    }

    fun clear(context: Context): Boolean = preferences(context).edit().clear().commit()
}
