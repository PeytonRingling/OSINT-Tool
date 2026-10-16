package vip.ringling.recon.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import vip.ringling.recon.data.mock.MockData
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.ui.components.LocalImagesEnabled
import vip.ringling.recon.ui.results.AreaScreen
import vip.ringling.recon.ui.results.ResultsActions
import vip.ringling.recon.ui.results.ResultsUiState
import vip.ringling.recon.ui.theme.ReconTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w841dp-h701dp-land-xhdpi")
class AreaWideClickTest {
    @get:Rule val rule = createComposeRule()

    @Test fun openRecordInTheWidePaneOpensTheSelectedRecord() {
        val zip = ScanInput("83702", InputType.ZIP)
        val findings = MockData.registry(zip)
        val sel = findings.first { it.id.startsWith("REGISTRY-ID") }
        var opened: Finding? = null; var selected: Finding? = null
        rule.setContent {
            ReconTheme(vip.ringling.recon.data.settings.ThemeMode.DARK, dynamicColor = false) {
                CompositionLocalProvider(LocalImagesEnabled provides false) {
                    AreaScreen(
                        ResultsUiState(input = zip, findings = findings, registryAck = true, selectedId = sel.id),
                        ResultsActions(onFinding = { selected = it }, onOpenFinding = { opened = it }), split = true,
                    )
                }
            }
        }
        rule.onNodeWithText("Open record", ignoreCase = true).performClick()
        assertEquals(sel.id, opened?.id, "Open record must navigate to the record's detail")
    }

    @Test fun chevronOnARecordRowOpensItWhileTheRowOnlySelects() {
        val zip = ScanInput("83702", InputType.ZIP)
        val findings = MockData.registry(zip)
        var opened: Finding? = null; var selected: Finding? = null
        rule.setContent {
            ReconTheme(vip.ringling.recon.data.settings.ThemeMode.DARK, dynamicColor = false) {
                CompositionLocalProvider(LocalImagesEnabled provides false) {
                    AreaScreen(
                        ResultsUiState(input = zip, findings = findings, registryAck = true),
                        ResultsActions(onFinding = { selected = it }, onOpenFinding = { opened = it }), split = true,
                    )
                }
            }
        }
        val chevron = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("has onClickLabel Open record details") { n ->
            n.config.getOrNull(SemanticsActions.OnClick)?.label == "Open record details"
        })
        chevron[0].performClick()
        assertEquals(true, opened != null && selected == null, "chevron opens, it does not just select")
    }

    @Test fun phonePinPreviewShowsTheRecordAndOpensItFromDetails() {
        val zip = ScanInput("83702", InputType.ZIP)
        val findings = MockData.registry(zip)
        val sel = findings.first { it.id.startsWith("REGISTRY-ID") }
        var opened: Finding? = null
        rule.setContent {
            ReconTheme(vip.ringling.recon.data.settings.ThemeMode.DARK, dynamicColor = false) {
                CompositionLocalProvider(LocalImagesEnabled provides false) {
                    AreaScreen(ResultsUiState(input = zip, findings = findings, registryAck = true, selectedId = sel.id), ResultsActions(onOpenFinding = { opened = it }), split = false)
                }
            }
        }
        rule.onNodeWithText(sel.title).assertIsDisplayed()
        rule.onNodeWithText("Details", ignoreCase = true).performClick()
        assertEquals(sel.id, opened?.id)
    }
}
