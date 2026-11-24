package com.sliit.thirdeye;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * On-phone object detection with MediaPipe and EfficientDet-Lite2 (80 everyday object types,
 * Apache 2.0). Works offline. Also turns a detection into words a blind user can act on:
 * where it is (left / ahead / right) and how close it looks.
 *
 * Not thread-safe: create, use and close it on one background thread.
 */
final class OnDeviceDetector implements AutoCloseable {

    static final String MODEL_ASSET = "efficientdet_lite2.tflite";
    private static final String TAG = "OnDeviceDetector";

    /** One detected object, with its box in the pixel coordinates of the image passed in. */
    static final class Found {
        final String label;
        final float score;
        final RectF box;
        final int imageWidth;
        final int imageHeight;

        Found(String label, float score, RectF box, int imageWidth, int imageHeight) {
            this.label = label;
            this.score = score;
            this.box = box;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }

        /** Share of the picture's height the object covers: a rough "how close" signal. */
        float heightShare() {
            return imageHeight == 0 ? 0f : box.height() / imageHeight;
        }

        /** "on your left", "ahead" or "on your right", from where the box's centre sits. */
        String position() {
            float centre = box.centerX() / Math.max(1, imageWidth);
            if (centre < 0.35f) return "on your left";
            if (centre > 0.65f) return "on your right";
            return "ahead";
        }

        /**
         * "very close", "close" or "" from how much of the picture it fills. This is not a
         * distance in metres; real distances come from a depth sensor, not a single photo.
         */
        String closeness() {
            float share = heightShare();
            if (share > 0.6f) return "very close";
            if (share > 0.35f) return "close";
            return "";
        }

        /** Natural name, e.g. "dining table" -> "table". */
        String spokenName() {
            return OnDeviceDetector.spokenName(label);
        }
    }

    private final ObjectDetector detector;

    private OnDeviceDetector(ObjectDetector detector) {
        this.detector = detector;
    }

    /**
     * Returns null when the model can't be loaded, most often because
     * assets/efficientdet_lite2.tflite is missing from the build.
     */
    @Nullable
    static OnDeviceDetector create(Context context, float minScore, int maxResults) {
        try {
            ObjectDetector.ObjectDetectorOptions options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(minScore)
                    .setMaxResults(maxResults)
                    .build();
            return new OnDeviceDetector(ObjectDetector.createFromOptions(context.getApplicationContext(), options));
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not load " + MODEL_ASSET + "; is it in app/src/main/assets?", e);
            return null;
        }
    }

    /** Detections sorted with the most urgent first: hazards, then the biggest (closest) objects. */
    List<Found> detect(Bitmap bitmap) {
        List<Found> found = new ArrayList<>();
        Bitmap argb = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        MPImage image = new BitmapImageBuilder(argb).build();
        ObjectDetectorResult result = detector.detect(image);
        for (Detection d : result.detections()) {
            if (d.categories().isEmpty()) continue;
            Category top = d.categories().get(0);
            found.add(new Found(top.categoryName(), top.score(), d.boundingBox(),
                    argb.getWidth(), argb.getHeight()));
        }
        Collections.sort(found, (a, b) -> {
            int byHazard = Boolean.compare(isHazard(b.label), isHazard(a.label));
            if (byHazard != 0) return byHazard;
            return Float.compare(b.heightShare(), a.heightShare());
        });
        return found;
    }

    @Override
    public void close() {
        detector.close();
    }

    /** Things a person walking should hear about first. */
    static boolean isHazard(String label) {
        switch (label) {
            case "person":
            case "bicycle":
            case "car":
            case "motorcycle":
            case "bus":
            case "truck":
            case "train":
            case "dog":
            case "cow":
            case "horse":
            case "traffic light":
            case "stop sign":
            case "fire hydrant":
            case "bench":
            case "chair":
            case "potted plant":
                return true;
            default:
                return false;
        }
    }

    static String spokenName(String label) {
        switch (label) {
            case "dining table":
                return "table";
            case "tv":
                return "TV";
            case "cell phone":
                return "mobile phone";
            case "couch":
                return "sofa";
            default:
                return label.toLowerCase(Locale.ROOT);
        }
    }

    /** "a" or "an" for a spoken noun. */
    static String article(String noun) {
        return noun.isEmpty() ? "a" : ("aeiou".indexOf(Character.toLowerCase(noun.charAt(0))) >= 0 ? "an" : "a");
    }
}
