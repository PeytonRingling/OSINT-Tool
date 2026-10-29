package vip.ringling.recon

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import vip.ringling.recon.ui.LockGate
import vip.ringling.recon.domain.Incoming
import vip.ringling.recon.domain.IncomingText
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
            LockGate(locked, lockScreen = {
                val s by vm.settings.collectAsStateWithLifecycle()
                ReconTheme(s.themeMode, s.dynamicColor) { LockScreen(onUnlock = ::promptUnlock) }
            }) { ReconRoot(vm) }
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

    /** Text shared into Recon (share sheet), selected text ("Recon" in the selection menu), mailto:/tel: links and the clipboard shortcut fill the search box (links: the Lab's inspector). Nothing is searched until the user presses go. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        when (val r = IncomingText.route(intent.action, intent.type, text, intent.data?.scheme, intent.data?.schemeSpecificPart)) {
            is Incoming.Prefill -> vm.requestPrefill(r.text)
            is Incoming.Link -> vm.requestLink(r.url)
            Incoming.ClipboardOnFocus -> scanClipboardOnFocus = true
            null -> return
        }
        setIntent(Intent(this, MainActivity::class.java))
    }
}
