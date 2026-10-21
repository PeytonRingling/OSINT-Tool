# Recon — personal OSINT toolkit (Android)

Android-only, Kotlin + Jetpack Compose, Material 3 Expressive. Package `vip.ringling.recon`.
minSdk 29, target/compileSdk 37. Replaces an earlier Python desktop project (reference only for
endpoints/logic; its `offender_registry.py` is NOT in this repo — inspect the live Idaho forms).
Private, sideloaded app. Repo: the GitHub repo this was cloned from (its owner is deliberately not named in the source: CI passes it as `GITHUB_REPOSITORY`, which becomes `BuildConfig.UPDATE_REPO` and drives in-app updates and the User-Agent; local builds leave it empty). Work branch: `claude/busy-pascal-nd1y36`.

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
- Design system "Recon // Signal" (see Signal section): Instrument Serif (titles, numerals), Geist (body), Geist Mono (labels, values, tags), all bundled in `res/font` (OFL licenses in `docs/fonts`).
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

## Registry detail (photos / map / distance) — added 2026-10-15
- Per-entry data: thumbnail + full photo URLs (`/sorFiles/photos/...`), coordinates parsed from the registry's OWN map page
  (`jsp/sor_map_display.jsp`, `google.maps.LatLng(lat, lng)`; no third-party geocoder ever sees an address), offense list, status,
  last-registered. Tags: adult/juvenile database, "Non-compliant" (status NON COMP), "Multiple convictions" (≥2 distinct conviction
  dates). The registry has NO recidivism field — "repeat offender" is only inferred from dates and the UI says so. NON COMP or multiple
  convictions → severity EXPOSED.
- `Finding` gained optional `geo`, `imageUrl`, `imageLargeUrl`, `tags` (generic, any module can use them).
- Photos/tiles: Coil with memory cache only (`diskCache(null)` in `ReconApp`, plus per-request DISABLED). Map = own OSM raster-tile slippy map
  (`ui/map/TileMap.kt`, math in `domain/Geo.kt`, attribution shown). Screenshot tests set `LocalImagesEnabled=false` so renders are offline.
- Location (`data/location/UserLocation`): FINE/COARSE runtime permission, in-memory only, never persisted/logged/sent; used for distances,
  nearest-first sorting and the "you" dot. Tile requests reveal the viewed AREA to OpenStreetMap (not identity).
- Map (`ui/map`): street = OSM tiles, satellite = Esri World Imagery (`…/World_Imagery/MapServer/tile/{z}/{y}/{x}`, attribution "Imagery © Esri, Vantor, Earthstar Geographics"); layer choice persisted in settings (`mapSatellite`) and provided via `LocalMapLayer`. Full-screen map = `FullscreenMap` dialog (pin → summary card → Details). Registry card shows 6 rows + "Show all N results".
- Registry findings can't be exported (`Export.exportable`): no Export JSON button, excluded from export-all; still stripped from history.
  Copy/Share of a single record remains.

## Breach detail + map camera (added 2026-10-15)
- `Finding` also has `facts: List<Fact>` (label/value table in the detail screen) and `hotTags`. HIBP breach findings carry description (HTML stripped),
  dates, counts, domain, verified/sensitive/spam/malware flags, logo, data-class tags and derived advice (`BreachAdvice`); paste findings list each paste;
  LeakCheck findings list every source+date and which FIELDS were exposed. **Leaked credential VALUES are never displayed or stored** (v2 entries
  contain them; the mapper reads only source names/dates/field names — tested).
- LeakCheck keyed v2 (paid; user has a key): parsed tolerantly into per-leak detail (source name/date, per-leak FIELD NAMES, unverified/combo/passwordless flags, `quota`). Shape is from LeakCheck's docs and NOT verified live (no key in the cloud env). The finding's RAW section shows the response with every string masked, so the user can paste a real shape for me to fix parsers + fixture. Values (passwords, emails, IPs…) are never extracted. An opt-in 'reveal values' mode was offered to the user, not built.
- Map camera: pins stream in while a search runs, so the camera only auto-fits until the user pans/zooms (`userMoved`); pinch zooms around the fingers,
  double-tap zooms in, and inline maps claim two-finger gestures only (one finger scrolls the page) — `oneFingerPan=true` only in the full-screen map.

