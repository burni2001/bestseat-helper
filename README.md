# Best Seat Helper

Small helpers for Google TV / Android TV, made to go with the [Best Seat](https://github.com/burni2001/best-seat) launcher.
It works with any launcher. Some features are tailored to Philips Google TVs.

A setup wizard on first start walks you through the settings that depend on your TV.

## Helpers

| Helper | What it does |
| --- | --- |
| **Remote keys** | Map remote keys to apps, actions or intents (short, double and long press). Learn any key with "Add a key…". On Philips TVs it also frees the Prime Video, Netflix and YouTube keys and maps the five function keys (Sources, TV, Quick Menu, Text, Settings, Assistant). |
| **Accessibility guard** | Turns watched accessibility services back on when they drop out, e.g. after Force stop. |
| **System apps** | Turns hidden system apps on or off (stock launcher, tracking clients, any other system app you add). |
| **Autostart** | Starts chosen apps with the TV and keeps them running. |
| **Screen timeout** | Keeps the screen-off time and Android's screensaver the way you set them. |
| **Refresh home screen** | Makes the launcher reload its apps, e.g. when apps on a USB drive are missing. |

**Backup** saves all settings to `Download/bestseat-helper-backup.json` on the TV and restores them.

## Install

1. Download `bestseat-helper.apk` from the [latest release](https://github.com/burni2001/bestseat-helper/releases/latest).
2. Install it with adb:
   ```bash
   adb install bestseat-helper.apk
   ```
3. Open **Best Seat Helper**. The setup wizard starts.

## Permissions

Some helpers need permissions that can only be granted with adb. The app shows which ones are missing under **Permissions**.

```bash
adb shell appops set tv.bestseat.helper GET_USAGE_STATS allow
adb shell appops set tv.bestseat.helper SYSTEM_ALERT_WINDOW allow
adb shell appops set tv.bestseat.helper WRITE_SETTINGS allow
adb shell pm grant tv.bestseat.helper android.permission.WRITE_SECURE_SETTINGS
```

Remote keys and the guards also need the accessibility service:
**Settings › Accessibility › Best Seat Helper**.

System apps, Autostart and Backup connect to the TV's own network debugging (`127.0.0.1:5555`).
Turn on network debugging in the developer options. The first time, confirm "Allow debugging" on the TV and choose "Always allow".

## Build

No Gradle. Needs JDK 17+ and the Android SDK command-line tools (platform 34, build-tools 34.0.0).

```bash
./build.sh
```

Paths can be overridden with `JAVA_HOME`, `ANDROID_SDK`, `BUILD_TOOLS`, `KEYSTORE` and `KEYSTORE_PASS`.
Without a keystore, the script creates one on the first build.

## License

MIT, see [LICENSE](LICENSE). Created by Börni (burni2001).
