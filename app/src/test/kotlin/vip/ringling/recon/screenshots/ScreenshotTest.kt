package vip.ringling.recon.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import vip.ringling.recon.ui.HistoryPane
import vip.ringling.recon.ui.HomePane
import vip.ringling.recon.ui.ReconActions
import vip.ringling.recon.ui.signal.Tab
import vip.ringling.recon.ui.results.AreaScreen
import vip.ringling.recon.ui.results.ExposureScreen
import vip.ringling.recon.ui.results.FindingsList
import vip.ringling.recon.ui.results.ResultsActions
import vip.ringling.recon.ui.results.ResultsUiState
import vip.ringling.recon.ui.detail.DetailActions
import vip.ringling.recon.ui.detail.FindingDetail
import vip.ringling.recon.ui.history.HistoryScreen
import vip.ringling.recon.ui.history.HistoryUiState
import vip.ringling.recon.ui.home.HomeActions
import vip.ringling.recon.ui.home.HomeUiState
import vip.ringling.recon.ui.scan.ScanScreen
import vip.ringling.recon.ui.scan.ScanUiState
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

    private fun home(state: HomeUiState, selected: vip.ringling.recon.domain.Finding? = null): @Composable (Boolean, PaddingValues) -> Unit =
        { expanded, pad -> HomePane(state, HomeActions(), selected, noActions, expanded, pad) }

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

    private fun results(state: ResultsUiState): @Composable (Boolean, PaddingValues) -> Unit = { _, pad ->
        if (state.input?.type == InputType.ZIP) AreaScreen(state, ResultsActions(), contentPadding = pad) else ExposureScreen(state, ResultsActions(), contentPadding = pad)
    }

    @Test fun results_email() = shot("03_results_email", tab = null, content = results(exposureEmail))

    @Test fun findings_list() = shot("03b_findings_list", tab = null) { _, pad -> FindingsList(exposureEmail, ResultsActions(), contentPadding = pad, onBack = {}) }

    @Test fun results_username() = shot("04_results_username", tab = null, content = results(ResultsUiState(input = user, statuses = userDone.statuses, findings = userDone.findings)))

    @Test fun results_domain() = shot("05_results_domain", tab = null, content = results(ResultsUiState(input = domain, statuses = domainDone.statuses, findings = domainDone.findings)))

    @Test fun area_gate() = shot("06_area_legal_notice", tab = null, content = results(ResultsUiState(input = zip, statuses = zipDone.statuses, findings = zipDone.findings, registryAck = false)))

    @Test fun area_results() = shot("06b_area_results_distance", tab = null, me = GeoPoint(43.6125, -116.2105), content = results(ResultsUiState(input = zip, statuses = zipDone.statuses, findings = zipDone.findings, registryAck = true)))

    @Test fun area_results_no_location() = shot("06c_area_results_no_location", tab = null, content = results(ResultsUiState(input = zip, statuses = zipDone.statuses, findings = zipDone.findings, registryAck = true)))

    @Test fun registry_fullscreen_map_satellite_selected() = shot("06d_registry_fullscreen_map", tab = null, me = GeoPoint(43.6125, -116.2105)) { _, _ ->
        androidx.compose.runtime.CompositionLocalProvider(LocalMapLayer provides MapLayerState(true) {}) {
            FullscreenMapContent(zipDone.findings, GeoPoint(43.6125, -116.2105), "REGISTRY-ID-1", onDismiss = {}, onOpen = {})
        }
    }

    @Test fun detail_registry() = shot("07b_detail_registry", tab = null, me = GeoPoint(43.6125, -116.2105)) { _, pad ->
        FindingDetail(zipDone.findings.first { it.id == "REGISTRY-ID-0" }, DetailActions(), onBack = {}, contentPadding = pad)
    }

    @Test fun detail() = shot("07_detail", tab = if (device.expanded) Tab.HOME else null) { expanded, pad ->
        if (expanded) {
            HomePane(HomeUiState(query = email.raw, detected = InputType.EMAIL, session = streaming, selectedId = adobe.id), HomeActions(), adobe, noActions, true, pad)
        } else {
            FindingDetail(adobe, DetailActions(), onBack = {}, contentPadding = pad)
        }
    }

    @Test fun breach_two_line_titles() = shot("05b_breach_two_line_titles") { _, pad ->
        val samples = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<List<vip.ringling.recon.data.sources.breach.HibpBreach>>(vip.ringling.recon.data.fixture("hibp_samples.json"))
        val findings = listOf("Suno", "Estonia", "Cocospy", "Adobe").map { n -> vip.ringling.recon.data.sources.breach.HibpMapper.breachFinding(samples.first { it.name == n }) }
        androidx.compose.foundation.layout.Column(Modifier.padding(pad).padding(16.dp)) {
            vip.ringling.recon.ui.components.ModuleCard(ModuleId.BREACH, ModuleStatus.DONE, findings, null, null, {})
        }
    }

    @Test fun registry_notice_expanded() = shot("06e_registry_notice_expanded") { _, pad ->
        androidx.compose.foundation.layout.Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                androidx.compose.foundation.layout.Column {
                    vip.ringling.recon.ui.components.LegalNoticeBanner(initiallyExpanded = true)
                    vip.ringling.recon.ui.components.RegistrySummaryRow(zipDone.findings.first { it.id == "REGISTRY-summary" }, initiallyExpanded = true)
                }
            }
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                androidx.compose.foundation.layout.Column {
                    vip.ringling.recon.ui.components.LegalNoticeBanner()
                    vip.ringling.recon.ui.components.RegistrySummaryRow(zipDone.findings.first { it.id == "REGISTRY-summary" })
                }
            }
        }
    }

    @Test fun paid_lookup_confirmation() = shot("07c_paid_lookup_confirmation", tab = null) { _, pad ->
        val offer = vip.ringling.recon.data.sources.phone.PhoneSource(vip.ringling.recon.data.net.Http(), vip.ringling.recon.data.net.Endpoints(), vip.ringling.recon.data.secret.InMemoryApiKeys()).twilioOffer()
        androidx.compose.foundation.layout.Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
            vip.ringling.recon.ui.detail.PaidActionCard(offer.paid!!, onConfirmed = {})
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                androidx.compose.foundation.layout.Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                    androidx.compose.material3.Text("Run a paid lookup?", style = MaterialTheme.typography.headlineSmall)
                    vip.ringling.recon.ui.detail.PaidConfirmBody(offer.paid!!)
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.align(androidx.compose.ui.Alignment.End)) {
                        androidx.compose.material3.TextButton(onClick = {}) { androidx.compose.material3.Text("Cancel") }
                        androidx.compose.material3.TextButton(onClick = {}) { androidx.compose.material3.Text("Confirm and pay") }
                    }
                }
            }
        }
    }

    @Test fun history() = shot("09_history", tab = Tab.HISTORY) { expanded, pad ->
        val st = HistoryUiState(history, selectedId = if (expanded) "h1" else null, now = NOW)
        if (expanded) {
            HistoryPane(st, history.first(), null, noActions, pad, {}, {}, {}, {})
        } else {
            HistoryScreen(st, {}, {}, {}, contentPadding = pad)
        }
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
