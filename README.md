# Recon

Personal on-device OSINT toolkit for Android (Kotlin, Jetpack Compose, Material 3 Expressive). Lookups run from the phone itself,
optionally through Tor (Orbot) or a SOCKS5 proxy. History, cases and API keys stay on the device, encrypted at rest.
See `CLAUDE.md` for design decisions, architecture and process.

Modules: breach (HIBP, LeakCheck), username sweep (~340 sites), email intel, domain (DNS, RDAP, crt.sh, Wayback, look-alikes),
phone, IP/network, crypto wallets, GitHub, photo metadata, people (open sources), the Idaho offender registry, and the Lab
(link inspector, QR, message checks, VirusTotal).

Screenshots (phone and Pixel Fold unfolded, dark and light) are in `docs/screenshots/`.

## Build

Requires **JDK 21** and the Android SDK.

```
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug lintDebug testDebugUnitTest
./gradlew recordRoborazziDebug     # re-record docs/screenshots
```

Every push to `main` publishes `Recon.apk` to the rolling `latest` pre-release. The repo is public, so the in-app updater needs
no token. It installs an update only if the APK is signed with the same certificate as the installed app.

## Release signing

Release builds are signed with a private key kept in GitHub Actions secrets. There is no fallback key: if any secret is missing,
the release workflow fails. Debug builds use the standard local debug key.

One-time setup (run on your own computer, keep the `.jks` file and passwords somewhere safe, never commit them):

```
keytool -genkeypair -v -keystore recon-release.jks -alias recon -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 recon-release.jks | gh secret set RELEASE_KEYSTORE_B64 -R PeytonRingling/OSINT-Tool
gh secret set RELEASE_KEYSTORE_PASSWORD -R PeytonRingling/OSINT-Tool   # paste the store password
gh secret set RELEASE_KEY_ALIAS -R PeytonRingling/OSINT-Tool -b recon
gh secret set RELEASE_KEY_PASSWORD -R PeytonRingling/OSINT-Tool        # paste the key password
```

(On macOS use `base64 -i recon-release.jks` instead of `base64 -w0`.) Builds signed with the new key can't install over a build
signed with the old one, so once: export a backup in Recon (System → Data → Backup → Export), uninstall Recon, install the new `Recon.apk`,
then restore the backup and re-enter API keys.
