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
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | Platforms 35, 36 and 37 (Gradle installed 36 and 37 automatically in phase 1). Referenced from the gitignored `local.properties`: `sdk.dir=C\:/Users/Joan/AppData/Local/Android/Sdk` |
| adb (use this one) | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` | The one Gradle and Android Studio use. Update it from SDK Manager → SDK Tools → Platform-Tools |
| adb (also installed) | `C:\Program Files\platform-tools\adb.exe` | v36.0.0. Fine, but two different adb versions restart each other's server |
| adb (**do not use**) | `C:\Android\adb.exe` | v1.0.32 (2015), **first on PATH**. No `adb pair`, and it kills newer servers. Remove `C:\Android` from PATH (or move it after the SDK) |
| JDK for Gradle | `C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot` | JDK 21. The `java` on PATH is JDK 11, which the Android Gradle Plugin rejects. Android Studio's JBR (`…\Android Studio\jbr`) is JDK 21 too, but it lacks `jdk.crypto.mscapi`, so it cannot use the Windows trust store (next row) |
| Avast Web Shield | HTTPS scanning on | Re-signs every HTTPS certificate with "Avast Web/Mail Shield Root". Windows trusts it; Java does not (`PKIX path building failed` when Gradle downloads). Fix per process: `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`. Alternatives, if you prefer: exclude Gradle/Maven hosts from Avast HTTPS scanning, or add `systemProp.javax.net.ssl.trustStoreType=Windows-ROOT` to `%USERPROFILE%\.gradle\gradle.properties` |
| Android Studio | 2024.3 (build 243) | Older than AGP 9.5 supports. Update it before opening the project in the IDE; command-line builds don't need it |
| GitHub CLI | not installed yet | `winget install --id GitHub.cli -e`, then `gh auth login` |

In Git Bash:

```bash
export ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.1.12-hotspot"
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

In PowerShell:

```powershell
$env:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot"
$env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

Every JVM then prints `Picked up JAVA_TOOL_OPTIONS: …` on stderr. That is expected.

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
"$ADB" shell am start -W -n com.lechugones.cocina.debug/com.lechugones.cocina.MainActivity   # -W waits for the first frame
"$ADB" shell am force-stop com.lechugones.cocina.debug
```

Reading the screen as text is cheaper than a screenshot:

```bash
"$ADB" exec-out uiautomator dump /dev/tty | grep -oE '(text|content-desc)="[^"]+"'
```

Bottom-bar tap targets on this phone (phase 1 layout): Recetas (146, 2581), Despensa (469, 2581),
Compra (793, 2581), Catálogo (1116, 2581), Ajustes gear (1166, 252).

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

Harmless noise seen in our process on this phone (phase 1): OxygenOS framework lines
(`OplusPredictiveBackController`, `OplusBracketLog`, `SchedAssist`, `DynamicFramerate`),
`Access denied finding property`, a `WindowOnBackDispatcher` opt-in warning that appears even
though the manifest opts in, and `hiddenapi … AccessibilityNodeInfo;->getSelection` when
`uiautomator dump` runs (Compose probing an API 37 method on Android 16). Anything else at
`W`/`E` level from our PID deserves a look.

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
"Importar receta" (the share target label of Cocina (debug)). `MainActivity` receives shares
(`singleTask`, `onNewIntent`), so a text share can also be simulated, though multi-line recipes are
awkward to pass through `am`:

```bash
"$ADB" shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "hola" -n com.lechugones.cocina.debug/com.lechugones.cocina.MainActivity
```

(That text is not a recipe, so the import screen reports a missing front matter block, which is
expected.)

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
