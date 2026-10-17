package vip.ringling.recon.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import vip.ringling.recon.domain.isRegistryRecord
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vip.ringling.recon.data.mock.MockData
import vip.ringling.recon.data.settings.AppSettings
import vip.ringling.recon.data.settings.ThemeMode
import androidx.compose.runtime.CompositionLocalProvider
import vip.ringling.recon.data.update.ReleaseInfo
import vip.ringling.recon.data.update.UpdateState
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.ui.components.LocalImagesEnabled
import vip.ringling.recon.ui.map.FullscreenMapContent
import vip.ringling.recon.ui.map.LocalMapLayer
import vip.ringling.recon.ui.map.MapLayerState
import vip.ringling.recon.ui.components.LocalUserLocation
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanSession
import vip.ringling.recon.ui.AppShell
import vip.ringling.recon.ui.ReconActions
import vip.ringling.recon.ui.signal.Tab
import vip.ringling.recon.ui.log.LogActions
import vip.ringling.recon.ui.log.LogMode
import vip.ringling.recon.ui.log.LogScreen
import vip.ringling.recon.ui.log.LogUiState
import vip.ringling.recon.ui.results.AreaScreen
import vip.ringling.recon.ui.results.ExposureScreen
import vip.ringling.recon.ui.results.FindingsList
import vip.ringling.recon.ui.results.ResultsActions
import vip.ringling.recon.ui.results.ResultsUiState
import vip.ringling.recon.ui.detail.DetailActions
import vip.ringling.recon.ui.detail.FindingDetail
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.ui.settings.SettingsActions
import vip.ringling.recon.ui.settings.SettingsScreen
import vip.ringling.recon.ui.settings.SettingsUiState
import vip.ringling.recon.ui.theme.ReconTheme

