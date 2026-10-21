package vip.ringling.recon.ui.lab

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import vip.ringling.recon.data.lab.QrReader

/**
 * Live camera view that reads a QR code / barcode on the device (CameraX preview + ZXing on the luminance plane). No picture is saved or
 * sent anywhere; the first code found is handed to [onResult] once and the camera stops being read.
 */
@Composable
fun QrCameraScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) { onDispose { runCatching { providerHolder[0]?.unbindAll() }; executor.shutdown() } }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            PreviewView(c).also { view ->
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                    providerHolder[0] = provider
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { image ->
                        try {
                            if (!done.get()) {
                                val plane = image.planes[0]
                                val buf = plane.buffer
                                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                QrReader.decodeLuma(bytes, image.width, image.height, plane.rowStride)?.let { text ->
                                    if (done.compareAndSet(false, true)) ContextCompat.getMainExecutor(ctx).execute { onResult(text) }
                                }
                            }
                        } finally { image.close() }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(c))
            }
        },
    )
}
