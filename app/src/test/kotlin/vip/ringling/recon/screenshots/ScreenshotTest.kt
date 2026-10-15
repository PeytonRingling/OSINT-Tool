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
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.ui.components.LocalImagesEnabled
import vip.ringling.recon.ui.components.LocalUserLocation
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanSession
import vip.ringling.recon.ui.AppShell
import vip.ringling.recon.ui.HistoryPane
import vip.ringling.recon.ui.HomePane
import vip.ringling.recon.ui.ReconActions
import vip.ringling.recon.ui.Tab
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

    @Test fun home_idle() = shot("01_home_idle", content = home(HomeUiState(recents = recents)))

    @Test fun home_typing() = shot(
        "02_home_typing",
        content = home(HomeUiState(query = "alex.moreno@proton.me", detected = InputType.EMAIL, recents = recents)),
    )

    @Test fun home_streaming() = shot(
        "03_home_streaming",
        content = home(HomeUiState(query = email.raw, detected = InputType.EMAIL, session = streaming, selectedId = adobe.id), selected = adobe),
    )

    @Test fun home_complete() = shot(
        "04_home_complete",
        content = home(
            HomeUiState(query = user.raw, detected = InputType.USERNAME, session = userDone, selectedId = "IDENTITY-github"),
            selected = userDone.findings.first { it.id == "IDENTITY-github" },
        ),
    )

    @Test fun home_domain() = shot(
        "05_home_domain",
        content = home(HomeUiState(query = domain.raw, detected = InputType.DOMAIN, session = domainDone)),
    )

    @Test fun home_registry_gate() = shot(
        "06_home_registry_legal_notice",
        content = home(HomeUiState(query = zip.raw, detected = InputType.ZIP, session = zipDone, registryAck = false)),
    )

    @Test fun home_registry_results() = shot(
        "06b_home_registry_results_map_distance", me = GeoPoint(43.6125, -116.2105),
        content = home(
            HomeUiState(query = zip.raw, detected = InputType.ZIP, session = zipDone, registryAck = true, selectedId = "REGISTRY-ID-0"),
            selected = zipDone.findings.first { it.id == "REGISTRY-ID-0" },
        ),
    )

    @Test fun home_registry_results_no_location() = shot(
        "06c_home_registry_results_no_location",
        content = home(HomeUiState(query = zip.raw, detected = InputType.ZIP, session = zipDone, registryAck = true)),
    )

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

    @Test fun scan() = shot("08_scan", tab = Tab.SCAN) { _, pad ->
        ScanScreen(ScanUiState(session = streaming, enabled = ModuleId.entries.toSet() - ModuleId.REGISTRY), { _, _ -> }, {}, contentPadding = pad)
    }

    @Test fun history() = shot("09_history", tab = Tab.HISTORY) { expanded, pad ->
        val st = HistoryUiState(history, selectedId = if (expanded) "h1" else null, now = NOW)
        if (expanded) {
            HistoryPane(st, history.first(), null, noActions, pad, {}, {}, {}, {})
        } else {
            HistoryScreen(st, {}, {}, {}, contentPadding = pad)
        }
    }

    @Test fun settings() = shot("10_settings", tab = Tab.SETTINGS) { _, pad ->
        SettingsScreen(
            SettingsUiState(AppSettings(ThemeMode.DARK, dynamicColor = false, haptics = true), mapOf(ApiKeyId.HIBP to "demo-hibp-key-0000"), history.size, "0.1.0"),
            SettingsActions(), contentPadding = pad,
        )
    }
}
