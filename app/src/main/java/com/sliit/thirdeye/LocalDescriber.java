package com.sliit.thirdeye;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeler;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Offline "What is this?". When the optional SmolVLM2 model is downloaded and the phone has
 * the memory for it, a vision-language model describes the photo in a sentence or two
 * (see {@link SmolVlmDescriber}); on timeout or error, or without it, two on-phone models:
 *   ML Kit Image Labeling (400+ labels) says what the photo shows.
 *   MediaPipe object detection says which everyday objects are there, and where.
 * The answer says how sure it is, because a confident wrong answer is worse than "I'm not sure"
 * for someone who can't check.
 *
 * Use from one background thread; call {@link #close()} when done.
 */
final class LocalDescriber implements AutoCloseable {

    private static final String TAG = "LocalDescriber";

    // Below this, ML Kit labels are too often wrong to mention at all.
    private static final float MIN_LABEL_CONFIDENCE = 0.6f;
    // At or above this we say "this looks like"; below it, "this might be".
    private static final float SURE_LABEL_CONFIDENCE = 0.8f;

    // Labels that describe a mood or style rather than a thing; not useful to a blind user.
    private static final Set<String> VAGUE_LABELS = new HashSet<>(Arrays.asList(
            "fun", "event", "pattern", "font", "smile", "cool", "leisure", "competition",
            "team", "sports", "musical", "flesh"));

    private final ImageLabeler labeler;
    @Nullable private final OnDeviceDetector detector;

    // SmolVLM runs on its own thread so a slow answer can be abandoned after the timeout.
    @Nullable private final ExecutorService vlmExecutor;
    @Nullable private volatile SmolVlmDescriber vlm;     // set and used on vlmExecutor only
    private volatile boolean vlmBusy;

    LocalDescriber(Context context) {
        labeler = ImageLabeling.getClient(new ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_LABEL_CONFIDENCE)
                .build());
        // Null if the detector model is missing; labels alone still give a useful answer.
        detector = OnDeviceDetector.create(context, 0.5f, 5);

        DescriberChoice.Engine engine = SmolVlmDescriber.pick(context);
        Log.i(TAG, "Offline describer: " + engine);
        if (engine == DescriberChoice.Engine.LABELS) {
            vlmExecutor = null;
        } else {
            // Start loading now (a few seconds) so it's ready by the time the photo is taken.
            Context app = context.getApplicationContext();
            vlmExecutor = Executors.newSingleThreadExecutor();
            vlmExecutor.execute(() -> vlm = SmolVlmDescriber.load(app, engine));
        }
    }

    /** Builds the spoken description. Blocks while the models run, so never call it on the UI thread. */
    @WorkerThread
    String describe(Bitmap photo) {
        String sentence = describeWithVlm(photo);
        if (sentence != null) return sentence;

        List<ImageLabel> labels = new ArrayList<>();
        try {
            labels = Tasks.await(labeler.process(InputImage.fromBitmap(photo, 0)));
        } catch (Exception e) {
            Log.e(TAG, "Image labeling failed", e);
        }
        List<OnDeviceDetector.Found> objects = new ArrayList<>();
        if (detector != null) {
            try {
                objects = detector.detect(photo);
            } catch (RuntimeException e) {
                Log.e(TAG, "Object detection failed", e);
            }
        }
        return compose(labels, objects);
    }

    /** SmolVLM's answer, or null to fall back to the labels (none, busy, error, timeout). */
    @Nullable
    @WorkerThread
    private String describeWithVlm(Bitmap photo) {
        if (vlmExecutor == null || vlmBusy) return null;
        vlmBusy = true;
        Future<String> answer;
        try {
            answer = vlmExecutor.submit(() -> {
                try {
                    SmolVlmDescriber model = vlm;
                    return model == null ? null
                            : DescriberChoice.tidy(model.describe(photo, DescriberChoice.PROMPT));
                } finally {
                    vlmBusy = false;
                }
            });
        } catch (RejectedExecutionException e) {
            vlmBusy = false;
            return null;
        }
        try {
            // Includes loading time on the first photo; a late model is used next time.
            return answer.get(DescriberChoice.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.w(TAG, "SmolVLM took too long; using labels");
            if (answer.cancel(false)) {
                vlmBusy = false;            // still queued behind loading; never ran
            } else {
                SmolVlmDescriber model = vlm;
                if (model != null) model.abort();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            Log.e(TAG, "SmolVLM failed; using labels", e);
            return null;
        }
    }

    static String compose(List<ImageLabel> labels, List<OnDeviceDetector.Found> objects) {
        // Keep useful labels, highest confidence first (ML Kit already sorts them).
        List<ImageLabel> useful = new ArrayList<>();
        for (ImageLabel l : labels) {
            if (!VAGUE_LABELS.contains(l.getText().toLowerCase(Locale.ROOT))) useful.add(l);
        }

        StringBuilder out = new StringBuilder();
        Set<String> mentioned = new HashSet<>();

        if (!useful.isEmpty()) {
            ImageLabel top = useful.get(0);
            String name = top.getText().toLowerCase(Locale.ROOT);
            mentioned.add(name);
            out.append(top.getConfidence() >= SURE_LABEL_CONFIDENCE ? "This looks like " : "This might be ")
                    .append(OnDeviceDetector.article(name)).append(' ').append(name).append('.');

            List<String> others = new ArrayList<>();
            for (int i = 1; i < useful.size() && others.size() < 2; i++) {
                String other = useful.get(i).getText().toLowerCase(Locale.ROOT);
                if (mentioned.add(other)) others.add(other);
            }
            if (!others.isEmpty()) {
                out.append(" It could also be ").append(joinWithOr(others)).append('.');
            }
        }

        // Objects with positions, at most three, skipping ones already named.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> where = new ArrayList<>();
        for (OnDeviceDetector.Found f : objects) {
            String name = f.spokenName();
            if (mentioned.contains(name) || !seen.add(name)) continue;
            String closeness = f.closeness();
            where.add(OnDeviceDetector.article(name) + " " + name + " " + f.position()
                    + (closeness.isEmpty() ? "" : ", " + closeness));
            if (where.size() == 3) break;
        }
        if (!where.isEmpty()) {
            if (out.length() > 0) out.append(' ');
            out.append("I can see ").append(joinWithAnd(where)).append('.');
        }

        if (out.length() == 0) {
            return "I'm not sure what this is. Try moving the phone a little further away, "
                    + "or turning on a light, then tap the screen to try again.";
        }
        return out.toString();
    }

    private static String joinWithOr(List<String> items) {
        return join(items, " or ");
    }

    private static String joinWithAnd(List<String> items) {
        return join(items, " and ");
    }

    private static String join(List<String> items, String lastSeparator) {
        if (items.size() == 1) return items.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(i == items.size() - 1 ? lastSeparator : ", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    @Override
    public void close() {
        labeler.close();
        if (detector != null) detector.close();
        if (vlmExecutor != null) {
            SmolVlmDescriber model = vlm;
            if (model != null) model.abort();
            // Free it on its own thread, after any description still running there.
            vlmExecutor.execute(() -> {
                SmolVlmDescriber m = vlm;
                vlm = null;
                if (m != null) m.close();
            });
            vlmExecutor.shutdown();
        }
    }
}