/**
 * Renders every screen on the JVM at phone and Pixel Fold (unfolded) sizes in dark and light.
 * Record with: ./gradlew recordRoborazziDebug  → docs/screenshots/<device>-<theme>/<screen>.png
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenshotTest(private val device: Device, private val dark: Boolean) {

    enum class Device(val dir: String, val qualifiers: String, val expanded: Boolean) {
        PHONE("phone", "w411dp-h914dp-port-xhdpi", false),
        FOLD("fold", "w841dp-h701dp-land-xhdpi", true),
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark={1}")
        fun params(): List<Array<Any>> = listOf<Array<Any>>(
            arrayOf<Any>(Device.PHONE, true), arrayOf<Any>(Device.PHONE, false),
            arrayOf<Any>(Device.FOLD, true), arrayOf<Any>(Device.FOLD, false),
        )

        private const val NOW = 1_760_000_000_000L
        private val email = ScanInput("alex.moreno@proton.me", InputType.EMAIL)
        private val domain = ScanInput("northwind-labs.io", InputType.DOMAIN)
        private val user = ScanInput("ghost_in_shell", InputType.USERNAME)
        private val zip = ScanInput("83702", InputType.ZIP)

        private val streaming = ScanSession(
            email,
            statuses = mapOf(ModuleId.BREACH to ModuleStatus.DONE, ModuleId.IDENTITY to ModuleStatus.RUNNING),
            findings = MockData.breach(email) + MockData.identity(email).take(2),
        )
        private val domainDone = ScanSession(
            domain, statuses = mapOf(ModuleId.DOMAIN to ModuleStatus.DONE), findings = MockData.domain(domain),
        )
        private val userDone = ScanSession(
            user,
            statuses = mapOf(ModuleId.BREACH to ModuleStatus.DONE, ModuleId.IDENTITY to ModuleStatus.DONE),
            findings = MockData.breach(user) + MockData.identity(user),
        )
        private val zipDone = ScanSession(
            zip, statuses = mapOf(ModuleId.REGISTRY to ModuleStatus.DONE), findings = MockData.registry(zip),
        )
        private val history = MockData.history(NOW)
        private val recents = history
        private val adobe = MockData.breach(email).first()
        private val noActions = ReconActions(DetailActions(), { _, _ -> })
    }

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() {
        RuntimeEnvironment.setQualifiers(device.qualifiers)
    }

    private fun shot(name: String, tab: Tab? = Tab.HOME, me: GeoPoint? = null, content: @Composable (expanded: Boolean, pad: PaddingValues) -> Unit) {
        rule.setContent {
            ReconTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
              CompositionLocalProvider(LocalImagesEnabled provides false, LocalUserLocation provides me) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val expanded = maxWidth >= 840.dp
                        val snackbar = remember { SnackbarHostState() }
                        AppShell(tab, useRail = maxWidth >= 600.dp, snackbar = snackbar, onTab = {}) { pad ->
                            content(expanded, pad)
                        }
                    }
                }
              }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(2_500)
        rule.onRoot().captureRoboImage("../docs/screenshots/${device.dir}-${if (dark) "dark" else "light"}/$name.png")
    }

    private val radarTargets = listOf(
        vip.ringling.recon.domain.RadarTarget(ScanInput("test.user@example.com", InputType.EMAIL), 100, null, NOW - 7 * 60_000, 4, "a"),
        vip.ringling.recon.domain.RadarTarget(ScanInput("5555550123", InputType.PHONE), 12, null, NOW - 9 * 60_000, 2, "b"),
        vip.ringling.recon.domain.RadarTarget(ScanInput("testuser", InputType.USERNAME), 10, null, NOW - 12 * 3_600_000, 1, "c"),
        vip.ringling.recon.domain.RadarTarget(ScanInput("test.user2@example.com", InputType.EMAIL), 0, null, NOW - 11 * 3_600_000, 1, "d"),
        vip.ringling.recon.domain.RadarTarget(ScanInput("83338", InputType.ZIP), 0, 68, NOW - 11 * 60_000, 3, null),
        vip.ringling.recon.domain.RadarTarget(ScanInput("83301", InputType.ZIP), 0, 0, NOW - 11 * 3_600_000, 1, null),
    )

    @Test fun radar_idle() = shot("01_radar_idle") { expanded, pad ->
        vip.ringling.recon.ui.radar.RadarScreen(vip.ringling.recon.ui.radar.RadarUiState(targets = radarTargets), vip.ringling.recon.ui.radar.RadarActions(), contentPadding = pad, wide = expanded)
    }

    @Test fun radar_typing() = shot("02_radar_typing") { expanded, pad ->
        vip.ringling.recon.ui.radar.RadarScreen(
            vip.ringling.recon.ui.radar.RadarUiState(
                query = "test.user@example.com", detected = InputType.EMAIL,
                modules = listOf(ModuleId.BREACH, ModuleId.IDENTITY, ModuleId.EMAILINTEL, ModuleId.CODE), targets = radarTargets,
            ),
            vip.ringling.recon.ui.radar.RadarActions(), contentPadding = pad, wide = expanded,
        )
    }

    @Test fun loadout_sheet() = shot("02b_loadout", tab = null) { _, pad ->
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF070A08)).padding(pad)) {
            vip.ringling.recon.ui.radar.LoadoutContent(ModuleId.entries.toSet(), true, { _, _ -> }, {}, {})
        }
    }

    private val hibpSamples = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromString<List<vip.ringling.recon.data.sources.breach.HibpBreach>>(vip.ringling.recon.data.fixture("hibp_samples.json"))
        .map { vip.ringling.recon.data.sources.breach.HibpMapper.breachFinding(it) }
        .let { l -> l.mapIndexed { i, f -> if (i == 0) f.copy(facts = f.facts + vip.ringling.recon.domain.Fact("Flags", "Stealer log — malware-harvested credentials")) else f } }

    private val exposureEmail = ResultsUiState(
        input = email,
        statuses = mapOf(ModuleId.BREACH to ModuleStatus.DONE, ModuleId.IDENTITY to ModuleStatus.DONE, ModuleId.EMAILINTEL to ModuleStatus.DONE, ModuleId.CODE to ModuleStatus.DONE),
        findings = hibpSamples + MockData.identity(email).take(1) + vip.ringling.recon.data.sources.emailintel.EmailAnalysis.analyze(email.raw, false, listOf("mx.google.com")),
    )

    private fun results(state: ResultsUiState): @Composable (Boolean, PaddingValues) -> Unit = { wide, pad ->
        val st = if (wide) state.copy(selectedId = state.selectedId ?: state.findings.firstOrNull { it.severity == vip.ringling.recon.domain.Severity.EXPOSED || it.isRegistryRecord }?.id) else state
        if (st.input?.type == InputType.ZIP) AreaScreen(st, ResultsActions(), contentPadding = pad, split = wide)
        else if (wide) vip.ringling.recon.ui.results.ExposureWide(st, ResultsActions(), pad)
        else ExposureScreen(st, ResultsActions(), contentPadding = pad)
    }

    @Test fun results_email() = shot("03_results_email", tab = null, content = results(exposureEmail))

    @Test fun findings_list() = shot("03b_findings_list", tab = null) { _, pad -> FindingsList(exposureEmail, ResultsActions(), contentPadding = pad, onBack = {}) }

    @Test fun results_username() = shot("04_results_username", tab = null, content = results(ResultsUiState(input = user, statuses = userDone.statuses, findings = userDone.findings)))

    @Test fun results_domain() = shot("05_results_domain", tab = null, content = results(ResultsUiState(input = domain, statuses = domainDone.statuses, findings = domainDone.findings)))

    @Test fun area_gate() = shot("06_area_legal_notice", tab = null, content = results(ResultsUiState(input = zip, statuses = zipDone.statuses, findings = zipDone.findings, registryAck = false)))

    @Test fun area_results() = shot("06b_area_results_distance", tab = null, me = GeoPoint(43.6125, -116.2105), content = results(ResultsUiState(input = zip, statuses = zipDone.statuses, findings = zipDone.findings, registryAck = true)))

    @Test fun area_pin_preview() = shot("06f_area_pin_preview", tab = null, me = GeoPoint(43.6125, -116.2105), content = results(ResultsUiState(input = zip, statuses = zipDone.statuses, findings = zipDone.findings, registryAck = true, selectedId = zipDone.findings.first { it.isRegistryRecord }.id)))

    @Test fun person_results() = shot("12_person_results", tab = null) { _, pad ->
        val p = ScanInput("Jordan Example", InputType.PERSON)
        vip.ringling.recon.ui.results.PersonScreen(ResultsUiState(input = p, findings = MockData.people(p) + vip.ringling.recon.domain.Finding("PEOPLE-court-error", ModuleId.PEOPLE, "Court dockets unavailable", "Rate-limited; try again in a minute", vip.ringling.recon.domain.Severity.INFO), statuses = mapOf(ModuleId.PEOPLE to ModuleStatus.DONE)), ResultsActions(), contentPadding = pad)
    }

    @Test fun area_results_no_location() = shot("06c_area_results_no_location", tab = null, content = results(ResultsUiState(input = zip, statuses = zipDone.statuses, findings = zipDone.findings, registryAck = true)))

    @Test fun registry_fullscreen_map_satellite_selected() = shot("06d_registry_fullscreen_map", tab = null, me = GeoPoint(43.6125, -116.2105)) { _, _ ->
        androidx.compose.runtime.CompositionLocalProvider(LocalMapLayer provides MapLayerState(true) {}) {
            FullscreenMapContent(zipDone.findings, GeoPoint(43.6125, -116.2105), "REGISTRY-ID-1", onDismiss = {}, onOpen = {})
        }
    }

    @Test fun detail_registry() = shot("07b_detail_registry", tab = null, me = GeoPoint(43.6125, -116.2105)) { _, pad ->
        FindingDetail(zipDone.findings.first { it.id == "REGISTRY-ID-0" }, DetailActions(), onBack = {}, contentPadding = pad)
    }

    @Test fun detail() = shot("07_detail", tab = null) { _, pad ->
        FindingDetail(adobe, DetailActions(), onBack = {}, contentPadding = pad)
    }

    private fun snap(id: String, input: ScanInput, ago: Long, risk: Int, exposed: Int = 0, info: Int = 0) =
        vip.ringling.recon.domain.ScanSnapshot(id, input.type, input.raw, NOW - ago, risk, exposed, info, emptyList())

    private val logSnapshots = listOf(
        snap("e1", email, 12 * 3_600_000, 34, 3, 5), snap("e2", email, 11 * 3_600_000, 100, 12, 10), snap("e3", email, 3 * 3_600_000, 100, 12, 10), snap("e4", email, 7 * 60_000, 100, 12, 10),
        snap("p1", ScanInput("5555550123", InputType.PHONE), 9 * 60_000, 12, 0, 3), snap("p0", ScanInput("5555550123", InputType.PHONE), 20 * 3_600_000, 8, 0, 2),
        snap("z1", zip, 11 * 60_000, 0, 0, 68), snap("z2", zip, 9 * 3_600_000, 0, 0, 68), snap("z3", zip, 30 * 3_600_000, 0, 0, 66),
        snap("z4", ScanInput("83301", InputType.ZIP), 11 * 3_600_000, 0, 0, 0),
        snap("c1", ScanInput("test.user2@example.com", InputType.EMAIL), 11 * 3_600_000, 0),
        snap("u1", ScanInput("testuser", InputType.USERNAME), 12 * 3_600_000, 10, 1, 4),
    )

    @Test fun log_by_target() = shot("09_log", tab = Tab.HISTORY) { _, pad ->
        LogScreen(LogUiState(vip.ringling.recon.domain.LogMath.targets(logSnapshots), logSnapshots.sortedByDescending { it.ts }, LogMode.BY_TARGET, NOW), LogActions(), contentPadding = pad)
    }

    @Test fun log_timeline() = shot("09b_log_timeline", tab = Tab.HISTORY) { _, pad ->
        LogScreen(LogUiState(vip.ringling.recon.domain.LogMath.targets(logSnapshots), logSnapshots.sortedByDescending { it.ts }, LogMode.TIMELINE, NOW), LogActions(), contentPadding = pad)
    }

    private val caseEmail = ScanInput("test.user@example.com", InputType.EMAIL)
    private val casePhone = ScanInput("5555550123", InputType.PHONE)
    private val caseUser = ScanInput("testuser", InputType.USERNAME)
    private val caseSnaps = listOf(
        snap("c1", caseEmail, 12 * 3_600_000, 34, 3, 8), snap("c2", caseEmail, 11 * 3_600_000 + 30 * 60_000, 100, 12, 8), snap("c3", caseEmail, 7 * 60_000, 100, 12, 10),
        snap("c4", caseUser, 12 * 3_600_000, 10, 1, 4), snap("c5", casePhone, 20 * 3_600_000, 8, 0, 2), snap("c6", casePhone, 9 * 60_000, 12, 0, 3),
        snap("c7", ScanInput("test.user2@example.com", InputType.EMAIL), 11 * 3_600_000, 0),
        snap("c8", zip, 11 * 60_000, 0, 0, 68), snap("c9", ScanInput("83301", InputType.ZIP), 11 * 3_600_000, 0, 0, 0),
    )

    private fun caseView(number: Int, name: String, targets: List<ScanInput>, pivots: List<vip.ringling.recon.domain.CasePivot> = emptyList(), notes: List<vip.ringling.recon.domain.CaseNote> = emptyList()): vip.ringling.recon.ui.cases.CaseView {
        val file = vip.ringling.recon.domain.CaseFile("case$number", name, NOW, number, targets.map { vip.ringling.recon.domain.CaseTarget(it.type, it.raw, NOW) }, notes)
        val by = caseSnaps.groupBy { it.key }
        val states = file.targets.map { t -> by[t.key].orEmpty().sortedBy { it.ts }.let { vip.ringling.recon.domain.CaseTargetState(t, it.lastOrNull(), it) } }
        return vip.ringling.recon.ui.cases.CaseView(file, states, vip.ringling.recon.domain.CaseLogic.edges(file.targets), pivots, vip.ringling.recon.domain.CaseChanges.events(states), vip.ringling.recon.domain.CaseChanges.exposureSeries(states))
    }

    private val footprint = caseView(
        1, "My footprint", listOf(caseEmail, caseUser, casePhone),
        pivots = listOf(
            vip.ringling.recon.domain.CasePivot("pal@work.io", "Breach check pal@work.io", "Found in Code footprint for test.user@example.com."),
            vip.ringling.recon.domain.CasePivot("octo_x", "Search @octo_x", "Found in Identity for testuser."),
        ),
    )
    private val casesState = vip.ringling.recon.ui.cases.CasesUiState(
        listOf(footprint, caseView(2, "example.com", listOf(ScanInput("test.user2@example.com", InputType.EMAIL))), caseView(3, "Jerome area", listOf(zip, ScanInput("83301", InputType.ZIP)))),
        listOf(vip.ringling.recon.data.watch.WatchItem("test.user@example.com", 7, NOW)),
    )

    @Test fun cases_list() = shot("11_cases", tab = Tab.CASES) { _, pad -> vip.ringling.recon.ui.cases.CasesScreen(casesState, vip.ringling.recon.ui.cases.CasesActions(), contentPadding = pad) }

    @Test fun case_detail_pivots() = shot("11b_case_detail", tab = Tab.CASES) { wide, pad ->
        val detail: @Composable () -> Unit = {
            vip.ringling.recon.ui.cases.CaseDetailScreen(
                footprint, if (wide) vip.ringling.recon.ui.cases.CaseTab.CHANGES else vip.ringling.recon.ui.cases.CaseTab.PIVOTS, vip.ringling.recon.ui.cases.CaseDetailActions(), contentPadding = pad,
                graphHeight = if (wide) 380.dp else 300.dp,
                changesContent = if (wide) { { androidx.compose.foundation.layout.Column { vip.ringling.recon.ui.cases.ExposurePanel(footprint); vip.ringling.recon.ui.cases.ChangeTimeline(footprint.events, NOW) } } } else null,
            )
        }
        if (wide) androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(Modifier.width(280.dp).fillMaxSize()) {
                vip.ringling.recon.ui.cases.CasesScreen(casesState, vip.ringling.recon.ui.cases.CasesActions(), contentPadding = pad, selectedId = footprint.file.id, compact = true)
            }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxSize()) { detail() }
        } else detail()
    }

    @Test fun case_detail_targets() = shot("11c_case_targets", tab = Tab.CASES) { _, pad ->
        vip.ringling.recon.ui.cases.CaseDetailScreen(footprint, vip.ringling.recon.ui.cases.CaseTab.TARGETS, vip.ringling.recon.ui.cases.CaseDetailActions(), contentPadding = pad)
    }

    @Test fun case_timeline() = shot("11d_case_changes", tab = Tab.CASES) { _, pad ->
        vip.ringling.recon.ui.cases.CaseTimelineScreen(footprint, vip.ringling.recon.ui.cases.ChangeFilter.ALL, {}, {}, {}, NOW, contentPadding = pad)
    }

    @Test fun case_timeline_worse() = shot("11e_case_changes_worse", tab = Tab.CASES) { _, pad ->
        vip.ringling.recon.ui.cases.CaseTimelineScreen(footprint, vip.ringling.recon.ui.cases.ChangeFilter.WORSE, {}, {}, {}, NOW, contentPadding = pad)
    }

    @Test fun settings_update_available() = shot("10b_settings_update_available", tab = Tab.SETTINGS) { _, pad ->
        val info = ReleaseInfo(12, "u", "Recon.apk", 4_057_192, null, "2026-10-15T01:02:03Z", null)
        SettingsScreen(
            SettingsUiState(AppSettings(), mapOf(ApiKeyId.HIBP to "demo-hibp-key-0000", ApiKeyId.GITHUB to "github_pat_demo"), history.size, "0.1.11", 11, UpdateState.Available(info)),
            SettingsActions(), contentPadding = pad,
        )
    }

    @Test fun settings() = shot("10_settings", tab = Tab.SETTINGS) { _, pad ->
        SettingsScreen(
            SettingsUiState(AppSettings(ThemeMode.DARK, dynamicColor = false, haptics = true), mapOf(ApiKeyId.HIBP to "demo-hibp-key-0000"), history.size, "0.1.0"),
            SettingsActions(), contentPadding = pad,
        )
    }
}
