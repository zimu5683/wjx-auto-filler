package com.wjx.autofill.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.wjx.autofill.R
import com.wjx.autofill.databinding.ActivityQrScanBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 实时扫码页：CameraX PreviewView + ImageAnalysis，自写 Analyzer 用 zxing 解码。
 *
 * 相机权限**只在进入本页时**申请；被拒绝时给出可点「去设置」的提示，
 * 而不是让用户卡在一个永远黑屏的取景框前。
 */
class QrScanActivity : AppCompatActivity(), ImageAnalysis.Analyzer {

    companion object {
        const val EXTRA_SCAN_RESULT = "extra_scan_result"

        fun intent(context: android.content.Context): Intent =
            Intent(context, QrScanActivity::class.java)
    }

    private lateinit var binding: ActivityQrScanBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private var cameraStarting = false
    private var requestedOnce = false
    private var handled = false
    private var torchOn = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.permissionPanel.visibility = View.GONE
                startCamera()
            } else {
                showPermissionPanel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.settingsButton.setOnClickListener { openAppSettings() }
        binding.retryButton.setOnClickListener { requestCameraPermission() }

        analysisExecutor = Executors.newSingleThreadExecutor()
        requestCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            binding.permissionPanel.visibility = View.GONE
            startCamera()
        } else if (requestedOnce) {
            showPermissionPanel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
            // 相机已释放时忽略
        }
        analysisExecutor?.shutdown()
        analysisExecutor = null
        mainHandler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- 权限

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (hasCameraPermission()) {
            binding.permissionPanel.visibility = View.GONE
            startCamera()
            return
        }
        requestedOnce = true
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionPanel() {
        binding.permissionMessage.text = getString(R.string.camera_permission_message)
        binding.permissionPanel.visibility = View.VISIBLE
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: Throwable) {
            binding.permissionMessage.text = getString(R.string.camera_permission_manual)
        }
    }

    // ---------------------------------------------------------------- 相机

    private fun startCamera() {
        if (cameraStarting || cameraProvider != null) return
        if (!hasCameraPermission()) return
        cameraStarting = true
        val executor = analysisExecutor ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraStarting = false
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(binding.previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor, this)
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                binding.torchButton.isEnabled = hasFlash
                binding.torchButton.alpha = if (hasFlash) 1f else 0.5f
            } catch (t: Throwable) {
                binding.scanHint.text =
                    getString(R.string.scan_camera_error, t.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val active = camera ?: return
        if (active.cameraInfo.hasFlashUnit() != true) return
        torchOn = !torchOn
        active.cameraControl.enableTorch(torchOn)
        binding.torchButton.text =
            getString(if (torchOn) R.string.torch_on else R.string.torch_off)
    }

    // ---------------------------------------------------------------- 解码

    override fun analyze(image: ImageProxy) {
        try {
            if (handled) return
            val text = QrDecoder.decodeImageProxy(image)
            if (!text.isNullOrBlank()) {
                handled = true
                mainHandler.post { finishWithResult(text) }
            }
        } catch (_: Throwable) {
            // 单帧解码失败属正常情况，忽略后等下一帧。
        } finally {
            image.close()
        }
    }

    private fun finishWithResult(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, text))
        finish()
    }
}
