package com.sliit.thirdeye;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * "Around me" with real distances. An ARCore session shows the camera and estimates depth;
 * camera images go to the on-phone detector (OnDeviceDetector) on a background thread, and
 * the depth around each object's centre gives "person ahead, about 2 metres".
 *
 * If ARCore or its depth support is missing, or the session fails, it says why and opens the
 * plain ObjDetection screen instead.
 *
 * Threads: UI (lifecycle, speech, overlay), GL (session.update, drawing, depth sampling),
 * inference (YUV conversion and detection).
 */
public class DepthDetectionActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "DepthDetection";
    private static final int REQUEST_CAMERA = 102;
    /** At most about five detections a second; enough for walking pace, easy on the battery. */
    private static final long MIN_FRAME_INTERVAL_MS = 200;
    private static final long REPEAT_AFTER_MS = 5000;
    private static final long NO_FRAME_TIMEOUT_MS = 4000;
    private static final long FALLBACK_SPEECH_TIMEOUT_MS = 8000;
    /** 5 x 5 depth pixels around a box centre. */
    private static final int DEPTH_PATCH_RADIUS = 2;

    private GLSurfaceView surfaceView;
    private BoxOverlayView overlay;
    private final CameraBackgroundRenderer background = new CameraBackgroundRenderer();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Created, resumed, paused and closed on the UI thread; updated on the GL thread while running.
    private Session session;
    private volatile boolean sessionRunning;
    private int resumeAttempts;
    private boolean noFrameRetried;
    private boolean textureSet;
    private volatile boolean geometryChanged;
    private volatile int viewWidth;
    private volatile int viewHeight;
    private volatile int displayRotation = Surface.ROTATION_0;
    private volatile int sensorOrientation = 90;
    private long lastCameraTimestamp;
    private volatile long lastFrameAt;
    private long lastFrameSentAt;

    private HandlerThread inferenceThread;
    private Handler inferenceHandler;
    // Created, used and closed on the inference thread; null if the model file is missing.
    private OnDeviceDetector detector;
    private volatile boolean detectorReady;
    private volatile boolean inferenceBusy;
    /** Latest detections waiting for depth, handed from the inference thread to the GL thread. */
    private volatile Detections pending;

    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private String lastKey = "";
    private boolean lastUrgent;
    private long lastSpokenAt;

    private volatile boolean fallingBack;
    private String fallbackReason;
    private boolean fallbackLaunched;
    private boolean fallbackOpensPlainScreen = true;
    private boolean pausedWhileLeaving;

    /** Detections from one camera image: names/positions upright, boxes in camera image pixels. */
    private static final class Detections {
        final List<OnDeviceDetector.Found> found;
        final List<RectF> imageBoxes;

        Detections(List<OnDeviceDetector.Found> found, List<RectF> imageBoxes) {
            this.found = found;
            this.imageBoxes = imageBoxes;
        }
    }

    /** YUV planes copied out of an ARCore camera image so it can be closed straight away. */
    private static final class YuvFrame {
        int width, height, yRowStride, yPixelStride, uvRowStride, uvPixelStride;
        byte[] y, u, v;

        static YuvFrame copyOf(Image image) {
            Image.Plane[] planes = image.getPlanes();
            YuvFrame f = new YuvFrame();
            f.width = image.getWidth();
            f.height = image.getHeight();
            f.yRowStride = planes[0].getRowStride();
            f.yPixelStride = planes[0].getPixelStride();
            f.uvRowStride = planes[1].getRowStride();
            f.uvPixelStride = planes[1].getPixelStride();
            f.y = bytes(planes[0].getBuffer());
            f.u = bytes(planes[1].getBuffer());
            f.v = bytes(planes[2].getBuffer());
            return f;
        }

        private static byte[] bytes(ByteBuffer buffer) {
            ByteBuffer b = buffer.duplicate();
            b.rewind();
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_depth_detection);

        surfaceView = findViewById(R.id.depthSurface);
        overlay = findViewById(R.id.depthOverlay);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        inferenceThread = new HandlerThread("depthInference");
        inferenceThread.start();
        inferenceHandler = new Handler(inferenceThread.getLooper());
        // Load the model off the UI thread; it takes a moment.
        inferenceHandler.post(() -> {
            detector = OnDeviceDetector.create(this, 0.5f, 5);
            if (detector == null) {
                speak("Sorry, the object detector is missing from this version of the app.", true);
            } else {
                detectorReady = true;
            }
        });

        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(new Locale("en", "LK"));
            tts.setSpeechRate(1f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    if ("fallback".equals(utteranceId)) mainHandler.post(() -> launchFallback());
                }

                @Override
                public void onError(String utteranceId) {
                    if ("fallback".equals(utteranceId)) mainHandler.post(() -> launchFallback());
                }
            });
            ttsReady = true;
            if (fallingBack) {
                speakFallbackReason();
            } else {
                speak("Live object detection with distances is on. Distances are estimates. Press back to stop.", true);
            }
        });

        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        surfaceView.onResume();
        if (fallingBack) {
            // The user left mid-explanation and came back; carry on to the next screen now.
            if (pausedWhileLeaving) launchFallback();
            return;
        }
        if (!hasCameraPermission()) return;
        if (session == null && !createSession()) return;
        resumeAttempts = 0;
        resumeSession();
    }

    /** Creates and configures the ARCore session; falls back (and returns false) if it can't. */
    private boolean createSession() {
        try {
            Session s = new Session(this);
            if (!s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                s.close();
                AroundMe.rememberNoDepth(this);
                fallBack("This phone cannot measure distances. Using live detection without distances.");
                return false;
            }
            Config config = new Config(s);
            config.setDepthMode(Config.DepthMode.AUTOMATIC);
            config.setFocusMode(Config.FocusMode.AUTO);
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            s.configure(config);
            sensorOrientation = readSensorOrientation(s.getCameraConfig().getCameraId());
            session = s;
            return true;
        } catch (UnavailableArcoreNotInstalledException e) {
            fallBack("To hear distances, install Google Play Services for AR from the Play Store. "
                    + "Using live detection without distances for now.");
        } catch (UnavailableApkTooOldException e) {
            fallBack("To hear distances, update Google Play Services for AR from the Play Store. "
                    + "Using live detection without distances for now.");
        } catch (UnavailableDeviceNotCompatibleException e) {
            AroundMe.rememberNoDepth(this);
            fallBack("This phone cannot measure distances. Using live detection without distances.");
        } catch (UnavailableSdkTooOldException e) {
            fallBack("Distances need a newer version of Third Eye. Using live detection without distances.");
        } catch (Exception e) {
            Log.e(TAG, "Could not start ARCore", e);
            fallBack("Distance measuring could not start. Using live detection without distances.");
        }
        return false;
    }

    /** Resumes the session, retrying briefly if the previous screen still holds the camera. */
    private void resumeSession() {
        if (session == null || fallingBack || isFinishing()) return;
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            if (resumeAttempts++ < 2) {
                Log.w(TAG, "Camera busy; retrying (attempt " + resumeAttempts + ")");
                mainHandler.postDelayed(this::resumeSession, 700);
            } else {
                fallBack("The camera is busy. Using live detection without distances.");
            }
            return;
        } catch (RuntimeException e) {
            Log.e(TAG, "Session resume failed", e);
            fallBack("Distance measuring stopped working. Using live detection without distances.");
            return;
        }
        sessionRunning = true;
        scheduleNoFrameCheck();
    }

    /** If no camera image arrives after starting, restart the session once, then fall back. */
    private void scheduleNoFrameCheck() {
        final long startedAt = SystemClock.elapsedRealtime();
        mainHandler.postDelayed(() -> {
            if (!sessionRunning || fallingBack || isFinishing() || isDestroyed()) return;
            if (lastFrameAt >= startedAt) return;
            if (!noFrameRetried) {
                noFrameRetried = true;
                Log.w(TAG, "No camera frames after " + NO_FRAME_TIMEOUT_MS + "ms; restarting the session");
                pauseSession();
                resumeSession();
            } else {
                fallBack("The camera did not start. Using live detection without distances.");
            }
        }, NO_FRAME_TIMEOUT_MS);
    }

    private void pauseSession() {
        sessionRunning = false;
        if (session == null) return;
        // GLSurfaceView.onPause waits for the GL thread, so session.update() is not running.
        surfaceView.onPause();
        session.pause();
        surfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sessionRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        // GLSurfaceView.onPause waits for the GL thread to stop drawing before we pause ARCore.
        surfaceView.onPause();
        if (session != null) session.pause();
        if (fallingBack) pausedWhileLeaving = true;
    }

    // ---------- GL thread ----------

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        background.create();
        textureSet = false;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        viewWidth = width;
        viewHeight = height;
        displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        geometryChanged = true;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session s = session;
        if (!sessionRunning || s == null) return;
        try {
            if (!textureSet) {
                s.setCameraTextureName(background.textureId());
                textureSet = true;
            }
            if (geometryChanged) {
                s.setDisplayGeometry(displayRotation, viewWidth, viewHeight);
                geometryChanged = false;
            }
            Frame frame = s.update();
            if (frame.getTimestamp() != 0 && frame.getTimestamp() != lastCameraTimestamp) {
                lastCameraTimestamp = frame.getTimestamp();
                lastFrameAt = SystemClock.elapsedRealtime();
            }
            background.draw(frame);

            Detections d = pending;
            if (d != null) {
                pending = null;
                measureAndAnnounce(frame, d);
            }
            maybeSendFrame(frame);
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera lost", e);
            fallBack("The camera stopped. Using live detection without distances.");
        } catch (Throwable t) {
            // One bad frame must not crash the screen.
            Log.e(TAG, "Frame failed", t);
        }
    }

    /** Hands a copy of the camera image to the detector when it is free and enough time has passed. */
    private void maybeSendFrame(Frame frame) {
        if (!detectorReady || inferenceBusy || fallingBack) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastFrameSentAt < MIN_FRAME_INTERVAL_MS) return;
        Image image;
        try {
            image = frame.acquireCameraImage();
        } catch (NotYetAvailableException e) {
            return;
        }
        final YuvFrame yuv;
        try {
            yuv = YuvFrame.copyOf(image);
        } finally {
            image.close();
        }
        lastFrameSentAt = now;
        inferenceBusy = true;
        inferenceHandler.post(() -> runDetection(yuv));
    }

    /** Reads depth around each object's centre, updates the boxes on screen and speaks. */
    private void measureAndAnnounce(Frame frame, Detections d) {
        int n = d.found.size();
        float[] metres = new float[n];
        List<RectF> viewBoxes = new ArrayList<>(n);
        List<String> labels = new ArrayList<>(n);

        Image depth = null;
        try {
            depth = frame.acquireDepthImage16Bits();
        } catch (NotYetAvailableException e) {
            // Depth needs a moment of movement after starting; use the closeness words until then.
        }
        try {
            float[] point = new float[2];
            float[] tex = new float[2];
            float[] corners = new float[4];
            float[] view = new float[4];
            for (int i = 0; i < n; i++) {
                RectF r = d.imageBoxes.get(i);
                metres[i] = -1f;
                if (depth != null) {
                    point[0] = r.centerX();
                    point[1] = r.centerY();
                    // The depth image lines up with the camera texture, not the CPU image.
                    frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, point,
                            Coordinates2d.TEXTURE_NORMALIZED, tex);
                    Image.Plane plane = depth.getPlanes()[0];
                    metres[i] = DepthMath.medianDepthMetres(plane.getBuffer(), plane.getRowStride(),
                            depth.getWidth(), depth.getHeight(), tex[0], tex[1], DEPTH_PATCH_RADIUS);
                }
                corners[0] = r.left;
                corners[1] = r.top;
                corners[2] = r.right;
                corners[3] = r.bottom;
                frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW, view);
                viewBoxes.add(new RectF(Math.min(view[0], view[2]), Math.min(view[1], view[3]),
                        Math.max(view[0], view[2]), Math.max(view[1], view[3])));
                labels.add(boxLabel(d.found.get(i), metres[i]));
            }
        } finally {
            if (depth != null) depth.close();
        }

        runOnUiThread(() -> {
            if (!isDestroyed()) overlay.show(viewBoxes, labels);
        });
        if (n > 0) announce(d.found, metres);
    }

    // ---------- Inference thread ----------

    private void runDetection(YuvFrame yuv) {
        try {
            OnDeviceDetector det = detector;
            if (det == null) return;
            int[] argb = new int[yuv.width * yuv.height];
            DepthMath.yuvToArgb(yuv.y, yuv.u, yuv.v, yuv.width, yuv.height,
                    yuv.yRowStride, yuv.yPixelStride, yuv.uvRowStride, yuv.uvPixelStride, argb);
            Bitmap raw = Bitmap.createBitmap(argb, yuv.width, yuv.height, Bitmap.Config.ARGB_8888);
            int rotation = DepthMath.bitmapRotation(sensorOrientation, degrees(displayRotation));
            Bitmap upright = raw;
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            }

            List<OnDeviceDetector.Found> found = det.detect(upright);
            List<RectF> imageBoxes = new ArrayList<>(found.size());
            float bw = upright.getWidth();
            float bh = upright.getHeight();
            for (OnDeviceDetector.Found f : found) {
                float[] a = DepthMath.toCameraNormalized(f.box.left / bw, f.box.top / bh, rotation);
                float[] b = DepthMath.toCameraNormalized(f.box.right / bw, f.box.bottom / bh, rotation);
                imageBoxes.add(new RectF(
                        Math.min(a[0], b[0]) * yuv.width, Math.min(a[1], b[1]) * yuv.height,
                        Math.max(a[0], b[0]) * yuv.width, Math.max(a[1], b[1]) * yuv.height));
            }
            pending = new Detections(found, imageBoxes);
        } catch (Exception e) {
            Log.e(TAG, "Detection failed on a frame", e);
        } finally {
            inferenceBusy = false;
        }
    }

    // ---------- Speech ----------

    private static String boxLabel(OnDeviceDetector.Found f, float metres) {
        if (!(metres > 0f)) return f.spokenName();
        if (metres < DepthMath.VERY_CLOSE_METRES) return f.spokenName() + " <1 m";
        if (metres > DepthMath.MAX_RELIABLE_METRES) return f.spokenName() + " >8 m";
        return f.spokenName() + String.format(Locale.ROOT, " %.1f m", metres);
    }

    /**
     * Speaks the most important object, e.g. "person ahead, about 2 metres". Same pacing as
     * ObjDetection; the distance number alone changing does not count as something new.
     */
    private synchronized void announce(List<OnDeviceDetector.Found> found, float[] metres) {
        boolean[] hazard = new boolean[found.size()];
        for (int i = 0; i < hazard.length; i++) hazard[i] = OnDeviceDetector.isHazard(found.get(i).label);
        int best = DepthMath.pickMostImportant(hazard, metres);
        OnDeviceDetector.Found f = found.get(best);
        float m = metres[best];

        String distance = DepthMath.distancePhrase(m);
        boolean urgent;
        if (distance.isEmpty()) {
            // No depth for this object yet: same rough words and rule as ObjDetection.
            distance = f.closeness();
            urgent = "very close".equals(distance) && hazard[best];
        } else {
            urgent = DepthMath.isUrgent(hazard[best], m);
        }
        String key = f.spokenName() + " " + f.position();
        String phrase = distance.isEmpty() ? key : key + ", " + distance;

        long now = SystemClock.elapsedRealtime();
        boolean isNew = !key.equals(lastKey) || (urgent && !lastUrgent);
        boolean speaking = ttsReady && tts.isSpeaking();
        if (!DepthMath.shouldAnnounce(isNew, urgent, now - lastSpokenAt, REPEAT_AFTER_MS, speaking)) return;
        lastKey = key;
        lastUrgent = urgent;
        lastSpokenAt = now;
        speak(phrase, urgent);
    }

    private void speak(String text, boolean urgent) {
        if (!ttsReady || fallingBack) return;
        // Urgent warnings interrupt; everything else waits its turn.
        tts.speak(text, urgent ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "detection");
    }

    // ---------- Falling back ----------

    /** Says why distances aren't available, then opens the plain detection screen. */
    private void fallBack(String reason) {
        leave(reason, true);
    }

    /** Speaks the reason, then opens ObjDetection (if openPlainScreen) and closes this screen. */
    private void leave(String reason, boolean openPlainScreen) {
        runOnUiThread(() -> {
            if (fallingBack) return;
            fallingBack = true;
            fallbackReason = reason;
            fallbackOpensPlainScreen = openPlainScreen;
            sessionRunning = false;
            Log.w(TAG, "Falling back to ObjDetection: " + reason);
            if (ttsReady) speakFallbackReason();
            // In case speech never finishes (or never starts).
            mainHandler.postDelayed(this::launchFallback, FALLBACK_SPEECH_TIMEOUT_MS);
        });
    }

    private void speakFallbackReason() {
        tts.speak(fallbackReason, TextToSpeech.QUEUE_FLUSH, null, "fallback");
    }

    private void launchFallback() {
        if (fallbackLaunched || isFinishing() || isDestroyed()) return;
        fallbackLaunched = true;
        if (fallbackOpensPlainScreen) startActivity(new Intent(this, ObjDetection.class));
        finish();
    }

    // ---------- Helpers ----------

    private int readSensorOrientation(String cameraId) {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Integer o = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION);
            return o == null ? 90 : o;
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "Could not read sensor orientation", e);
            return 90;
        }
    }

    private static int degrees(int surfaceRotation) {
        switch (surfaceRotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // Plain detection needs the camera too, so there is nothing to fall back to.
            // Don't re-request: that loops forever once the user picks "Don't ask again".
            Toast.makeText(this, "Object detection needs camera permission", Toast.LENGTH_LONG).show();
            leave("Object detection needs camera permission.", false);
        }
        // When granted, onResume runs next and starts the session.
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        // Close the detector on its own thread, after any frame still in progress.
        inferenceHandler.post(() -> {
            if (detector != null) detector.close();
            detector = null;
        });
        inferenceThread.quitSafely();
        // The GL thread is paused (onPause ran first), so the session can be released now.
        if (session != null) {
            session.close();
            session = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
