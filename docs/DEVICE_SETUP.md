# Device setup

How the development phone is connected and used for testing. **The phone holds the owner's
real data.** Read the safety rules in [CLAUDE.md § Device rules](../CLAUDE.md#device-rules)
before running any adb command.

## The phone

| | |
|---|---|
| Model | OnePlus CPH2645 |
| Android | 16 (API 36) |
| ABI | arm64-v8a |
| Screen | 1264 × 2780 px, 560 dpi |
| Locale | es-ES |

Checked with `adb shell getprop` on 2026-11-18.

## The development PC (Windows 11)

| Tool | Location | Notes |
|---|---|---|
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | Has platform 35 and build-tools 35/36. **Platform 36 must be installed** (Android Studio → SDK Manager) before phase 1 builds |
| adb (use this one) | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` | The one Gradle and Android Studio use. Update it from SDK Manager → SDK Tools → Platform-Tools |
| adb (also installed) | `C:\Program Files\platform-tools\adb.exe` | v36.0.0. Fine, but two different adb versions restart each other's server |
| adb (**do not use**) | `C:\Android\adb.exe` | v1.0.32 (2015), **first on PATH**. No `adb pair`, and it kills newer servers. Remove `C:\Android` from PATH (or move it after the SDK) |
| JDK for Gradle | `C:\Program Files\Android\Android Studio\jbr` | JDK 21. The `java` on PATH is JDK 11, which the Android Gradle Plugin rejects. Set `JAVA_HOME` |
| GitHub CLI | not installed yet | `winget install --id GitHub.cli -e`, then `gh auth login` |

In Git Bash:

```bash
export ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

In PowerShell:

```powershell
$env:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

## One-time phone setup

1. **Developer options**: Ajustes → Acerca del dispositivo → Versión → tap "Número de compilación"
   7 times, then enter the PIN.
2. **USB debugging**: Opciones de desarrollador (under Ajustes → Sistema or Ajustes adicionales,
   depending on the OxygenOS version) → "Depuración por USB" on.
3. Connect the USB cable, pick "Transferencia de archivos" if asked, run `adb devices`, and
   accept "¿Permitir depuración por USB?" on the phone with **"Permitir siempre desde este
   ordenador"** ticked.
4. OnePlus/OxygenOS may show an extra confirmation when an app is installed over USB. Accept it on
   the phone the first time.

## Wireless debugging (Android 11+)

PC and phone on the same Wi-Fi.

1. Opciones de desarrollador → "Depuración inalámbrica" on → "Vincular dispositivo con código
   de vinculación". The phone shows `IP:PORT` and a 6-digit code.
2. Pair (only once per PC):

   ```bash
   "$ADB" pair 192.168.1.50:37123      # pairing port, then type the 6-digit code
   ```

3. Connect, using the **other** port shown on the "Depuración inalámbrica" main screen:

   ```bash
   "$ADB" connect 192.168.1.50:41234
   "$ADB" devices -l
   ```

4. The connection port changes whenever wireless debugging is toggled or the phone reboots.
   `"$ADB" mdns services` lists what the phone currently advertises.

If both USB and Wi-Fi are connected, the phone appears twice. Pass `-s <serial>` to every
command, or unplug one.

## Start-of-session checklist

```bash
"$ADB" devices -l
```

| You see | Meaning / action |
|---|---|
| one line ending in `device … model:CPH2645` | Ready |
| `unauthorized` | Accept the prompt on the phone (unlock it first) |
| `offline` | Unplug and replug, or toggle wireless debugging, then `"$ADB" reconnect` |
| nothing | Cable or USB mode (choose "Transferencia de archivos"), or reconnect over Wi-Fi |
| `adb server version (41) doesn't match this client` | Two adb versions are running. Use only the SDK one |

## Build, install and run

```bash
./gradlew :app:installDebug      # build + install com.lechugones.cocina.debug, keeps data
"$ADB" shell am start -n com.lechugones.cocina.debug/com.lechugones.cocina.MainActivity
"$ADB" shell am force-stop com.lechugones.cocina.debug
```

`installDebug` reinstalls over the existing app (`adb install -r`), so data is kept.

**If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`**, the APK is signed with a
different key than the installed app. **Do not uninstall** (that deletes the data). Stop, then
restore the backed-up `debug.keystore` (below). Uninstalling is a decision for the owner only,
after making a backup.

## Logs

```bash
PID=$("$ADB" shell pidof -s com.lechugones.cocina.debug)
"$ADB" logcat -v time --pid="$PID"                       # live, only our app
"$ADB" logcat -d -v time --pid="$PID" '*:W'              # dump warnings and errors, then exit
"$ADB" logcat -d -v time -s AndroidRuntime:E             # crashes (any app; look for our package)
```

App log tags start with `Cocina` (e.g. `CocinaImport`, `CocinaProvider`). Do not clear the log
buffer (`logcat -c`); use `-T '<time>'` to read from a point in time instead.

## Screenshots and UI inspection

Captures go to `.device/` (gitignored), because they can show personal data.

```bash
mkdir -p .device
"$ADB" exec-out screencap -p > ".device/$(date +%Y%m%d-%H%M%S).png"   # Git Bash: binary-safe
"$ADB" exec-out uiautomator dump /dev/tty                             # view hierarchy as XML
"$ADB" shell dumpsys window | grep mCurrentFocus                       # which app has focus
```

PowerShell 5.1 corrupts binary data in `>` redirection. From PowerShell, go through `cmd`:

```powershell
cmd /c "`"$env:ADB`" exec-out screencap -p > .device\screen.png"
```

Screen recording (max 3 minutes), then pull and delete only our own file:

```bash
"$ADB" shell screenrecord --time-limit 30 /sdcard/Download/cpl-rec.mp4
"$ADB" pull /sdcard/Download/cpl-rec.mp4 .device/ && "$ADB" shell rm /sdcard/Download/cpl-rec.mp4
```

Input automation (`adb shell input tap X Y`, `swipe`, `text`, `keyevent`) only after
`mCurrentFocus` shows `com.lechugones.cocina.debug`. A tap can land in another app if focus
changed.

## Test files on the phone

Use one dedicated folder, and remove only files you put there:

```bash
"$ADB" shell mkdir -p /sdcard/Download/CocinaParaLechugones-test
"$ADB" push examples/recipe-packs/tacos-de-pollo.md /sdcard/Download/CocinaParaLechugones-test/
```

To test the share sheet, open the pushed file in the phone's Files app and share it to
"Cocina (debug)". An `am start -a android.intent.action.SEND` example with the exact import
activity name is added here in phase 2. Multi-line text is awkward to pass through `am`, so file
sharing is the main test path.

## Debug vs release data

| | Debug | Release |
|---|---|---|
| Package | `com.lechugones.cocina.debug` | `com.lechugones.cocina` |
| Used for | Development and adb testing, test data | Day-to-day use, **real data** |
| Signed with | `%USERPROFILE%\.android\debug.keystore` | Personal release keystore (outside the repo) |

To try real data in the debug app: in the release app make a backup ("Ajustes → Copia de
seguridad", from phase 2), then restore that file in the debug app. Never point adb commands at
the release package without asking.

## Keystores (do this once, now)

If a keystore is lost, the app can no longer be updated over itself: the only way forward is
uninstalling, which deletes the data on that phone.

1. Copy `%USERPROFILE%\.android\debug.keystore` to a safe place outside the repo (password
   manager attachment, encrypted drive, or both).
2. From phase 7 on: keep the release keystore and its passwords in the same safe place, and keep
   `keystore.properties` out of git (already in `.gitignore`).

## Before installing a build with a database migration

From phase 2 on, a Room migration bug could damage data. Before installing such a build on a
phone with real data:

1. In the app: Ajustes → Copia de seguridad → Crear copia.
2. Copy the file to the PC: `"$ADB" pull /sdcard/Download/cocina-backup-<fecha>.zip .device/backups/`.
3. Then install.
