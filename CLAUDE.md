# Recon — personal OSINT toolkit (Android)

Android-only, Kotlin + Jetpack Compose, Material 3 Expressive. Package `vip.ringling.recon`.
minSdk 29, target/compileSdk 37. Replaces an earlier Python desktop project (reference only for
endpoints/logic; its `offender_registry.py` is NOT in this repo — inspect the live Idaho forms).
Private, sideloaded app. Repo: testuser/OSINT-Tool. Work branch: `claude/busy-pascal-nd1y36`.

## Process rules
- **Phase 1 (done, approved):** scaffold, design system, four tab screens + detail, committed screenshots.
- **Phase 2 (in progress):** breach, identity, domain modules DONE and fixture-tested; Room history and
  Keystore-encrypted keys DONE; Idaho registry adapter DONE (live-inspected 2026-10-14).
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

## Modules
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
- **Legal notice wording** (`ui/components/Cards.kt`, Settings) now follows the statements on the Idaho SOR site itself
  (74-120: no commercial use / no phone or mailing lists; 18-8326 & 18-8413 anti-harassment). Still worth a read by a human.
- Registry mock data (screenshots only) uses clearly fictional placeholder records. Never commit real registry data.
- Phase 1 stand-ins are replaced: Room history, Keystore-encrypted keys, real breach/identity/domain sources. `data/mock/MockData.kt` remains only for screenshot tests and previews.
- Workflow `.github/workflows/build.yml` has not been run yet (no Actions access from the cloud session).
- Keep `docs/screenshots/` in sync: re-run `./gradlew recordRoborazziDebug` after any UI change and commit the PNGs.

## Phase 2 status / gotchas
- Data hosts were allowed on 2026-10-15 and the keyless paths were verified LIVE with harmless inputs: Cloudflare DoH, crt.sh, rdap.org (→ registry RDAP), LeakCheck public endpoint, Spotify signup check, Gravatar, HIBP `/breaches` model, ~30 username sites. Still unverifiable here (need the user's keys): HIBP `breachedaccount`/`pasteaccount`, LeakCheck keyed v2. Fixtures under `app/src/test/resources/fixtures/` mirror live shapes where verified; v2/HIBP-account ones are authored from docs. If a shape is wrong, fix the parser + fixture together.
- Live findings baked into code: LeakCheck no-result = `{"success":false,"error":"Not found"}` (treated as clean); its public endpoint works keyless for email/username/phone (phone digits only, no `+`). Example.com-style revoked DKIM (`p=` empty) is not a signer. crt.sh lists e-mail SANs; non-hostnames are dropped. Site probes use a browser UA, and bot-wall interstitials ("Just a moment", "Client Challenge") are INCONCLUSIVE, never found/absent.
- `sites.json` was calibrated against live responses (existing vs nonexistent handle); sites that return 200 for any name (Twitch, Imgur, PyPI, Wikipedia, LeetCode) were dropped. confidence LOW = bot-walled from the build environment so unverified (Codeberg, SourceForge, npm, CodePen, Reddit, Last.fm, Letterboxd, Patreon). Re-calibrate periodically: sites change.
- Unofficial endpoints that may change: Spotify signup validation (works today: status 20 = registered), `sites.json` detection rules (heuristic; MEDIUM for HTML pages, HIGH for APIs).
- **Idaho registry** (implemented from the live site, not memory): ZIP search = `POST /sor_id/SOR` `form=5&zip=NNNNN&rad=A&sz=400`; city `form=3&cty=`, county `form=4&cnt=` (city/county built from the form markup, not live-run). HTML results: 15 rows/page, "N matches found in M pages", cells `td#nam_field/kno_field/adr_field/cty_field(x2)/zip_field/stat_field`; detail `GET SOR?id=N&sz=400`. Queries BOTH the adult (`rad=A`) and juvenile (`rad=J`) databases (user-requested; same public site, same rules), page 1 of each only, never DOB/physical description/aliases/photos, retains nothing (`raw` empty). Detail links are taken from the results hrefs. Count wording seen live: "N matches found in M pages.", "N matches found.", "1 match.". Fixtures are fictional but mirror the markup; if Idaho changes markup, update parser + fixtures together.
- Registry findings are shown live only: `ScanController` strips them before saving history (tested). Never cache.
- Failure policy: a failing sub-check becomes an INFO "… unavailable" finding (breach/domain); an inconclusive site probe
  is counted, never treated as "not found".
- Tests: `./gradlew testDebugUnitTest` (domain, MockWebServer source tests, Roborazzi). 90 tests at last count.
- Robolectric on JDK 21 needs the `--add-opens/--add-exports` jvmArgs already in `app/build.gradle.kts`.
