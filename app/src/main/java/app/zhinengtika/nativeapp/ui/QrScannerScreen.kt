@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.zhinengtika.nativeapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun QrScannerScreen(onBack: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val latestOnCode by rememberUpdatedState(onCode)
    val scope = rememberCoroutineScope()
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var decodingImage by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
        cameraError = if (it) null else "相机权限未授予，仍可选择二维码图片。"
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            decodingImage = true
            cameraError = null
            try {
                val value = withContext(Dispatchers.IO) { decodePhoto(context, uri) }
                if (value == null) cameraError = "图片中没有识别到二维码，请选择清晰的二维码图片。"
                else if (!disposed.get() && delivered.compareAndSet(false, true)) latestOnCode(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                cameraError = "无法读取所选图片，请重新选择。"
            } finally {
                decodingImage = false
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            executor.shutdown()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("本地扫码") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = {
                    cameraError = null
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }, enabled = permissionGranted && !decodingImage) {
                    Icon(Icons.Outlined.Cameraswitch, "切换摄像头")
                }
                IconButton(onClick = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = !decodingImage) { Icon(Icons.Outlined.PhotoLibrary, "从照片选择二维码") }
            },
        )
        if (!permissionGranted) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("相机和图片都在本机解码，扫码图片不会上传。", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) { Text("开启实时扫码") }
                Button(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.padding(top = 8.dp), enabled = !decodingImage) { Text("选择二维码图片") }
            }
        } else {
            key(lensFacing) {
                CameraPreview(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    lensFacing = lensFacing,
                    executor = executor,
                    delivered = delivered,
                    onCode = { if (!disposed.get()) latestOnCode(it) },
                    onError = { cameraError = it },
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (decodingImage) CircularProgressIndicator()
            Text("识别后会填入题卡编号，查询前可核对。", style = MaterialTheme.typography.bodySmall)
            cameraError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    lensFacing: Int,
    executor: ExecutorService,
    delivered: AtomicBoolean,
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val disposed = remember { AtomicBoolean(false) }
    val latestOnCode by rememberUpdatedState(onCode)
    val latestOnError by rememberUpdatedState(onError)
    val provider = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            provider[0]?.unbindAll()
        }
    }
    AndroidView(modifier = modifier, factory = { viewContext ->
        PreviewView(viewContext).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            val view = this
            val future = ProcessCameraProvider.getInstance(viewContext)
            val main = ContextCompat.getMainExecutor(viewContext)
            future.addListener({
                if (!disposed.get()) {
                    try {
                        val cameras = future.get()
                        provider[0] = cameras
                        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                        check(cameras.hasCamera(selector))
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        val reader = qrReader()
                        var lastFrameAt = 0L
                        analysis.setAnalyzer(executor) { proxy ->
                            try {
                                val now = SystemClock.elapsedRealtime()
                                if (!disposed.get() && !delivered.get() && now - lastFrameAt >= 180) {
                                    lastFrameAt = now
                                    val value = decodeFrame(proxy, reader)
                                    if (value != null && !disposed.get() && delivered.compareAndSet(false, true)) main.execute {
                                        if (!disposed.get()) latestOnCode(value)
                                        else delivered.set(false)
                                    }
                                }
                            } finally {
                                proxy.close()
                            }
                        }
                        cameras.unbindAll()
                        cameras.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    } catch (_: Exception) {
                        latestOnError("当前摄像头不可用，请切换摄像头或选择二维码图片。")
                    }
                }
            }, main)
        }
    })
}

private fun qrReader() = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
}

private fun decodeFrame(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer.duplicate()
    val offset = buffer.position()
    val width = image.width
    val height = image.height
    val luminance = ByteArray(width * height)
    for (y in 0 until height) for (x in 0 until width) {
        val index = offset + y * plane.rowStride + x * plane.pixelStride
        if (index >= buffer.limit()) return null
        luminance[y * width + x] = buffer.get(index)
    }
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)))).text
    } catch (_: ReaderException) { null } finally { reader.reset() }
}

private fun decodePhoto(context: Context, uri: Uri): String? {
    val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (maxOf(info.size.width / sample, info.size.height / sample) > 2048) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image")
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Image unavailable")
    }
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        val reader = qrReader()
        try { reader.decodeWithState(binary).text } catch (_: ReaderException) { null } finally { reader.reset() }
    } finally { bitmap.recycle() }
}
