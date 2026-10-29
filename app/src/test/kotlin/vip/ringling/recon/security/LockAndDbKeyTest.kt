package vip.ringling.recon.security

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import vip.ringling.recon.data.db.DbEncryption
import vip.ringling.recon.data.db.DbKey
import vip.ringling.recon.data.db.DbKeyUnreadable
import vip.ringling.recon.ui.LockGate
import vip.ringling.recon.ui.LockScreen
import vip.ringling.recon.ui.theme.ReconTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LockGateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lockedContentCanNotBeTappedOrRead() {
        var hits = 0; var unlocks = 0
        var locked by mutableStateOf(true)
        rule.setContent {
            ReconTheme {
                LockGate(locked, lockScreen = { LockScreen(onUnlock = { unlocks++ }) }) {
                    Box(Modifier.fillMaxSize().clickable { hits++ }) { Text("SECRET CONTENT") }
                }
            }
        }
        rule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        rule.onRoot().performTouchInput { click(Offset(width - 10f, height - 10f)) }
        rule.waitForIdle()
        assertEquals(0, hits, "a tap on the lock screen reached the content behind it")
        rule.onNodeWithText("SECRET CONTENT").assertDoesNotExist()
        rule.onNodeWithText("Unlock").performClick()
        assertEquals(1, unlocks, "the lock screen's own button still works")

        locked = false
        rule.waitForIdle()
        rule.onNodeWithText("SECRET CONTENT").assertExists()
        rule.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        rule.waitForIdle()
        assertEquals(1, hits, "unlocked content is usable again")
    }
}

// A plain Application: ReconApp's onCreate opens the real database in the background, which races these file assertions.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DbKeyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("recon_db", Context.MODE_PRIVATE)

    @Test fun noSavedKeyIsMissing() {
        prefs().edit().clear().commit()
        assertEquals(DbKey.Missing, DbKey.load(context))
    }

    @Test fun anUndecryptableKeyIsUnreadableNotMissing() {
        prefs().edit().putString("k", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        assertTrue(DbKey.load(context) is DbKey.Unreadable)
    }

    @Test fun aKeyThatCanNotBeReadNeverDeletesOrReplacesTheDatabase() {
        val blob = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        prefs().edit().putString("k", blob).commit()
        val db = context.getDatabasePath("recon.db").apply { parentFile?.mkdirs() }
        val encrypted = ByteArray(4096) { (it * 31 + 7).toByte() }
        db.writeBytes(encrypted)
        File(db.path + "-wal").writeBytes(byteArrayOf(1, 2, 3))
        assertFailsWith<DbKeyUnreadable> { DbEncryption.prepare(context) }
        assertTrue(db.readBytes().contentEquals(encrypted), "database file untouched")
        assertTrue(File(db.path + "-wal").exists(), "WAL untouched")
        assertEquals(blob, prefs().getString("k", null), "saved key not replaced")
        assertEquals(false, DbEncryption.status?.encrypted)
    }

    @Test fun anEncryptedFileWithNoKeyIsSetAsideNotDeleted() {
        val dir = File(context.cacheDir, "setaside").apply { deleteRecursively(); mkdirs() }
        val db = File(dir, "recon.db").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        DbEncryption.setAside(db)
        val kept = dir.listFiles().orEmpty().single()
        assertTrue(kept.name.startsWith("recon.db.unopenable-"))
        assertTrue(kept.readBytes().contentEquals(byteArrayOf(9, 9, 9)))
    }
}
