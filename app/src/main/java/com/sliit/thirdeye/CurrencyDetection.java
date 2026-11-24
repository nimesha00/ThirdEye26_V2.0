package com.sliit.thirdeye;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.ViewStub;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sri Lankan rupee note reader. Camera frames go to an on-phone MediaPipe object detector
 * trained on LKR notes (assets/lkr_notes.tflite, see ml-training/lkr_notes); NoteAnnouncer
 * decides what to say, e.g. "This looks like a 1000 rupee note" or each value and the total
 * when several notes are visible. Works offline.
 */
public class CurrencyDetection extends AbstractCameraXActivity<CurrencyDetection.AnalysisResult> {

    static final String MODEL_ASSET = "lkr_notes.tflite";
    private static final String TAG = "CurrencyDetection";
    // Lower than NoteAnnouncer.MIN_SCORE so its own rules decide; at most a handful of notes.
    private static final float DETECTOR_MIN_SCORE = 0.3f;
    private static final int MAX_NOTES = 6;

    private NoteBoxesView boxesView;
    private TextView resultView;

    private TextToSpeech tts;
    private volatile boolean ttsReady;
    // Said once the speech engine is ready, if something had to be said before that.
    private String pendingSpeech;

    // Used only on the analysis thread.
    private OnDeviceDetector detector;
    private boolean detectorTried;
    private final NoteAnnouncer announcer = new NoteAnnouncer();
    private Bitmap frameBuffer;
    private final Matrix rotation = new Matrix();

    static final class AnalysisResult {
        final List<RectF> boxes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        int imageWidth;
        int imageHeight;
        String screenText;
        @Nullable String speech;
        boolean modelMissing;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(new Locale("en", "LK"));
            tts.setSpeechRate(1f);
            runOnUiThread(() -> {
                ttsReady = true;
                speak(pendingSpeech != null ? pendingSpeech : getString(R.string.note_reader_intro));
                pendingSpeech = null;
            });
        });
    }

    @Override
    protected int getContentViewLayoutId() {
        return R.layout.activity_currency_detection;
    }

    @Override
    protected PreviewView getCameraPreviewView() {
        boxesView = findViewById(R.id.noteBoxes);
        resultView = findViewById(R.id.noteResult);
        return ((ViewStub) findViewById(R.id.object_detection_texture_view_stub)).inflate()
                .findViewById(R.id.object_detection_texture_view);
    }

    @Override
    @WorkerThread
    @Nullable
    protected AnalysisResult analyzeImage(ImageProxy image, int rotationDegrees) {
        if (!detectorTried) {
            detectorTried = true;
            detector = OnDeviceDetector.create(this, MODEL_ASSET, DETECTOR_MIN_SCORE, MAX_NOTES);
            if (detector == null) {
                AnalysisResult missing = new AnalysisResult();
                missing.modelMissing = true;
                return missing;
            }
        }
        if (detector == null) return null;

        try {
            Bitmap upright = toUprightBitmap(image, rotationDegrees);
            List<OnDeviceDetector.Found> found = detector.detect(upright);

            List<NoteAnnouncer.Seen> seen = new ArrayList<>(found.size());
            for (OnDeviceDetector.Found f : found) {
                seen.add(new NoteAnnouncer.Seen(f.label, f.score,
                        f.box.left, f.box.top, f.box.right, f.box.bottom));
            }
            AnalysisResult result = new AnalysisResult();
            result.speech = announcer.onFrame(seen, SystemClock.elapsedRealtime());
            List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(seen);
            for (NoteAnnouncer.Note n : notes) {
                result.boxes.add(new RectF(n.seen.left, n.seen.top, n.seen.right, n.seen.bottom));
                result.labels.add(n.value + (n.sure() ? "" : "?"));
            }
            result.screenText = NoteAnnouncer.screenText(notes);
            result.imageWidth = upright.getWidth();
            result.imageHeight = upright.getHeight();
            return result;
        } catch (RuntimeException e) {
            Log.e(TAG, "Note detection failed on a frame", e);
            return null;
        }
    }

    /** Copies an RGBA_8888 camera frame into a Bitmap and turns it the right way up. */
    private Bitmap toUprightBitmap(ImageProxy image, int rotationDegrees) {
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        pixels.rewind();
        // Rows may be padded; make the buffer bitmap as wide as a padded row, then crop.
        int rowWidth = plane.getRowStride() / plane.getPixelStride();
        if (frameBuffer == null || frameBuffer.getWidth() != rowWidth
                || frameBuffer.getHeight() != image.getHeight()) {
            frameBuffer = Bitmap.createBitmap(rowWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        }
        frameBuffer.copyPixelsFromBuffer(pixels);
        rotation.reset();
        rotation.postRotate(rotationDegrees);
        return Bitmap.createBitmap(frameBuffer, 0, 0, image.getWidth(), image.getHeight(), rotation, false);
    }

    @Override
    protected void applyToUiAnalyzeImageResult(AnalysisResult result) {
        if (isFinishing() || isDestroyed()) return;
        if (result.modelMissing) {
            resultView.setText(R.string.note_reader_model_missing_short);
            String message = getString(R.string.note_reader_model_missing);
            if (ttsReady) {
                speak(message);
            } else {
                pendingSpeech = message;
            }
            return;
        }
        boxesView.setNotes(result.boxes, result.labels, result.imageWidth, result.imageHeight);
        CharSequence text = result.screenText.isEmpty()
                ? getString(R.string.note_reader_looking) : result.screenText;
        if (!text.toString().contentEquals(resultView.getText())) resultView.setText(text);
        if (result.speech != null) speak(result.speech);
    }

    private void speak(String text) {
        if (!ttsReady) return;
        // A new result replaces whatever is still being said about the previous one.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "note");
    }

    @Override
    @WorkerThread
    protected void onAnalysisStopped() {
        if (detector != null) {
            detector.close();
            detector = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
