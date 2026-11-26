# Root and Android 17 verification

Build with Android Studio's configured Gradle JDK and Android SDK 37:
`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.

On a physical Android 17 device with Magisk 31.1 or newer:

1. Unmount any image using the old package before installing the new app.
2. Install the debug APK (package skidworks.app.simpleboot). Leave its Magisk
   grant disabled. Launch: the root guidance screen should remain usable.
3. Tap Retry while denied: remain on the guidance screen without closing the app.
4. In Magisk's Superuser tab, enable the new SimpleBoot package. Return to
   SimpleBoot: it should detect access, replacing the cached non-root shell.
5. Grant all-files access; return from Settings and confirm ISO discovery.
6. With USB debugging enabled and File Transfer selected, confirm that the host
   can see the device in `adb devices` and transfer files over MTP. Mount an ISO
   using ConfigFS and confirm that it appears as USB storage. Unmount it and
   verify that both ADB and MTP return without a reboot or cable reconnect.
   Repeat with USB debugging disabled and confirm it stays disabled.
   Verify on the Samsung Galaxy S22 Ultra with KernelSU and One UI 7 from issue #2
   when available. Check that loopback mounting and unmounting leave ADB and MTP
   connected, and that repeated taps do not overlap jobs.
7. Rotate/resize during setup and verify root checks and layout remain usable.
8. Export logs and verify the receiving app can read the shared file URI.

Also check a non-root device (guidance stays visible), a manager with an interactive
grant prompt (allow and deny), and Android 11 as the minimum supported release.

Build/lint checks do not establish root grant or USB gadget compatibility.
