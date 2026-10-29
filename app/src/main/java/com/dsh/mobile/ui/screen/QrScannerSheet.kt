package com.dsh.mobile.ui.screen

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.ui.motion.Motion
import com.dsh.mobile.ui.motion.rememberTimeline
import com.dsh.mobile.ui.theme.DshAccents
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.Executors
import com.dsh.mobile.ui.motion.pressScale
import androidx.compose.foundation.clickable

/**
 * Camera QR scanner for the pairing link.
 *
 * The DSH desktop panel already renders the pairing link as a QR code, so scanning
 * it removes the worst step of onboarding: getting a long URL off a computer and
 * into a phone without retyping it or bouncing through a chat app.
 *
 * ## Why ML Kit
 *
 * The bundled barcode model ships inside the APK, so this works on a device with no
 * Google Play services at all — which is the normal state of a mainland HyperOS
 * phone. The unbundled ZXing path would depend on Play services being present.
 *
 * @param onScanned receives the first decoded URL; the caller closes the sheet.
 * @param onCancel dismissed without scanning.
 */
@Composable
fun QrScannerSheet(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var consumed by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            error = "需要相机权限才能扫码"
            RunLog.warn("QrScan", "camera permission denied")
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // One sweep of the framing brackets, matching the rest of the app's motion.
    val timeline = rememberTimeline(900)
    val frameIn = timeline.at(0, 420)
    val hintIn = timeline.at(260, 800)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D11)),
    ) {
        if (hasPermission) {
            CameraPreview(
                onBarcode = { raw ->
                    // Guard against the analysis thread firing repeatedly for the
                    // same code while the caller is still dismissing the sheet.
                    if (consumed) return@CameraPreview
                    consumed = true
                    RunLog.info("QrScan", "decoded QR; chars=${raw.length}; payload excluded")
                    onScanned(raw)
                },
                onError = {
                    error = it
                    RunLog.error("QrScan", it)
                },
                lifecycleOwner = lifecycleOwner,
            )
        }

        // Viewfinder: a soft dark scrim outside a rounded square, with corner
        // brackets that draw themselves in. The scrim is punched out with a
        // difference blend rather than four rectangles, so it stays crisp on any
        // aspect ratio.
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.68f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f
            val r = side * 0.08f

            val scrim = Color.Black.copy(alpha = 0.55f)
            // Four bands around the hole.
            drawRect(scrim, size = androidx.compose.ui.geometry.Size(size.width, top))
            drawRect(
                scrim,
                topLeft = Offset(0f, top + side),
                size = androidx.compose.ui.geometry.Size(size.width, size.height - top - side),
            )
            drawRect(
                scrim,
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(left, side),
            )
            drawRect(
                scrim,
                topLeft = Offset(left + side, top),
                size = androidx.compose.ui.geometry.Size(size.width - left - side, side),
            )

            // Brackets.
            val len = side * 0.18f
            val stroke = 3.dp.toPx()
            val p = frameIn
            val c = DshAccents.accent.copy(alpha = 0.65f + 0.35f * p)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(c, Offset(x, y), Offset(x + dx * len * p, y), stroke, StrokeCap.Round)
                drawLine(c, Offset(x, y), Offset(x, y + dy * len * p), stroke, StrokeCap.Round)
            }
            corner(left, top, 1f, 1f)
            corner(left + side, top, -1f, 1f)
            corner(left, top + side, 1f, -1f)
            corner(left + side, top + side, -1f, -1f)

            // Hairline outline so the target reads even before the brackets land.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(side, side),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 56.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "扫描配对二维码",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "对准电脑上 DSH「远程访问」面板里的二维码",
                color = Color(0xFFB6BEC9),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 0.dp).let { it },
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val msg = error
            if (msg != null) {
                Text(text = msg, color = DshAccents.error, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
            }
            ScanCancelButton(onCancel = onCancel)
        }
    }
}

@Composable
private fun ScanCancelButton(onCancel: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onCancel,
            )
            .padding(horizontal = 26.dp, vertical = 11.dp),
    ) {
        Text(text = "取消", color = Color(0xFFD5DAE1), fontSize = 14.sp)
    }
}

/**
 * CameraX preview plus a barcode analysis pipeline.
 *
 * Bind/unbind is tied to the composition so leaving the sheet always releases the
 * camera — a leaked camera session keeps the privacy indicator on and blocks other
 * apps from using it.
 */
@Composable
private fun CameraPreview(
    onBarcode: (String) -> Unit,
    onError: (String) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner: BarcodeScanner = remember {
        BarcodeScannerOptions.Builder()
            // QR only: the pairing payload is a QR code, and restricting formats
            // makes decoding noticeably faster.
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
            .let { BarcodeScanning.getClient(it) }
    }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        future.addListener({
            runCatching {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    // Keep only the newest frame: a queue of stale frames adds
                    // latency without improving the chance of a decode.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(executor) { proxy ->
                            analyzeFrame(proxy, scanner, onBarcode)
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }.onFailure {
                onError("无法启动相机：${it.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
            runCatching { scanner.close() }
            executor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Decode one frame, then always release it. */
private fun analyzeFrame(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    onBarcode: (String) -> Unit,
) {
    val mediaImage = proxy.image
    if (mediaImage == null) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { codes ->
            val value = codes.firstNotNullOfOrNull { it.rawValue }
            if (value != null) onBarcode(value)
        }
        .addOnCompleteListener { proxy.close() }
}
