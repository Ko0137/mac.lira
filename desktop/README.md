# L.I.R.A. desktop

The desktop client is a Java 17 / JavaFX application. Build and test it on the
target operating system so Gradle selects the matching JavaFX native libraries.

From this directory, using the included Gradle Wrapper:

```text
bash ./gradlew clean test installDist fatJar
```

Run the development build with `bash ./gradlew run`. The application stores data in
`~/.lira/lira_offline.db`, using the Android app's SQLite schema version 6.
Existing desktop and Android numeric record IDs are migrated before the first
sync so older records from different devices do not overwrite one another.

The desktop app works offline for chat history, finances, budgets, goals,
progress, daily planning, tasks, and notes. Russian voice recognition uses the
bundled Vosk model; first use extracts it to `~/.lira/model-ru`. A microphone
and roughly 100 MB of additional disk space are needed. Voice replies use the
operating system's built-in speech tools. Browser search requires internet.

To create a Windows GUI app image without a console, run the build above on
Windows with JDK 17 and then use `jpackage` with the JavaFX Windows jars:

```powershell
$packageInput = "build\windows-input"
New-Item -ItemType Directory -Force $packageInput | Out-Null
Copy-Item "build\libs\lira-desktop-1.0.0-all.jar" $packageInput
$fx = "build\install\lira-desktop\lib"
$modules = "$env:JAVA_HOME\jmods;$fx\javafx-base-21.0.2-win.jar;$fx\javafx-graphics-21.0.2-win.jar;$fx\javafx-controls-21.0.2-win.jar"
$runtimeModules = "javafx.controls,jdk.httpserver,java.sql,jdk.unsupported,java.logging,java.naming,java.management"
jpackage --type app-image --name LIRA --app-version 1.0.0 `
 --input $packageInput --main-jar lira-desktop-1.0.0-all.jar `
  --main-class com.example.lira.desktop.LiraDesktopApp `
 --module-path $modules --add-modules $runtimeModules `
 --java-options "--add-modules=$runtimeModules" `
  --dest build\windows-package
```

Do not pass `--win-console`; the resulting `LIRA.exe` is a GUI launcher and
includes its own Java runtime. The app image can be zipped for portable use.

## macOS

Build a DMG for the current Mac architecture on macOS with JDK 17:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
bash ./build-macos.sh native
```

The DMG is written under `build/macos-package-arm64` on Apple Silicon or
`build/macos-package-x64` on Intel. This creates an unsigned app; macOS may
require Control-click > Open on first launch. For distribution without that
warning, sign and notarize the app with an Apple Developer ID.

To create a download that supports both Intel and Apple Silicon, use an Apple
Silicon Mac with Rosetta installed and JDK 17 installations for both
architectures. Rosetta is needed to run the Intel JDK:

```sh
export JAVA_HOME_ARM64=/path/to/arm64/jdk-17
export JAVA_HOME_X64=/path/to/x64/jdk-17
bash ./build-macos.sh universal
```

The resulting `build/LIRA-macOS-Universal.zip` contains separate native DMGs
for Apple Silicon and Intel Macs. Install the matching DMG for the Mac.
Native builds must be performed on macOS; the Windows build cannot produce
valid macOS app bundles. To sign, set `APPLE_SIGN_IDENTITY` to the exact
Developer ID Application identity installed in the Mac's keychain. To
notarize, create a `notarytool` keychain profile and set
`APPLE_NOTARY_PROFILE` before running the script. Unsigned builds are allowed
for private testing; Gatekeeper may require Control-click > Open.

### GitHub Actions

The repository workflow `.github/workflows/macos.yml` builds Apple Silicon and
Intel DMGs on a macOS runner when desktop sources change, or when started
manually from the Actions tab. Download the `LIRA-macOS-Universal` artifact
from the completed workflow run.

Without signing secrets the workflow produces unsigned DMGs for testing. To
sign and notarize releases, add these repository Actions secrets:

- `APPLE_CERTIFICATE_BASE64`: base64-encoded Developer ID Application `.p12`.
- `APPLE_CERTIFICATE_PASSWORD`: password used to export that `.p12`.
- `APPLE_ID`: Apple ID used for notarization.
- `APPLE_APP_SPECIFIC_PASSWORD`: app-specific password for that Apple ID.
- `APPLE_TEAM_ID`: Apple Developer team ID.

Keep the certificate and passwords in GitHub Secrets; never commit them or
send them in chat. Notarization requires an active Apple Developer Program
membership and a Developer ID Application certificate.

For local Wi-Fi sync, enable the sync server in desktop Settings and enter its
displayed IP address and pairing code in Android Settings. The service listens
on port `48522`; allow it through the PC firewall on a trusted private network.
Sync uses HTTP on the local network and is not encrypted.
