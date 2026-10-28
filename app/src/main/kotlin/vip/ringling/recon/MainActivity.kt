package vip.ringling.recon

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vip.ringling.recon.ui.AppLockPrompt
import vip.ringling.recon.ui.LockScreen
import vip.ringling.recon.ui.MainViewModel
import vip.ringling.recon.ui.ReconRoot
import vip.ringling.recon.ui.theme.ReconTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var locked by mutableStateOf(false)
    private var authenticating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Lock engages on cold start; it re-engages whenever the app is backgrounded (onStop).
        lifecycleScope.launch {
            vm.settings.map { it.appLock }.distinctUntilChanged().collect { on ->
                if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        locked = runBlockingLockSetting()
        setContent {
            Box {
                ReconRoot(vm)
                if (locked) {
                    val s by vm.settings.collectAsStateWithLifecycle()
                    ReconTheme(s.themeMode, s.dynamicColor) { LockScreen(onUnlock = ::promptUnlock) }
                }
            }
        }
        handleShare(intent)
    }

    private fun runBlockingLockSetting(): Boolean = vm.appLockNow()

    override fun onStart() {
        super.onStart()
        if (locked) promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        if (vm.settings.value.appLock && !isChangingConfigurations) locked = true
    }

    private fun promptUnlock() {
        if (authenticating) return
        if (!AppLockPrompt.canAuthenticate(this)) { locked = false; return }
        authenticating = true
        AppLockPrompt.show(this, onSuccess = { authenticating = false; locked = false }, onFail = { authenticating = false })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private var scanClipboardOnFocus = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Clipboard reads are only allowed while we have focus (Android 10+), so the launcher shortcut defers to here. Any app can fire this intent, so the clipboard is only PUT IN THE SEARCH BOX: nothing is sent anywhere until the user presses go.
        if (hasFocus && scanClipboardOnFocus) {
            scanClipboardOnFocus = false
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            vip.ringling.recon.domain.IncomingText.clean(cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString())?.let(vm::requestPrefill)
        }
    }

    /** Text shared into Recon (share sheet), selected text ("Recon" in the selection menu), mailto:/tel: links and the clipboard shortcut become a search. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val query = when (intent.action) {
            Intent.ACTION_SEND -> if (intent.type?.startsWith("text/") == true) vip.ringling.recon.domain.IncomingText.clean(intent.getStringExtra(Intent.EXTRA_TEXT)) else null
            Intent.ACTION_PROCESS_TEXT -> vip.ringling.recon.domain.IncomingText.clean(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO, Intent.ACTION_DIAL -> vip.ringling.recon.domain.IncomingText.fromUri(intent.data?.scheme, intent.data?.schemeSpecificPart)
            "vip.ringling.recon.SCAN_CLIPBOARD" -> { scanClipboardOnFocus = true; null }
            else -> null
        }
        if (query != null && intent.action != Intent.ACTION_VIEW && vip.ringling.recon.domain.IncomingText.isLink(query)) vm.requestLink(query) else query?.let(vm::requestSearch)
        if (query != null || intent.action == "vip.ringling.recon.SCAN_CLIPBOARD") setIntent(Intent(this, MainActivity::class.java))
    }
}
