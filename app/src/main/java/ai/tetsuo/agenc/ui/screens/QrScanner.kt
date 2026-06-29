package ai.tetsuo.agenc.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.content.ContextCompat
import ai.tetsuo.agenc.ui.Onboarding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fullscreen live QR scanner for device pairing — the Android port of iOS `QRScannerView`.
 *
 * Opens the back camera via CameraX, feeds frames to an ML Kit [BarcodeScanner] limited to QR
 * codes, and on the FIRST detected QR extracts the pairing code (mirroring
 * `LinkMacView.extractCode`), fires a haptic, then calls [onCode] followed by [onClose]. The
 * `AtomicBoolean` guard guarantees the callbacks fire exactly once even though analysis frames keep
 * arriving until the camera unbinds.
 *
 * Handles the runtime CAMERA permission with the Activity Result API: it requests on first show and,
 * if denied, shows a "Camera permission needed" panel with a Retry button. The camera is torn down
 * on dispose ([ProcessCameraProvider.unbindAll]).
 *
 * @param onCode fires once with the normalized pairing code extracted from the scanned QR.
 * @param onClose fires when the user taps the close (X) button, or right after a successful scan.
 */
@Composable
fun QrScannerOverlay(
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        hasPermission = granted
        denied = !granted
    }

    // Request on first show if we don't already hold the permission.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasPermission) {
            CameraScanner(onCode = onCode, onClose = onClose)
            ScannerChrome(onClose = onClose)
        } else {
            PermissionPanel(
                denied = denied,
                onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onClose = onClose,
            )
        }
    }
}

/** The live camera surface + ML Kit analysis pipeline. Only mounted once permission is granted. */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraScanner(
    onCode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    // Fires the success callbacks exactly once across the stream of analysis frames.
    val handled = remember { AtomicBoolean(false) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scannerOptions = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    }
    val barcodeScanner: BarcodeScanner = remember { BarcodeScanning.getClient(scannerOptions) }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize(),
    ) {
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(analysisExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val input = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees,
                        )
                        barcodeScanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                val value = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                                if (value != null && handled.compareAndSet(false, true)) {
                                    val code = extractPairCode(value)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCode(code)
                                    onClose()
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.w("QrScanner", "barcode analysis failed", e)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                Log.e("QrScanner", "failed to bind camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Shut the camera down on dispose.
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
            runCatching { barcodeScanner.close() }
            analysisExecutor.shutdown()
        }
    }
}

/** Dark scrim chrome: a centered rounded viewfinder, a close (X) button, and the hint line. */
@Composable
private fun ScannerChrome(onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Centered rounded viewfinder frame.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(248.dp)
                .border(
                    width = 3.dp,
                    color = Onboarding.accent,
                    shape = RoundedCornerShape(24.dp),
                ),
        )

        // Close (X) button, top-left.
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(44.dp)
                .background(Color(0x99000000), shape = RoundedCornerShape(22.dp)),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White,
            )
        }

        // Hint line near the bottom.
        Text(
            text = "Point at the QR from `agenc remote on`",
            color = Color.White,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 48.dp)
                .fillMaxWidth()
                .background(Color(0x66000000), shape = RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Shown when the CAMERA permission is missing/denied. */
@Composable
private fun PermissionPanel(
    denied: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(44.dp)
                .background(Color(0x99000000), shape = RoundedCornerShape(22.dp)),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Camera permission needed",
                color = Onboarding.ink,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (denied) {
                    "Allow camera access to scan the pairing QR code."
                } else {
                    "Grant camera access to scan the pairing QR code."
                },
                color = Onboarding.ink.copy(alpha = 0.65f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Onboarding.accent),
            ) {
                Text("Retry", fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

/**
 * Pull the pairing code out of a scanned value — mirrors iOS `LinkMacView.extractCode`.
 *
 * The scanned value is either a URL like `agenc://pair?c=CODE` (or `?code=CODE`) or a bare code.
 * When it's an `agenc://` URL we read the `c` (or `code`) query parameter; otherwise we treat the
 * whole string as the code. Either way the result is normalized: uppercased, keeping only A–Z and
 * 0–9.
 */
internal fun extractPairCode(raw: String): String {
    val uri = runCatching { Uri.parse(raw) }.getOrNull()
    if (uri?.scheme == "agenc") {
        val value = uri.getQueryParameter("c") ?: uri.getQueryParameter("code")
        if (value != null) return normalizePairCode(value)
    }
    return normalizePairCode(raw)
}
