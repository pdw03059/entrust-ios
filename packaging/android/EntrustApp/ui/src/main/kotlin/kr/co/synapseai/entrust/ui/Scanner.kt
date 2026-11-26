// 카메라 QR 읽기(CameraX + ZXing, 구글 플레이 서비스 없음). 같은 글자는 2초 안에 다시 넘기지 않는다.
package kr.co.synapseai.entrust.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8"))
    }
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val w = image.width; val h = image.height
            val y = ByteArray(w * h)
            if (rowStride == w) buf.get(y, 0, w * h) else for (r in 0 until h) { buf.position(r * rowStride); buf.get(y, r * w, w) }
            val src = PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false)
            val text = try { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }
            catch (e: NotFoundException) { runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src.invert()))).text }.getOrNull() }
            finally { reader.reset() }
            if (text != null) onText(text)
        } catch (_: Exception) {
        } finally { image.close() }
    }
}

/** 카메라 화면 + QR 읽기. 권한이 없으면 묻고, 거절하면 안내 글을 보인다. */
@Composable
fun QRScanner(modifier: Modifier = Modifier, active: Boolean = true, onScan: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val latest by rememberUpdatedState(onScan)
    val last = remember { mutableStateOf("" to 0L) }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (!granted) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (denied) "카메라 권한이 꺼져 있어요.\n설정 › 앱 › ENTRUST 관리자 › 권한 › 카메라를 켜 주세요." else "카메라 권한을 물어보는 중…",
                    color = Color.White, textAlign = TextAlign.Center,
                )
            }
        } else if (active) {
            val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            DisposableEffect(owner) {
                val exec = Executors.newSingleThreadExecutor()
                val main = ContextCompat.getMainExecutor(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(exec, QrAnalyzer { text ->
                        main.execute {
                            val now = System.currentTimeMillis()
                            val (lt, lat) = last.value
                            if (text != lt || now - lat > 2000) { last.value = text to now; latest(text) }
                        }
                    })
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, main)
                onDispose {
                    runCatching { future.get().unbindAll() }
                    exec.shutdown()
                }
            }
        }
    }
}
