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

Pull requests run `.github/workflows/ci.yml`: a gitleaks secret scan, build, lint (any warning not in `app/lint-baseline.xml`
fails) and the unit tests. Actions are pinned to commit SHAs, and Dependabot proposes updates weekly.

Every push to `main` publishes `Recon.apk` to the rolling `latest` pre-release. The repo is public, so the in-app updater needs
no token. It installs an update only if the APK is signed with the same certificate as the installed app.

## Release signing

Release builds are signed with a private key kept in GitHub Actions secrets. There is no fallback key: if any secret is missing,
the release workflow stops with an error naming it and publishes nothing. Debug builds use the standard local debug key.

One-time setup, on your own computer (`keytool` ships with any JDK, including Android Studio's). Keep the `.jks` file and its
password somewhere safe and never commit them: every future update must be signed with this key.

```
keytool -genkeypair -v -storetype PKCS12 -keystore recon-release.jks -alias recon -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 recon-release.jks | gh secret set RELEASE_KEYSTORE_B64 -R PeytonRingling/OSINT-Tool
gh secret set RELEASE_KEYSTORE_PASSWORD -R PeytonRingling/OSINT-Tool   # paste the keystore password
gh secret set RELEASE_KEY_PASSWORD -R PeytonRingling/OSINT-Tool        # paste the same password (PKCS12 uses one password)
gh secret set RELEASE_KEY_ALIAS -R PeytonRingling/OSINT-Tool -b recon
gh secret list -R PeytonRingling/OSINT-Tool                            # expect the four RELEASE_* names
```

On macOS use `base64 -i recon-release.jks | gh secret set ...`. Set the secrets before the first push to `main` that carries this
workflow, or that build fails at "Load release key".

Android won't install an update signed with a different key, so the switch needs one manual reinstall: export a backup in Recon
(System → Data → Backup → Export), uninstall Recon, install `Recon.apk` from the `latest` release, then import the backup and re-enter
your API keys (backups never contain keys). Until you do, the old app's in-app update fails at install time, which is harmless.
After the reinstall, in-app updates from the `latest` release work as before.
