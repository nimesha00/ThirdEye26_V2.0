package com.sliit.thirdeye

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Live object detection ("Around me"). Camera frames go to the on-phone MediaPipe detector
 * on a background thread; the most important object is spoken with where it is and how
 * close it looks, e.g. "person ahead, close" or "chair on your left".
 */
class ObjDetection : AppCompatActivity() {

    private lateinit var tts: TextToSpeech
    @Volatile private var ttsReady = false

    private lateinit var imageView: ImageView
    private lateinit var textureView: TextureView
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var cameraHandler: Handler

    // Detection runs on its own thread; running it on the UI thread froze the screen.
    private lateinit var inferenceThread: HandlerThread
    private lateinit var inferenceHandler: Handler
    @Volatile private var inferenceBusy = false
    // Created on the inference thread; null if the model file is missing.
    private var detector: OnDeviceDetector? = null
    private var detectorFailed = false

    // If the previous screen is still releasing the camera, the first open can deliver
    // no frames at all. Reopen if nothing arrives within this time.
    @Volatile private var lastFrameAt = 0L
    private var reopenAttempts = 0
    private val noFrameTimeoutMs = 3000L
    private val maxReopenAttempts = 2

    // Announcement pacing: repeat the same thing at most every few seconds.
    private var lastSpoken = ""
    private var lastSpokenAt = 0L
    private val repeatAfterMs = 5000L

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFD600")
        isAntiAlias = true
    }
    private val labelBackground = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD600")
    }
    private val labelText = Paint().apply {
        color = Color.BLACK
        isAntiAlias = true
        isFakeBoldText = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_obj_detection)

        imageView = findViewById(R.id.imageView2)
        textureView = findViewById(R.id.textureView2)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val cameraThread = HandlerThread("cameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread.looper)
        inferenceThread = HandlerThread("inferenceThread").also { it.start() }
        inferenceHandler = Handler(inferenceThread.looper)

        // Load the model off the UI thread; it takes a moment.
        inferenceHandler.post {
            detector = OnDeviceDetector.create(this, 0.5f, 5)
            if (detector == null) {
                detectorFailed = true
                speak("Sorry, the object detector is missing from this version of the app.", urgent = true)
            }
        }

        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale("en", "LK")
                tts.setSpeechRate(1f)
                ttsReady = true
                speak("Live object detection is on. Press back to stop.", urgent = true)
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                // Wait for the permission result instead of opening without permission.
                if (hasCameraPermission()) openCamera()
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {}

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean = false

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                lastFrameAt = SystemClock.elapsedRealtime()
                // Skip frames while the detector is still busy with the previous one.
                if (inferenceBusy || detectorFailed || isFinishing || textureView.width == 0) return
                // Half size is plenty for the model and much cheaper to copy on the UI thread.
                val frame = textureView.getBitmap(textureView.width / 2, textureView.height / 2) ?: return
                inferenceBusy = true
                inferenceHandler.post { runDetection(frame) }
            }
        }

        if (!hasCameraPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    /** Runs on the inference thread. */
    private fun runDetection(frame: Bitmap) {
        try {
            val d = detector ?: return
            val found = d.detect(frame)

            val annotated = frame.copy(Bitmap.Config.ARGB_8888, true)
            drawBoxes(Canvas(annotated), found, annotated.height)
            runOnUiThread { if (!isDestroyed) imageView.setImageBitmap(annotated) }

            found.firstOrNull()?.let { announce(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed on a frame", e)
        } finally {
            inferenceBusy = false
        }
    }

    private fun drawBoxes(canvas: Canvas, found: List<OnDeviceDetector.Found>, height: Int) {
        boxPaint.strokeWidth = height / 120f
        labelText.textSize = height / 28f
        val pad = labelText.textSize * 0.3f
        for (f in found) {
            canvas.drawRect(f.box, boxPaint)
            val text = f.spokenName()
            val textWidth = labelText.measureText(text)
            val top = (f.box.top - labelText.textSize - 2 * pad).coerceAtLeast(0f)
            canvas.drawRect(f.box.left, top, f.box.left + textWidth + 2 * pad,
                top + labelText.textSize + 2 * pad, labelBackground)
            canvas.drawText(text, f.box.left + pad, top + labelText.textSize + pad * 0.5f, labelText)
        }
    }

    /**
     * Speaks the most important object, e.g. "person ahead, close". New things and anything
     * very close are spoken straight away; the same thing is repeated at most every few seconds.
     */
    private fun announce(f: OnDeviceDetector.Found) {
        val closeness = f.closeness()
        val phrase = buildString {
            append(f.spokenName()).append(' ').append(f.position())
            if (closeness.isNotEmpty()) append(", ").append(closeness)
        }
        val now = SystemClock.elapsedRealtime()
        val urgent = closeness == "very close" && OnDeviceDetector.isHazard(f.label)
        val isNew = phrase != lastSpoken
        if (!isNew && now - lastSpokenAt < repeatAfterMs) return
        if (!urgent && ttsReady && tts.isSpeaking) return
        lastSpoken = phrase
        lastSpokenAt = now
        speak(phrase, urgent)
    }

    private fun speak(text: String, urgent: Boolean) {
        if (!ttsReady) return
        // Urgent warnings interrupt; everything else waits its turn.
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "detection")
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        cameraManager.openCamera(cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                val surface = Surface(textureView.surfaceTexture)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                request.addTarget(surface)
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        session.setRepeatingRequest(request.build(), null, null)
                        scheduleNoFrameCheck()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera session configuration failed")
                    }
                }, cameraHandler)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "Camera error $error")
                device.close()
            }
        }, cameraHandler)
    }

    /** Reopens the camera if no preview frame arrives soon after it starts streaming. */
    private fun scheduleNoFrameCheck() {
        val startedAt = SystemClock.elapsedRealtime()
        textureView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (lastFrameAt < startedAt && reopenAttempts < maxReopenAttempts) {
                reopenAttempts++
                Log.w(TAG, "No camera frames after ${noFrameTimeoutMs}ms; reopening (attempt $reopenAttempts)")
                if (::cameraDevice.isInitialized) cameraDevice.close()
                openCamera()
            }
        }, noFrameTimeoutMs)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // Don't re-request: that loops forever once the user picks "Don't ask again".
            Toast.makeText(this, "Object detection needs camera permission", Toast.LENGTH_LONG).show()
            finish()
        } else if (textureView.isAvailable) {
            openCamera()
        }
    }

    override fun onDestroy() {
        // Close the detector on its own thread, after any frame still in progress.
        inferenceHandler.post { detector?.close(); detector = null }
        inferenceThread.quitSafely()
        // Release the camera, or it can stay locked for the next screen that needs it.
        if (::cameraDevice.isInitialized) cameraDevice.close()
        cameraHandler.looper.quitSafely()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ObjDetection"
        const val REQUEST_CAMERA = 101
    }
}
