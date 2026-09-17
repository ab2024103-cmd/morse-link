package com.morselink.app.feature.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.morselink.app.R
import com.morselink.app.core.logging.LogStore

/**
 * Minimal on-device QR scanner (m4): lets the phone scan a QR shown by a PC or
 * another device instead of typing an address. Uses the legacy Camera API so
 * no extra dependencies are needed; decoding runs on zxing's pure-Java reader.
 */
class QrScanActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_RESULT = "scan_result"
        private const val REQ_CAMERA = 41
    }

    private var camera: Camera? = null
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    @Volatile private var surfaceReady = false
    @Volatile private var decoding = false
    @Volatile private var opened = false
    private var delivered = false

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scan)
        findViewById<View>(R.id.scan_close).setOnClickListener { finish() }
        val surface = findViewById<SurfaceView>(R.id.scan_surface)
        surface.holder.addCallback(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openCameraWhenReady()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                openCameraWhenReady()
            } else {
                Toast.makeText(this, R.string.transfer_camera_needed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun openCameraWhenReady() {
        if (surfaceReady) openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (opened) return
        opened = true
        decodeThread = HandlerThread("qr-decode").apply { start() }
        decodeHandler = Handler(decodeThread!!.looper)
        val holder = findViewById<SurfaceView>(R.id.scan_surface).holder
        decodeHandler?.post {
            try {
                camera = Camera.open().apply {
                    val params = parameters
                    val size = supportedPreviewSize(params)
                    params.setPreviewSize(size.first, size.second)
                    parameters = params
                    setDisplayOrientation(90)
                    addCallbackBuffer(ByteArray(size.first * size.second * 3 / 2 + 16))
                    setPreviewCallbackWithBuffer { data, cam ->
                        handleFrame(data, size.first, size.second, cam)
                    }
                }
                camera?.setPreviewDisplay(holder)
                camera?.startPreview()
            } catch (e: Exception) {
                LogStore.w("QR scan: camera failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, R.string.transfer_camera_needed, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun supportedPreviewSize(params: Camera.Parameters): Pair<Int, Int> {
        val sizes = params.supportedPreviewSizes ?: emptyList<Camera.Size>()
        val best = sizes.filter {
            it.width in 320..1280 && it.height in 240..960
        }.minByOrNull { it.width * it.height }
        val chosen = best ?: sizes.firstOrNull()
        return if (chosen != null) Pair(chosen.width, chosen.height) else Pair(640, 480)
    }

    private fun handleFrame(data: ByteArray, width: Int, height: Int, cam: Camera) {
        if (decoding || delivered) {
            cam.addCallbackBuffer(data)
            return
        }
        decoding = true
        val copy = data.copyOf()
        decodeHandler?.post {
            try {
                val found = decodeRotations(copy, width, height)
                if (found != null && !delivered) {
                    delivered = true
                    runOnUiThread { deliver(found) }
                }
            } catch (_: Exception) {
                // keep scanning
            } finally {
                decoding = false
                cam.addCallbackBuffer(data)
            }
        }
    }

    /** Preview data arrives in sensor orientation; try the rotations a hand-held scan needs. */
    private fun decodeRotations(data: ByteArray, width: Int, height: Int): String? {
        val rotated = rotate90(data, width, height)
        val rotatedBack = rotate90(rotated, height, width)
        for (candidate in listOf(
            Pair(rotated, Pair(height, width)),
            Pair(data, Pair(width, height)),
            Pair(rotatedBack, Pair(width, height))
        )) {
            val text = tryDecode(candidate.first, candidate.second.first, candidate.second.second)
            if (text != null) return text
        }
        return null
    }

    private fun tryDecode(data: ByteArray, width: Int, height: Int): String? {
        return try {
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            reader.decodeWithState(bitmap).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotate90(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(data.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + (height - 1 - y)] = data[y * width + x]
            }
        }
        return out
    }

    private fun deliver(text: String) {
        val intent = Intent().putExtra(EXTRA_RESULT, text)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openCamera()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        opened = false
        try {
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.stopPreview()
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
        decodeThread?.quitSafely()
        decodeThread = null
        decodeHandler = null
    }
}
