package vip.ringling.recon.ui

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Device-credential gate using the framework BiometricPrompt (no extra dependency). Fails open only if the device has no screen lock at all. */
object AppLockPrompt {
    fun canAuthenticate(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true // API 29: DEVICE_CREDENTIAL flag unavailable; prompt itself reports errors
        val bm = context.getSystemService(BiometricManager::class.java)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun show(context: Context, onSuccess: () -> Unit, onFail: () -> Unit) {
        val builder = BiometricPrompt.Builder(context).setTitle("Unlock Recon")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFail()
        })
    }
}

/**
 * Shows [content] and, while [locked], covers it with [lockScreen]. The cover takes every touch (only its own buttons react) and the content is
 * removed from the accessibility tree, so nothing behind the lock can be tapped blind or read by a screen reader. The content stays composed,
 * so unlocking returns to exactly where the user was.
 */
@Composable
fun LockGate(locked: Boolean, lockScreen: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box {
        Box(if (locked) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        if (locked) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() } }
                },
            ) { lockScreen() }
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Text("Recon is locked", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Button(onClick = onUnlock, modifier = Modifier.heightIn(min = 52.dp)) { Text("Unlock") }
        }
    }
}
