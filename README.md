# Recon

Personal on-device OSINT toolkit for Android (Kotlin, Compose, Material 3 Expressive). See `CLAUDE.md` for
design decisions, architecture and process.

**Status: Phase 1** — design system and all screens on mock data. Real modules are wired only after design approval.

Screenshots (phone and Pixel Fold unfolded, dark and light) are in `docs/screenshots/`.

```
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug testDebugUnitTest
./gradlew recordRoborazziDebug     # re-record docs/screenshots
```

Every push to `main` publishes `Recon.apk` to the rolling `latest` pre-release.
