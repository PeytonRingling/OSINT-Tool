# Recon — personal OSINT toolkit (Android)

Android-only, Kotlin + Jetpack Compose, Material 3 Expressive. Package `vip.ringling.recon`.
minSdk 29, target/compileSdk 37. Replaces an earlier Python desktop project (reference only for
endpoints/logic; its `offender_registry.py` is NOT in this repo — inspect the live Idaho forms).
Private, sideloaded app. Repo: testuser/OSINT-Tool. Work branch: `claude/busy-pascal-nd1y36`.

## Process rules
- **Phase 1 (done when approved):** scaffold, design system, four tab screens + detail with MOCK data,
  committed screenshots. **Stop and show the user before wiring real modules (Phase 2).**
- Commit in small steps. No PR unless asked.
- **No API keys exist in the environment and none may ever be committed.** Keys are entered in
  Settings at runtime (Keystore-backed encrypted storage, never plaintext, never logged, masked with
  reveal toggle). Test every module's parsing/error handling against saved fixtures + MockWebServer;
  the user does live checks on their phone.
- The only committed secret-like file is `ci/debug.keystore` (throwaway, password `android`) so
  every CI build has the same signature and installs over the previous one.

## Environment / build
- Cloud container, no emulator, no KVM. Android SDK at `/opt/android-sdk` (`local.properties` is
  gitignored; set `ANDROID_HOME=/opt/android-sdk`). JDK 21, Gradle wrapper 9.8.1, AGP 9.4.1
  (built-in Kotlin — no `kotlin-android` plugin), Hilt 2.60.1, KSP.
- Network allowlist needed: dl.google.com, maven.google.com, services.gradle.org,
  plugins.gradle.org, repo1.maven.org, fonts.google.com. `repo.maven.apache.org` often returns 429, so
  `settings.gradle.kts` uses `repo1.maven.org`. If a build fails with 429, just retry.
- Verify: `./gradlew assembleDebug testDebugUnitTest`. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Screenshots: Roborazzi on Robolectric (JVM). `./gradlew recordRoborazziDebug` writes PNGs to
  `docs/screenshots/` (committed for review): phone (411x914dp) and Pixel Fold unfolded
  (841x701dp), dark + light.
- CI: `.github/workflows/build.yml` builds on every push to `main`, signs with the shared keystore,
  and publishes to the rolling pre-release tagged `latest`.

## Design (top priority — must not look like a default template)
- Dark-first, true-black AMOLED surfaces; optional Material You dynamic color toggle (off by default).
- Accent palette: "volt" acid lime primary; severity colors: clean = mint-teal, info = azure,
  exposed = coral. Not default purple.
- One display font (Space Grotesk, bundled in `res/font`) for headlines/numerals + system sans for body.
- Motion: expressive spring specs, shared-element transition list→detail, predictive back, haptic on
  scan complete, shimmer skeletons, animated risk-score rings.
- Home: single smart search field, auto-detects email/phone/username/domain/ZIP, overridable
  type chip, runs relevant modules in parallel, streams results as each finishes.
- Results: cards grouped per module, severity chips (clean/info/exposed), tap → detail;
  copy / share / export JSON.
- Bottom nav Home, Scan, History, Settings (rail on medium+). WindowSizeClass adaptive; list-detail on
  expanded widths (Pixel Fold unfolded must look intentional).
- A11y: 48dp targets, AA contrast, font scaling.

## Architecture
- Single Gradle module `:app`, layers `ui/` `domain/` `data/`. ViewModel + StateFlow, Hilt,
  coroutines/Flow, Navigation Compose.
- Every source implements `OsintSource` (id, supportedInputTypes, `suspend fun run(input): Flow<Finding>`).
  Normalized `Finding`: module, title, detail, severity, url, raw. Add modules without UI changes.
- OkHttp + kotlinx.serialization, Jsoup, Room (history with clear-all + per-item delete).
- Everything on-device, no backend.

## Modules (Phase 2 — only after design approval)
1. **Breach**: HIBP v3 for email (`hibp-api-key` header, required user-agent, honor 429 `Retry-After`);
   LeakCheck with key, falling back to its public endpoint without a key.
2. **Identity**: username via bundled curated JSON site list (URL template + detection method),
   concurrent with a rate limit. Email/phone registration checks = small curated set, each hit has a
   confidence level.
3. **Domain**: Cloudflare DoH for A/AAAA/MX/NS/TXT; SPF, DMARC (flag missing / p=none), DKIM common
   selectors, BIMI, MTA-STS; subdomains via crt.sh; RDAP instead of WHOIS. Passive only — no port scanning.
4. **Offender registry**: per-state adapter interface, Idaho first (apps.isp.idaho.gov/sor_id: ZIP/city/
   county search + detail page, Jsoup). Live per-query only; **never bulk-cache or compile lists**
   (Idaho Code 74-120). Show the legal notice before results.

## Open items / notes
- **Legal notice wording** in `ui/components/Cards.kt` (LegalNotice) and Settings is a placeholder paraphrase of
  Idaho Code §74-120 as described by the user; verify against the statute before release.
- Registry mock data uses clearly fictional placeholder records only. Never commit real registry data.
- Phase 1 stand-ins to replace in Phase 2: `MockSources.kt` (bound in `di/AppModule.kt`),
  `InMemoryHistoryRepository` (→ Room), in-memory API keys in `SettingsViewModel` (→ Keystore-backed encrypted storage).
- Workflow `.github/workflows/build.yml` has not been run yet (no Actions access from the cloud session).
- Keep `docs/screenshots/` in sync: re-run `./gradlew recordRoborazziDebug` after any UI change and commit the PNGs.
