package com.xiaopacai.child.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.PlanarYUVLuminanceSource
import com.xiaopacai.child.ui.theme.XiaopacaiTheme
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * [REQ] 通用二维码扫描器（CameraX + ZXing）
 *
 * 用途：家长端扫码（Web 登录二维码/儿童端二维码）、儿童端扫码（家长端配对二维码）。
 * 返回：RESULT_OK + extra "qr_result" = 二维码文本。
 * 测试：debug 构建可用 extra "test_result" 注入结果，跳过相机（模拟器无真实相机）。
 */
class QrScannerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "QrScanner"
        const val EXTRA_RESULT = "qr_result"
        const val EXTRA_TEST_RESULT = "test_result"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var lastResult = ""
    private var cameraStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            ensureCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 测试注入：跳过相机直接返回结果
        intent.getStringExtra(EXTRA_TEST_RESULT)?.let {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, it))
            finish()
            return
        }

        setContent {
            XiaopacaiTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(
                                factory = { ctx ->
                                    PreviewView(ctx).also { previewView ->
                                        this@QrScannerActivity.previewView = previewView
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            // 扫码框
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "将二维码置于框内",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                        Button(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            Text("取消")
                        }
                    }
                }
            }
        }

        // [FIX] 相机启动不能依赖 setContent 后的同步时序：Compose 首帧可能异步，
        // 此时 previewView 尚未创建，直接 startCamera 会静默失败（黑屏）。
        ensureCamera()
    }

    private var previewView: PreviewView? = null

    /**
     * 确保相机启动：等待预览视图就绪 + 相机权限就绪，带自动重试。
     */
    private fun ensureCamera(retry: Int = 0) {
        if (cameraStarted || isFinishing) return
        val pv = previewView
        if (pv == null) {
            // Compose 视图尚未创建，稍后重试（最多 40 次 x 50ms = 2s）
            if (retry < 40) {
                Log.i(TAG, "预览视图未就绪，重试 $retry")
                mainHandler.postDelayed({ ensureCamera(retry + 1) }, 50L)
            } else {
                Log.e(TAG, "预览视图创建超时，无法启动相机")
                Toast.makeText(this, "相机初始化超时，请重试", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        cameraStarted = true
        Log.i(TAG, "开始绑定相机: ${pv.width}x${pv.height}")
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    decode(image)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                Log.i(TAG, "相机已绑定")
            } catch (e: Exception) {
                Log.e(TAG, "启动相机失败: ${e.message}", e)
                Toast.makeText(this, "相机启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                cameraStarted = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun decode(image: ImageProxy) {
        try {
            val text = decodeQrFromImage(image)
            if (text != null && text.isNotBlank() && text != lastResult) {
                lastResult = text
                image.close()
                runOnUiThread {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, text))
                    finish()
                }
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "解码失败: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun decodeQrFromImage(image: ImageProxy): String? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val planes = image.planes
        val width = image.width
        val height = image.height
        if (planes.size < 3) return null

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val nv21 = ByteArray(width * height * 3 / 2)

        // Y
        copyPlaneToNv21(yPlane, nv21, 0, width, height)
        // UV interleaved
        copyUvToNv21(uPlane, vPlane, nv21, width * height, width, height)

        val source = PlanarYUVLuminanceSource(nv21, width, height, 0, 0, width, height, false)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        return try {
            MultiFormatReader().apply { setHints(hints) }.decodeWithState(binaryBitmap).text
        } catch (e: NotFoundException) {
            null
        }
    }

    /** 将单色平面按行拷贝到 NV21 目标数组（处理 rowStride 对齐与 pixelStride 采样） */
    private fun copyPlaneToNv21(
        plane: ImageProxy.PlaneProxy,
        target: ByteArray,
        offset: Int,
        rowWidth: Int,
        rowCount: Int
    ) {
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var dst = offset
        for (row in 0 until rowCount) {
            var src = row * rowStride
            val rowEnd = src + rowWidth * pixelStride
            while (src < rowEnd && dst < target.size) {
                target[dst++] = buffer.get(src)
                src += pixelStride
            }
        }
    }

    /** 将 U/V 两个半分辨率平面交错写入 NV21 的 UV 区（VU 顺序：NV21 为 V 在前） */
    private fun copyUvToNv21(
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy,
        target: ByteArray,
        offset: Int,
        rowWidth: Int,
        height: Int
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uvWidth = rowWidth / 2
        val uvHeight = height / 2
        var dst = offset
        for (row in 0 until uvHeight) {
            var uSrc = row * uRowStride
            var vSrc = row * vRowStride
            var col = 0
            while (col < uvWidth && dst + 1 < target.size) {
                target[dst++] = vBuffer.get(vSrc)
                target[dst++] = uBuffer.get(uSrc)
                uSrc += uPixelStride
                vSrc += vPixelStride
                col++
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdown()
    }
}
