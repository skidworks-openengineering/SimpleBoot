> [!IMPORTANT]
> **Magisk 31.1+:** Open Magisk → Superuser and grant SimpleBoot access before using it.
> Magisk no longer displays an automatic permission prompt. Return to SimpleBoot and
> tap **Retry root access** if needed. Older root managers may still show a prompt.
>
> SimpleBoot now uses the package `skidworks.app.simpleboot`. Android treats it as
> a separate app: grant root and all-files access again. Private preferences and
> mount state from `redhead.app.simpleboot` do not transfer. Unmount images in the
> old app before switching; shared `SimpleBootISOs` and `SimpleBootLogs` directories
> remain in place.
>
> The app targets Android 17 (API 37), supports Android 11+, and uses libsu 6.0.0.
> Root access does not guarantee USB mass-storage support: the device kernel and
> USB gadget configuration must support it. Physical-device validation is required.

---

# SimpleBoot – Turn your phone into a bootable USB device!
**Version:** v3.0 “Equinox - Milestone 3”  
**License:** GNU General Public License v3 (GPLv3)  
**Developer:** Skidworks + Matthew DaLuz [@matthewdaluz](https://github.com/matthewdaluz)

**SimpleBoot** is a powerful, root-required Android app that transforms your phone into a fully bootable USB device. Mount ISO/IMG files via **ConfigFS**, **Legacy mass_storage**, or raw **Loopback** mode and boot directly into live systems on any PC.

> Successor to DriveDroid and PhoneStick, built for Android 16+ with full modern kernel and ConfigFS support.

---

## 🚀 Features

- 🔍 **Automatic ISO/IMG discovery** from `/storage/emulated/0/SimpleBootISOs`
- 📦 **Three mount methods**:
  - `ConfigFS`: For most modern kernels
  - `Legacy`: For older Android USB stacks
  - `Loopback`: For direct mount w/o USB gadget (testing or chaining)
- 🔐 **Root shell integration** using [`libsu`](https://github.com/topjohnwu/libsu)
- 🧠 **Complete logging** of all mount/unmount operations (`SimpleBootLogs`)
- 🌓 **Light/Dark mode** Jetpack Compose UI
- 🛠️ **Per-ISO selection** of mount method
- 💾 **Read-only or read/write mounting** per image. Read-only is the default. CD-ROM mode is always read-only.
- 📤 Export logs and diagnostics for debugging

---

## 📸 Screenshots

<img width="160" alt="Screenshot_20261107-102741_SimpleBoot" src="https://github.com/user-attachments/assets/19d92275-fb8b-42e3-8038-85829d9e9f28" />

<img width="160" alt="Screenshot_20261107-103313_SimpleBoot" src="https://github.com/user-attachments/assets/cbaabf31-127d-4330-afd7-d326e9e83993" />


## ⚙️ Requirements

- 📱 Android 11+ (API 30 or later)
- 📲 Root access (Magisk recommended)
- 🔌 OTG-capable USB port
- 📦 Kernel support for ConfigFS (most modern AOSP-based ROMs)
- 🧪 Optional: Legacy USB gadget stack for older devices

---

## 🗂 File System Layout

- `/storage/emulated/0/SimpleBootISOs/`  
  Drop your `.iso` or `.img` files here
- `/storage/emulated/0/SimpleBootLogs/`  
  Verbose mount/unmount logs
- `/storage/emulated/0/SimpleBootLogs/mount_log_YYYYMMDD.txt`  
  Daily logs for boot diagnostics
- `/dev/block/loopX`  
  Loop device usage via `losetup` (automatic)

---

## Boot from an extracted ISO

1. Extract the full contents of the ISO directly into a folder under `/sdcard/SimpleBootables/`, for example `/sdcard/SimpleBootables/Windows10Live/`. Keep the `EFI/BOOT` directory and the rest of the source layout.
2. Tap **+ → Extracted ISO**, choose the folder, and select **Windows** or **Linux**. For Linux media that searches for a specific volume label, enter the label if it fits FAT32's 11-character limit. SimpleBoot stores volume labels in uppercase.
3. Tap **Add**. SimpleBoot writes a FAT32 disk image under `/sdcard/SimpleBootISOs/Bootables/Windows/` or `/sdcard/SimpleBootISOs/Bootables/Linux/`. The image appears in **Available Images**.
4. Mount the new entry with a USB-capable mount method, then select the phone's USB drive in your PC's UEFI boot menu. Imported images use the USB HDD mode.

The extracted media must support booting from FAT32 and include a UEFI bootloader for your PC's architecture. Some Linux distributions require the original ISO layout. FAT32 accepts individual files smaller than 4 GiB and cannot store symbolic links. For Windows media with a larger `sources/install.wim`, [split it into `install.swm` files](https://learn.microsoft.com/en-us/windows-hardware/manufacture/desktop/install-windows-from-a-usb-flash-drive) before adding the folder.

---

## Boot from a network boot link

Tap **+ → Network boot link**, paste a direct HTTPS URL, then tap **Add**. SimpleBoot saves the image under `/sdcard/SimpleBootISOs/Bootables/Netboot/`. Mount it from **Available Images** and select the phone's USB drive from the PC's boot menu.

- **UEFI application (`.efi`).** SimpleBoot places the executable at the USB boot path for its CPU architecture. For example, [Arch Linux Netboot](https://archlinux.org/releng/netboot/) offers `https://archlinux.org/static/netboot/ipxe-arch.efi` for x86-64 UEFI.
- **iPXE script.** SimpleBoot creates an x86-64 UEFI image with iPXE. At boot it uses DHCP and loads the script from its original URL, so relative resource URLs work as they do when you run the script over the network.
- **Bootable USB image (`.usb`).** SimpleBoot uses the linked image as-is. Select the firmware mode supported by that image.

Raw BIOS PXE chainloaders such as `.pxe` and `.kpxe` require a PXE environment. Use a standalone UEFI application or a prebuilt [iPXE USB disk image](https://ipxe.org/download) for USB boot. The PC needs network access for any files fetched during boot.

---

## 💻 How It Works

1. You select an ISO from the UI
2. You choose the desired **mount method**
3. SimpleBoot sets up the USB gadget using ConfigFS or legacy nodes
4. It attaches the loop device and configures the gadget as a **bootable CD-ROM**
5. Your PC sees it as a USB boot drive — boot away!

---

## 📦 Mount Methods Explained

| Method     | Description                                                                 |
|------------|-----------------------------------------------------------------------------|
| `ConfigFS` | Modern gadget system. Uses `/config/usb_gadget/...`. Required for Android 11+ |
| `Legacy`   | Uses `/sys/class/android_usb/android0/` and `f_mass_storage`. Older devices |
| `Loopback` | Mounts ISO to `/dev/block/loop7` only (no USB exposure). For dev/testing    |

---

## ⚠️ Disclaimers

- 📛 This app **requires root** and **full filesystem access**
- 🧱 Misconfiguration or unsupported kernels may cause boot failures or USB stack issues
- ⚡ SimpleBoot tries to restore ADB/charging state on every unmount and mount failure

---

## 🛠️ Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [libsu](https://github.com/topjohnwu/libsu)
- [Kotlin](https://kotlinlang.org/)
- [Android 11+ Permissions API](https://developer.android.com/about/versions/11/privacy/storage)

---

## 📜 License

GNU GPLv3 – see [LICENSE](./LICENSE)

---

## 💬 Contribute

Pull requests welcome for:
- Additional mount backends (e.g. FFS + userspace)
- USB mode presets (keyboard, HID)
- Multi-ISO boot chains (Ventoy-style)
- USB detection callback API (notify when PC sees device)

---

## 🙏 Credits

- **Lead Dev:** [@matthewdaluz](https://github.com/matthewdaluz)
- **AI Assistants:** Codex + GitHub Copilot
- **Special Thanks:** The open-source Android root community!

---
