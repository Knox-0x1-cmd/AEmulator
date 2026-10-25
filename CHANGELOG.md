# AEmulator 0.0.0.9

**🇬🇧 English** · [🇷🇺 Русский](docs/i18n/CHANGELOG.ru.md) · [🇺🇦 Українська](docs/i18n/CHANGELOG.uk.md) · [🇩🇪 Deutsch](docs/i18n/CHANGELOG.de.md) · [🇫🇷 Français](docs/i18n/CHANGELOG.fr.md) · [🇪🇸 Español](docs/i18n/CHANGELOG.es.md) · [🇧🇷 Português](docs/i18n/CHANGELOG.pt-BR.md) · [🇮🇹 Italiano](docs/i18n/CHANGELOG.it.md) · [🇵🇱 Polski](docs/i18n/CHANGELOG.pl.md) · [🇹🇷 Türkçe](docs/i18n/CHANGELOG.tr.md) · [🇸🇦 العربية](docs/i18n/CHANGELOG.ar.md) · [🇮🇷 فارسی](docs/i18n/CHANGELOG.fa.md) · [🇮🇳 हिन्दी](docs/i18n/CHANGELOG.hi.md) · [🇮🇩 Indonesia](docs/i18n/CHANGELOG.id.md) · [🇻🇳 Tiếng Việt](docs/i18n/CHANGELOG.vi.md) · [🇨🇳 简体中文](docs/i18n/CHANGELOG.zh-CN.md) · [🇯🇵 日本語](docs/i18n/CHANGELOG.ja.md) · [🇰🇷 한국어](docs/i18n/CHANGELOG.ko.md)

### Security
- Import: boot/recovery/OTA reads are capped, the extracted tree is limited (24 GB, 1M files, 500k zip entries), and symlinks that resolve outside the image are dropped. Permissions from an `updater-script` can no longer walk out of the tree.
- `TreeFixer` removes escaping symlinks before its first write, so a hostile firmware cannot redirect preparation into app storage.
- ADB sync follows links only inside the image or the shared card, refuses to push through a symlink, and validates `logcat -b` buffer names.
- The exported `MainActivity` accepts only `content://` VIEW intents; the raw `path`/`file`/`import` extras were removed, so another app cannot make AEmulator read an arbitrary file.
- Image ids are validated (`[A-Za-z0-9_-]{1,64}`); clones do not copy escaping links and point `aemu-owners.jar` at their own ownership table.

### Updates and CI
- The updater reads this fork's releases, picks the APK for the installed flavour, requires and verifies `SHA256SUMS.txt`, caps download sizes, and refuses non-HTTPS URLs.
- Unit tests run before the APK build; the build job is read-only, only the release job can write, tags must match `versionName`, and tagged releases are never cancelled.
- ADB rejects bad packet magic, oversized packets and sync chunks, and caps concurrent connections at 32; logd rotates each buffer at 8 MB.

# AEmulator 0.0.0.8

### Reliability
- Android 12/12L: the API 31 kernel now emulates `SO_PEERCRED` ids, so the 2024 zygote backport accepts `system_server` and the boot proceeds.
- Automatic guest RAM is 3584 MB on an 8 GB phone (half of total RAM at most), including the manual choices.
- The phantom vibrator VINTF declaration is removed before boot, so Android 15 no longer stalls on it.

# AEmulator 0.0.0.3

### Emulation
- New native arm64 engine: Android 5.0–17 and GSIs (8–17) run directly on the phone; Android 2.3–4.4 runs through QEMU.
- 64-bit firmware support; 32-bit programs inside them run through qemu-arm.
- Full Android 8 support, including GSIs.
- zygote and mediaserver crash much less often (binder fixes, system_server deadlock fixed).
- `/system` is read-only for the guest, so precompiled framework code is no longer lost.
- Faster first boot of Dalvik firmware (Galaxy S III: 104 s → 59 s).
- Option for hardware 32-bit execution (AArch32) without a translator, with QEMU as the fallback.
- GLES 1/2/3 through SwiftShader.

### Firmware and vendors
- TouchWiz: `ro.tvout.enable=false`, `ro.SecEDS.enable=false` and binder patches.
- Qualcomm firmware: removed `qti_permissions.xml`, `com.qti.dpmframework.xml`, `com.qualcomm.location`, `qcrilmsgtunnel`, `dpmserviceapp`, `DiracAudioControlService`, `DiracManager`.
- Infinix (AOSP / XUI / XOS) and Tecno (AOSP / HiOS) detected by Android version.
- Sony: notification LED emulation.
- Fixes for MIUI, HTC Sense, TouchWiz, Xperia and AOSP. Tested on Nexus 4, Galaxy Nexus, Galaxy S II/S III, Redmi 1S, Desire HD, One M7, Nexus 5X, Phh 9, AOSP GSI 17.

### Import
- New formats: 7z, RAR, `.pac`, yaffs2, rfs, EROFS, `super.img`, APEX, `.tar.gz`.

### Features
- Internet works in more firmware.
- Sound fixed; microphone, camera, accelerometer and vibration added.
- Navigation bar: Back, Home and Recents work on every firmware.
- ADB over the local network and a built-in ADB terminal.
- Three-dot menu in a running system fixed: screenshots, screen recording, VNC, SMS, airplane mode, APK install and more.
- The menu button can be moved in a running system.
- Option to hide the LED indicator: in defaults for new systems and per system.
