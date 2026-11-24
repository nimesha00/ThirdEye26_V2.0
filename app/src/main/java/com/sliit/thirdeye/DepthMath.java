package com.sliit.thirdeye;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Plain-Java helpers for "Around me" with ARCore depth: reading distance from a depth image,
 * turning it into words, deciding what is urgent, and converting camera frames. No Android
 * types, so it is covered by JVM unit tests.
 */
final class DepthMath {

    /** Hazards nearer than this interrupt whatever is being said. */
    static final float URGENT_METRES = 1.5f;
    /** Below this we say "very close, less than 1 metre". */
    static final float VERY_CLOSE_METRES = 1.0f;
    /** ARCore depth is only dependable up to about 8 m; beyond that we don't give a number. */
    static final float MAX_RELIABLE_METRES = 8.0f;
    /** A patch needs at least this many valid (non-zero) depth pixels to be trusted. */
    static final int MIN_VALID_SAMPLES = 3;

    private DepthMath() {
    }

    /**
     * Median distance in metres of a (2 * radius + 1) square patch of an ARCore 16-bit depth
     * image (unsigned millimetres per pixel, 0 = no estimate) centred on normalised (u, v).
     * Zeros are ignored. Returns -1 when the point is outside the image or too few pixels
     * have a depth estimate.
     */
    static float medianDepthMetres(ByteBuffer buffer, int rowStrideBytes, int width, int height,
                                   float u, float v, int radius) {
        if (width <= 0 || height <= 0 || u < 0f || u > 1f || v < 0f || v > 1f) return -1f;
        ByteBuffer buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int cx = Math.min(width - 1, (int) (u * width));
        int cy = Math.min(height - 1, (int) (v * height));
        int side = 2 * radius + 1;
        int[] samples = new int[side * side];
        int count = 0;
        for (int y = Math.max(0, cy - radius); y <= Math.min(height - 1, cy + radius); y++) {
            for (int x = Math.max(0, cx - radius); x <= Math.min(width - 1, cx + radius); x++) {
                int index = y * rowStrideBytes + x * 2;
                if (index + 1 >= buf.limit()) continue;
                int mm = buf.getShort(index) & 0xFFFF;
                if (mm > 0) samples[count++] = mm;
            }
        }
        if (count < MIN_VALID_SAMPLES) return -1f;
        return median(samples, count) / 1000f;
    }

    /** Median of the first {@code count} values (sorts them in place). */
    static float median(int[] values, int count) {
        Arrays.sort(values, 0, count);
        int mid = count / 2;
        return count % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2f;
    }

    /**
     * Words for a distance: "very close, less than 1 metre", "about 1 and a half metres"
     * (half metres under 3 m), "about 4 metres" (whole metres beyond), "more than 8 metres".
     * Empty when the distance is unknown (metres <= 0 or NaN).
     */
    static String distancePhrase(float metres) {
        if (!(metres > 0f)) return "";
        if (metres < VERY_CLOSE_METRES) return "very close, less than 1 metre";
        if (metres > MAX_RELIABLE_METRES) return "more than 8 metres";
        float rounded = metres < 3f ? Math.round(metres * 2f) / 2f : Math.round(metres);
        int whole = (int) rounded;
        boolean half = rounded - whole >= 0.25f;
        if (half) return "about " + whole + " and a half metres";
        return "about " + whole + (whole == 1 ? " metre" : " metres");
    }

    /** A hazard (person, car, chair...) measured nearer than 1.5 m interrupts speech. */
    static boolean isUrgent(boolean hazard, float metres) {
        return hazard && metres > 0f && metres < URGENT_METRES;
    }

    /**
     * Which detection to speak: the nearest urgent hazard if there is one, otherwise the
     * detector's own first choice (index 0).
     */
    static int pickMostImportant(boolean[] hazard, float[] metres) {
        int best = 0;
        float bestMetres = Float.MAX_VALUE;
        for (int i = 0; i < metres.length; i++) {
            if (isUrgent(hazard[i], metres[i]) && metres[i] < bestMetres) {
                best = i;
                bestMetres = metres[i];
            }
        }
        return best;
    }

    /**
     * Same pacing as ObjDetection: something new (or newly urgent) is spoken straight away,
     * the same thing at most every {@code repeatAfterMs}; non-urgent speech never interrupts.
     */
    static boolean shouldAnnounce(boolean isNew, boolean urgent, long msSinceLast,
                                  long repeatAfterMs, boolean speaking) {
        if (!isNew && msSinceLast < repeatAfterMs) return false;
        return urgent || !speaking;
    }

    /** Clockwise rotation that turns the back camera's image upright for the display. */
    static int bitmapRotation(int sensorOrientation, int displayRotationDegrees) {
        return ((sensorOrientation - displayRotationDegrees) % 360 + 360) % 360;
    }

    /**
     * Maps a normalised point in the upright (rotated) bitmap back to the camera image it was
     * rotated from. {@code rotation} is the clockwise rotation that was applied.
     */
    static float[] toCameraNormalized(float u, float v, int rotation) {
        switch (rotation) {
            case 90:
                return new float[]{v, 1f - u};
            case 180:
                return new float[]{1f - u, 1f - v};
            case 270:
                return new float[]{1f - v, u};
            default:
                return new float[]{u, v};
        }
    }

    /**
     * YUV_420_888 planes (copied out of an android.media.Image) to ARGB pixels.
     * Uses the BT.601 full-range conversion that camera images use.
     */
    static void yuvToArgb(byte[] y, byte[] u, byte[] v, int width, int height,
                          int yRowStride, int yPixelStride, int uvRowStride, int uvPixelStride,
                          int[] out) {
        for (int row = 0; row < height; row++) {
            int yRow = row * yRowStride;
            int uvRow = (row >> 1) * uvRowStride;
            for (int col = 0; col < width; col++) {
                int yy = y[yRow + col * yPixelStride] & 0xFF;
                int uvIndex = uvRow + (col >> 1) * uvPixelStride;
                int uu = (u[uvIndex] & 0xFF) - 128;
                int vv = (v[uvIndex] & 0xFF) - 128;
                int r = clamp(yy + (int) (1.402f * vv));
                int g = clamp(yy - (int) (0.344136f * uu + 0.714136f * vv));
                int b = clamp(yy + (int) (1.772f * uu));
                out[row * width + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static int clamp(int c) {
        return c < 0 ? 0 : (c > 255 ? 255 : c);
    }
}