## In-app updates (added 2026-10-15)
- Settings → Updates (+ a quiet launch-time check and snackbar when a token is set). Source = the rolling `latest` GitHub release.
  While the repo is PRIVATE the user adds a fine-grained GitHub token (this repo, **Contents: read**) under Settings → API keys
  (`ApiKeyId.GITHUB`, Keystore-encrypted like the others); if the repo is made public no token is needed (it's optional everywhere). Token only goes to api.github.com; OkHttp drops it on the storage redirect (tested).
- CI writes `build: <run number>` and `sha256: <hex>` lines into the release body; `versionCode` = `GITHUB_RUN_NUMBER` (local builds = 1).
  The app compares build vs `BuildConfig.VERSION_CODE`, downloads to `cacheDir/updates/Recon.apk`, verifies size + SHA-256 (bad file deleted),
  installs via `PackageInstaller` (needs REQUEST_INSTALL_PACKAGES / "Install unknown apps" for Recon). Android itself enforces same-signature,
  so the shared `ci/debug.keystore` must never change or updates stop installing.
- Code: `data/update/{UpdateRepository,ApkInstaller,UpdateManager}.kt`; tests `UpdateTest` (MockWebServer; release JSON fixture authored from GitHub docs).
- Local/dev APKs have versionCode 1, so they can't update over a CI build; always install the release build first.

## Phone intel (added 2026-10-15)
- `ModuleId.PHONE`, `data/sources/phone/`: offline libphonenumber analysis (region, carrier, line type, formats, timezones), 800notes community reports
  (page 1 parsed, count approximated from pager), one-tap lookup links (search engines, Truecaller, Telegram, WhatsApp...; NO people-search brokers),
  optional keyed Veriphone and Twilio Lookup v2 (caller_name = owner/CNAM, paid, billed to the user's own account; response shapes authored from docs, unverified).
- Free sources cannot name a number's owner. Findings gained `links` ("Open in browser") and `pivots` ("Search next" -> `ScanController.requestSearch`).
- 800notes/Veriphone/Twilio shapes: fixtures only; verify live on the phone.

## Username enrichment (added 2026-10-15)
- `Site.extract` (`data/sources/identity/Extract.kt`): per-site spec (kind json|xml|meta; label->path, `*` wildcard over arrays/objects, transforms `|date|num|host`;
  avatar; `handles` = linked accounts on other services). A hit's body is mined for facts, avatar and pivots. Well-known labels (Name, Location, Website, Email, Bio, Company)
  are correlated into one "Identity profile" finding (with pivots: emails -> breach, websites -> domain, linked handles -> username, reverse-image links).
  "Check manually" finding links bot-walled platforms (Instagram, X, TikTok, ...) instead of pretending they were checked.
- API sites with extraction (existence verified live 2026-10-15; extraction paths verified against live shapes for GitLab, Keybase, Chess.com, Bluesky, Mastodon, Dev.to, Hugging Face, Lichess; others authored from docs).
  Telegram: found = title "Telegram: View @x".
- Same-handle matches across sites are not proof of the same person; the profile card says so.

## Extras batch (added 2026-10-15)
- **WhatsMyName import**: `assets/wmn_sites.json` (~340 sites, CC BY-SA 4.0, attributed in Settings → About) generated by `tools/wmn_import.py`, which keeps only sites where a known
  account is found and a random handle is not. `AssetSiteList` merges it after the curated `sites.json` (curated wins on name clashes). `tools/check_sites.py` + weekly
  `.github/workflows/site-health.yml` detect drifted rules (needs `known` handles: `known` in wmn entries, `tools/known_accounts.json` for curated ones).
- **Domain**: Wayback availability (earliest/latest) and Shodan InternetDB (ports/CVEs/hostnames per A record; third-party database, nothing sent to the target) in `DomainExtras`.
  Email checks add Duolingo and WordPress.com (unofficial endpoints, MEDIUM).
- **Photo metadata** (`ModuleId.IMAGE`, `InputType.IMAGE`, never auto-detected): Home chip -> picker -> `ImageAnalyzer` (androidx.exifinterface) shows camera, owner/serial, GPS (+map), SHA-256/MD5, reverse-image links. Fully on-device.
- **Case file**: Room v2 (`MIGRATION_1_2` adds `case_items`, `watch`; migration is tested). "Add to case" on any finding except registry; notes; entity roll-up from pivots; Markdown/PDF/share report (`CaseReport`, `PdfWriter`).
- **Watchlist**: Settings -> Watchlist; `WatchWorker` (WorkManager, daily, network) re-runs the breach source for watched emails, silent baseline first, notification on NEW breaches only; failed lookups never alter the baseline. Needs POST_NOTIFICATIONS on API 33+.
- **Privacy**: Settings -> Tor (Orbot 127.0.0.1:9050) or custom SOCKS5 for every lookup via `ProxyState` (fail-closed; hostnames unresolved locally; Coil photos/tiles use the same proxy selector, so they go through it too). App lock = framework BiometricPrompt (biometric or device credential), re-locks on background, FLAG_SECURE hides recents.
- Share any text into Recon (share sheet) to search it; `SearchWidget` is a launcher shortcut only (no data shown).
- Unverifiable here (need the user's keys/phone): HIBP account endpoints, LeakCheck v2, Veriphone, Twilio, biometric prompt and widget on a real device.

## Intel expansion (added 2026-10-15)
- New input types (auto-detected before phone/domain/username): **IP** (v4/v6) and crypto **wallet** (BTC legacy/bech32, ETH 0x). Pivots from other modules can feed them.
- `NETWORK` (IpSource): RDAP (org, CIDR, abuse contact), approximate geo/ASN via ipinfo (LOW confidence, labeled approximate), reverse DNS (DoH PTR), Shodan InternetDB, Tor exit list, reputation links. Private/reserved IPs short-circuit.
- `CRYPTO` (WalletSource): Blockstream (BTC) and Blockscout (ETH) public APIs for balance/activity/ENS/creator + scam-report and clustering links. Addresses are pseudonymous: no identity claims.
- `CODE` (GithubSource, username): profile, orgs, SSH-key count, and e-mails in public PushEvent commit authors (noreply filtered; labeled as leads). Optional GitHub token only raises the rate limit. Shapes authored from GitHub docs (api.github.com is blocked from the cloud sandbox): verify live.
- `EMAILINTEL`: offline analysis (disposable blocklist asset `disposable_domains.txt`, CC0 disposable-email-domains; free/role flags; +tag and Gmail-dot canonicalisation; MX provider via DoH) with pivots to username/breach/domain.
- `DORKS`: ready-made search operators per input type (pure links, no network). Domain also gets urlscan.io public scan history.
- Tests: `IntelTest.kt` (+ fixtures `rdap_ip.json` live, `urlscan.json` live). 185 tests.

## Map state + bad pins (added 2026-10-15)
- `domain/MapMemory`: in-memory only (cleared on every new scan) camera for the inline ("inline") and full-screen ("full") maps, the selected pin, and `reopenFullscreen`. "Details" from the full-screen map
  sets it on phones so Back reopens the map exactly where it was (not in the two-pane layout, where the record shows beside the list).
- `ScanSession.apply` drops the pin (keeps the record, adds tag "Location unverified") of a registry entry >100 km from the median of the others (needs ≥4 located); the registry's own geocoder sometimes lands in the wrong city.
  `parseGeo` prefers the Marker's LatLng over the map centre.
- Different street addresses with identical coordinates = geocoder fell back to a town/city centre: tagged "Approximate location" (`APPROXIMATE_TAG`), note added to the detail, pale/small pin, no distance shown. Same address sharing coordinates is NOT flagged.

## Risk score, severity, paid lookups (added 2026-10-15, from v0.1.13 device testing)
- **Risk score (`RiskScore`, VERSION 2) is a pure function of two counts**: `score = min(100, 14 × exposed + 4 × info)`, bands: ≥50 exposed, ≥14 info (one exposed finding is never "clean"), else clean.
  Only *scorable* findings count (`Finding.scorable`): not CLEAN, not offender-registry records, not DORKS, and not housekeeping ids (suffixes `-error -summary -manual -links -profile -offer -pending -nostate -none`).
  Identical counts → identical score regardless of order/modules (tested). Don't add new helper findings without a suffix from that list.
- **History**: `ScanEntity.scoreVersion` (Room v3, `MIGRATION_2_3`, default 0). Rows saved by an older build are re-scored on load (`Rescore`: breach severity re-derived from stored data classes, registry → INFO) and labelled "Re-scored under current rules". New rows are saved at `RiskScore.VERSION`. Bump VERSION whenever scoring/severity rules change.
- **Registry is neutral**: ZIP results use `CountTile` ("12 RECORDS"), never the risk ring, never a severity chip or "Exposed" wording (adapter severity is always INFO; status/convictions are tags; `Finding.neutral`). History shows "Registry search · live lookup" with no ring.
  The legal notice gates results until acknowledged, then stays on screen as `LegalNoticeBanner`; the "matches are by location only" disclaimer is `RegistrySummaryRow` (two lines + Read more).
- **Breach severity (`BreachSeverity`)** is derived from HIBP DataClasses: EXPOSED if any of passwords/hints/security Q&A/auth tokens, card or bank/financial data, government ID (SSN, passport, driver's licence, issued IDs), physical addresses, or phone numbers; otherwise INFO (email/names/usernames/IP/DOB/locations…).
  Tests use real HIBP data (`fixtures/hibp_samples.json`, incl. Suno 2025). Rows show two lines of title and of the first detail line; only hot (exposing) tags are red.
- **Paid lookups never run in a scan.** A source returns an offer `Finding.paid = PaidAction(...)`; the detail screen shows the cost and an explicit confirmation dialog; only then `ScanController.runPaid` calls `PaidLookups.runPaid` (offer → "Looking up…" → result). Twilio: `PhoneSource.TWILIO_COST_LABEL` (≈ $0.018 = caller name $0.010 + line type $0.008, Twilio list prices at time of writing; update the constant if pricing changes). Offers are not saved to history.

## Signal redesign (added 2026-10-15, from the Claude Design canvas)
- Design system "Recon // Signal": near-black ground, volt `#D2FF3F` signal, coral alert, azure info, mint safe; Instrument Serif (display/numerals), Geist (body), Geist Mono (labels/values), bundled in `res/font` (OFL texts in `docs/fonts`). Tokens in `ui/theme` (`ReconTheme.signal`), components in `ui/signal` (BracketPanel, StatusTag, SegmentedControl, LedSwitch, RiskNumeral, TickBar, Sparkline, ReconBottomBar/ReconRail, ...). Default theme Dark; Light kept as an option.
- Nav: Radar, Cases, Log, System (bottom bar; 72dp rail >=600dp). The old Scan tab is gone: module selection is the Loadout sheet (Auto-pick arms all; tapping a tile switches to Manual). Radar (`ui/radar`, math in `domain/Radar.kt`) places a blip per past target.
- Results: non-ZIP = Exposure map (`ExposureScreen`: risk numeral, orbit graph, breach timeline, fix-first) + findings list; ZIP = `AreaScreen` (map + records, NO score/ring, registry notice/neutral rules unchanged). >=840dp: `ExposureWide` (centre graph, 320dp findings pane with All/Exposed/Info + selected card incl. "Mark handled") and `AreaScreen(split=true)` (map + records pane with "Open record"/"Pin to case"). Selection lives in `ResultsViewModel`.
- Log (`ui/log`, `domain/Log.kt`): grouped by target with sparklines; By target / Timeline. Backed by `scanSnapshot` (Room v4): one row per scan with risk/exposed/info; ZIP stores the record COUNT only (registry data is never stored). Delta = latest vs FIRST scan. Backfilled from History on first Log load; Clear all also clears snapshots.
- Cases (`ui/cases`, Room `caseFile/caseTarget/caseNote`; legacy `case_items` kept for schema only): dossier cards, link graph (edges from shared handles/domains, "No link yet" is shown, never inferred), Targets/Pivots/Notes/Changes, "Pin to case" from results, Markdown/PDF/share report. Case exposure = max risk of scored targets. Changes timeline (`domain/CaseChanges.kt`) is derived ONLY from scanSnapshot rows (no inferred causes). >=840dp: `CasesWorkspace` (280dp list + canvas, Changes inline).
- System (`ui/settings`): status panel, privacy (proxy, app lock), display, API-key status list, updates, data, about. Watchlist UI now lives on Cases; `HandledRepository` backs "Mark handled".
- Migrations 1->2->3->4 are tested (`CaseTest`). Unverified on a device: fold/unfold state retention, biometric, widget, Tor, notifications.

## Improvements batch (added 2026-10-15)
- Breaches: tappable rows on the exposure screen (`FindingGroup`), tappable orbit nodes / fix-first steps / timeline dots (bottom sheets list the matching findings; `FixStep.findingIds`, `TimelineEvent.findingId`). Breach detail = `ui/detail/BreachDetail.kt` (stats, what leaked + `BreachAdvice.explain`, what to do, story, details).
- Radar and Log both read `scanSnapshot` (`RadarMath.targetsFromSnapshots`); `SnapshotRepository.backfill` adds any History row without a snapshot (runs at Radar/Log start). Radar draws the 12 newest, labels the 3 newest. Log: "Re-scan all" and Undo for Clear all (in-memory backup).
- Re-scan all (`ScanController.rescanAll`, `ScanDiffs`): sequential background scans of non-ZIP/non-photo targets, saved like normal scans, `RescanSheet` shows score change + new titles. Also in a case's menu.
- Handled: `HandledRepository.keyOf(f)` hash (NOT the finding id) is what the UI compares; "Mark as handled" on phone detail + wide pane; handled rows dim. The risk score is unchanged by it.
- Watchlist accepts email (breach diff), username and domain (diff of scorable finding ids via `ScanRunner`; failed runs never alert). Adding one asks for POST_NOTIFICATIONS.
- Password check (`PwnedPasswords`, k-anonymity, only a 5-char SHA-1 prefix leaves the phone, nothing stored). Evidence PDF (`Evidence`: timestamp + SHA-256 of the finding text; not for registry records). Backup export/import (`BackupManager`: scans, snapshots, cases, watchlist; never keys/settings; import merges).
- Incoming: share sheet, text-selection menu (PROCESS_TEXT), `mailto:`/`tel:` links, launcher shortcut "Scan clipboard", Paste button on Radar (`IncomingText`).
- Fold: `androidx.window` tabletop posture (`LocalTabletop`): results stack graph above findings. Unverified on a device.

## Hardening & extras batch (added 2026-10-17)
- **Encrypted DB** (`data/db/DbEncryption.kt`): SQLCipher via `SupportOpenHelperFactory`; random passphrase held only as AES-GCM ciphertext under a Keystore key (`DbKey`). A plaintext `recon.db` is converted in place on first launch (export, verify version + table count, swap, overwrite+delete old file). Fail-safe: any failure (native lib, Keystore, conversion) leaves the DB as it was and the app runs unencrypted rather than losing data; under Robolectric it always falls back. UNVERIFIED on a device. Room is v5 (`MIGRATION_4_5` adds `cases.tags`). `allowBackup=false`.
- **Retention / wipe / welcome**: `DataGuard` (`purgeOlderThan` at launch + on setting change; `wipeEverything` = clearAllTables + handled + keys + settings + updates + cancels work). Settings: `retentionDays`, `autoRescan`, `onboarded`, `loaded` (first-run UI waits for `loaded`). `WelcomeDialog` explains keys once.
- **Daily case re-scan** (`data/watch/AutoRescan.kt`): WorkManager, scans every non-ZIP/non-photo case target, records scans, notifies only if worse or a new finding (`AutoRescanLogic`); runs with a failed source are skipped (no false "improved").
- **Tor test**: System -> Privacy -> "Test connection" (`RouteTester`, check.torproject.org/api/ip through the same client/proxy selector as every lookup).
- **Look-alike domains**: opt-in free action on domain scans (`LookalikeGenerator` + `LookalikeLookup`, DoH only, reuses the `PaidAction` confirm flow with "Free" wording). Handle variants (`Variants`, in the DORKS module) give "Search next" pivots.
- **Cases**: tags (`CaseFileRepository.normalizeTags`), search (`CaseSearch`), "Expand leads (up to 8)" adds pivot targets and scans them; Log has a search box.
- **Site health** (`SiteHealth`): username sites inconclusive 5 sweeps in a row are skipped for a week; a sweep where nothing was conclusive records nothing.
- **Lint** runs in CI (`lintDebug`). Fixed: `InputStream.readNBytes` (API 33) crash on API 29-32, missing `USE_BIOMETRIC`, `LocalActivity`, location SecurityException.

## Public-records name search (added 2026-10-17)
- `InputType.PERSON` ("Name search" button on Radar; NEVER auto-detected) and `ModuleId.PEOPLE`. `InputType.countsOnly` (ZIP, PERSON) = live lookup, neutral count instead of a risk score, snapshot stores only the count, findings are NEVER saved to History and not exportable (`ScanController` strips PEOPLE like REGISTRY; `Export.exportable`).
- Sources (`data/sources/people/People.kt`, all "matched by name only", LOW confidence): Wikidata humans (`haswbstatement:P31=Q5`, live-verified), ORCID (live-verified), CourtListener RECAP dockets (anonymous; may 429; shape from docs), FEC individual donations (optional free api.data.gov key `ApiKeyId.FEC`; shape from docs; city/state/employer only, NEVER street addresses), plus a links finding to official/public portals (PACER, NSOPW, EDGAR, BrokerCheck, OpenCorporates, Scholar, Patents, News).
- Also: NPI registry (CMS, keyless, live-verified: name, credential, specialty, practice city/state only) and OpenAlex (publications; keyless, may 429). FEC falls back to the shared `DEMO_KEY` (40/hour) when the user has no key.
- People-finder sites (Spokeo, Whitepages, TruePeopleSearch, ...) are included by the USER'S EXPLICIT REQUEST as plain browser LINKS only (`PeopleParsers.finders`, separate "PEOPLE-finders" finding with a data-quality warning). Recon never requests, scrapes or sends them anything. The user later asked for their DATA to be pulled into the app; that was declined (it would compile home addresses, phones and relatives of private people by name, and the sites forbid scraping). Instead, open sources are read in-app as deeply as possible (ORCID employment/works), and all links open in an in-app Custom Tab (`androidx.browser`; it uses the browser's network, not Recon's Tor/proxy). Do not add scraping of them. Home addresses/phones are never read from any API source. OpenCorporates' API needs a token and GDELT limits to 1 req / 5 s, so they are links only.
- UI: `PersonSearchDialog` (states the lawful-use notice), `PersonScreen` (match count, source groups, "sources with no result", portals).

## Wow pass (added 2026-10-17)
- Motion: `RiskNumeral` counts up (and glides on change); `OrbitGraph` nodes grow in staggered as each module first reports, and a radar sweep circles the target while `scanning` (scan running). All of it stays still when the system's animations are off (`rememberAnimationsEnabled`).
- **Lead graph** (`domain/EntityGraph.kt`, `ui/results/EntityGraphScreen.kt`): full-screen pan/zoom dialog, target -> modules -> leads, built ONLY from findings' `pivots` (a lead shows under every module that listed it; target, DORKS excluded; max 40). Tap a lead -> "Search this" pivots via `ResultsActions.onPivot`. Button on the exposure screen when there are leads. Tests: `EntityGraphTest`.
- **Briefing** (`domain/Briefing.kt` `BriefingBuilder`, `ui/results/Briefing.kt`): templated plain-English summary card on the exposure screen (headline, breach span/most recent, per-module counts, unreachable sources, "Start with" = first fix-first step). Pure function of findings; tests in `BriefingTest`.
- **Since last scan** (`domain/SinceLast.kt`, `SinceLastCard`): results banner comparing against the previous saved scan of the same target (`ResultsViewModel.previous`): risk delta, new titles, gone count (suppressed when any source failed). Not for countsOnly types. Tests `SinceLastTest`.
- **Dossier PDF cover** (`PdfWriter.Cover`): dark title page (kicker, title, big exposure number, key lines) before the report pages of a case PDF. Robolectric cannot run PdfDocument, so the PDF path is UNVERIFIED off-device.
- **Security checkup** (`domain/Checkup.kt`, `CheckupCard` at the top of the Log): latest saved scan per non-area target; fixes ranked by how many targets they help (stealer logs urgent first, same breach across targets = one fix, passkeys). Handled findings excluded. Tests `CheckupTest`.
- **Weakest link** in the lead graph: `EntityNode.sources/hot`, `EntityGraphModel.weakest` = lead behind an exposed finding listed by the most sources; drawn in alert colour with a pulsing ring, reason shown on tap.
- **Risk widget** (`RiskWidget`, `domain/WidgetRisk`): 2x1 home-screen gauge showing ONLY the highest latest risk and a band word (no names/values/findings). The number is kept in SharedPreferences `recon_widget` (the only data outside the encrypted DB), published by `ReconApp` from the snapshots flow (clears itself when scans are wiped). Unverified on a device.
- "Breach sources checked" rows list what each source found (`SourceCheck.findingIds`, bottom sheet), HIBP included.
- **E-mail spoofing grade** (`domain/MailGrade.kt`, `MailGradeCard`, domain scans): A-F from SPF 30 / DMARC 40 / DKIM 15 / MTA-STS 10 / BIMI 5 with a checklist and advice; null (no grade) when SPF or DMARC did not come back. Tests `MailGradeTest`.
- **Scan report PDF** (`domain/ScanReport.kt`, header "Save PDF report" button): cover page + summary/briefing/grade + findings by module via the system file picker; registry/name-search/DORKS/error findings never included. PDF rendering unverified off-device. Tests `ScanReportTest`.
- **Lab tab** (`Tab.LAB`, `ui/lab`, `domain/Lab.kt`, `data/lab/LinkTracer.kt`): 5th nav item with one-shot tools that keep nothing. (1) Header analyzer (`HeaderAnalyzer`: unfolds headers, SPF/DKIM/DMARC from Authentication-Results/Received-SPF, display-name spoof, Return-Path/Reply-To/Message-ID mismatches, hop path, verdict, pivots to sender domain / origin IP; fully offline). (2) Link inspector (`LinkTracer` follows redirects hop by hop through the shared client/proxy with Range 0-0 and never reads a body, max 10 hops, http/https only; `LinkAnalysis` flags shorteners, xn--, IP hosts, user@host, http, long chains). (3) Password check (same k-anonymity `PwnedPasswords`). Tests `LabTest`, `LinkTracerTest`.
- **Lab additions**: Message (scam-text check `ScamCheck`: urgency/threat/credential/money/secrecy cues, brand-vs-link mismatch, link tricks, risky attachment types; verdict labels "likely a scam"), QR (`QrReader`, ZXing core 3.5.3, Apache-2.0 attributed in System -> About; image picker, on-device, hands a decoded link to the Link tool), Decode (`LabDecoder`: Base64, hex, URL, JWT with exp/iat and an unverified-signature note, epoch s/ms, ROT13). Tool chooser is a scrolling chip row. Tests `LabTextTest`.
- **Link inspector hardening**: never reads a body, no cookies, no auto-redirects, http/https only, max 10 hops; refuses private/loopback/link-local/CGNAT/ULA targets (literal hosts and names that resolve to them, via a guarding `Dns`) so a link can't probe the phone's LAN or a metadata address. Tests `LinkTracerTest`. Known limits: it still sends a GET (reveals the user's IP unless Tor/proxy is on and can trip one-time links), and meta-refresh/JS redirects are not followed.
- Decided against more: backend/off-device features. Still ideas: dossier PDF cover, correlation timeline, on-device "what to fix first" summary.

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
- **Idaho registry** (implemented from the live site, not memory): ZIP search = `POST /sor_id/SOR` `form=5&zip=NNNNN&rad=A&sz=400`; city `form=3&cty=`, county `form=4&cnt=` (city/county built from the form markup, not live-run). HTML results: 15 rows/page, "N matches found in M pages", cells `td#nam_field/kno_field/adr_field/cty_field(x2)/zip_field/stat_field`; detail `GET SOR?id=N&sz=400`. Queries BOTH the adult (`rad=A`) and juvenile (`rad=J`) databases (user-requested; same public site, same rules), EVERY results page of that one query (pager links `&page=N`, 15 rows/page; verified live: ZIP 83646 = 67 records over 5 pages, all loaded in ~17 s, streamed), bounded by `maxPagesPerDatabase=20` and `maxEnriched=200` (rows past the cap still show, without detail/coordinates, and the summary says "Showing X of N"). Never DOB/physical description/aliases, retains nothing (`raw` empty). Detail links are taken from the results hrefs. Count wording seen live: "N matches found in M pages.", "N matches found.", "1 match.". Fixtures are fictional but mirror the markup; if Idaho changes markup, update parser + fixtures together.
- Registry findings are shown live only: `ScanController` strips them before saving history (tested). Never cache.
- Failure policy: a failing sub-check becomes an INFO "… unavailable" finding (breach/domain); an inconclusive site probe
  is counted, never treated as "not found".
- Tests: `./gradlew testDebugUnitTest` (domain, MockWebServer source tests, Roborazzi). 200+ tests at last count.
- Robolectric on JDK 21 needs the `--add-opens/--add-exports` jvmArgs already in `app/build.gradle.kts`.
