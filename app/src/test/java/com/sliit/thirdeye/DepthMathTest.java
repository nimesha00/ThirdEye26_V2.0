package com.sliit.thirdeye;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DepthMathTest {

    // ---------- distance phrasing ----------

    @Test
    public void unknownDistanceSaysNothing() {
        assertEquals("", DepthMath.distancePhrase(-1f));
        assertEquals("", DepthMath.distancePhrase(0f));
        assertEquals("", DepthMath.distancePhrase(Float.NaN));
    }

    @Test
    public void underOneMetreIsVeryClose() {
        assertEquals("very close, less than 1 metre", DepthMath.distancePhrase(0.3f));
        assertEquals("very close, less than 1 metre", DepthMath.distancePhrase(0.99f));
    }

    @Test
    public void halfMetresUnderThree() {
        assertEquals("about 1 metre", DepthMath.distancePhrase(1.0f));
        assertEquals("about 1 metre", DepthMath.distancePhrase(1.2f));
        assertEquals("about 1 and a half metres", DepthMath.distancePhrase(1.3f));
        assertEquals("about 1 and a half metres", DepthMath.distancePhrase(1.6f));
        assertEquals("about 2 metres", DepthMath.distancePhrase(2.1f));
        assertEquals("about 2 and a half metres", DepthMath.distancePhrase(2.6f));
        assertEquals("about 3 metres", DepthMath.distancePhrase(2.9f));
    }

    @Test
    public void wholeMetresFromThree() {
        assertEquals("about 3 metres", DepthMath.distancePhrase(3.4f));
        assertEquals("about 4 metres", DepthMath.distancePhrase(3.6f));
        assertEquals("about 8 metres", DepthMath.distancePhrase(8.0f));
    }

    @Test
    public void beyondEightMetresGivesNoNumber() {
        assertEquals("more than 8 metres", DepthMath.distancePhrase(8.1f));
        assertEquals("more than 8 metres", DepthMath.distancePhrase(40f));
    }

    // ---------- depth patch sampling ----------

    /** Depth image of 16-bit little-endian millimetres with optional row padding. */
    private static ByteBuffer depthImage(int width, int height, int rowStrideBytes, int fillMm) {
        ByteBuffer b = ByteBuffer.allocate(rowStrideBytes * height).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) b.putShort(y * rowStrideBytes + x * 2, (short) fillMm);
        }
        return b;
    }

    private static void set(ByteBuffer b, int rowStride, int x, int y, int mm) {
        b.putShort(y * rowStride + x * 2, (short) mm);
    }

    @Test
    public void uniformPatchGivesThatDistance() {
        ByteBuffer img = depthImage(160, 120, 320, 2000);
        assertEquals(2.0f, DepthMath.medianDepthMetres(img, 320, 160, 120, 0.5f, 0.5f, 2), 1e-4f);
    }

    @Test
    public void zerosAreIgnoredAndOutliersDoNotMoveTheMedian() {
        int stride = 340; // padded rows
        ByteBuffer img = depthImage(160, 120, stride, 0);
        // 3 x 3 patch at (80, 60): five readings of 1.5 m, one far outlier, three missing.
        set(img, stride, 79, 59, 1500);
        set(img, stride, 80, 59, 1500);
        set(img, stride, 81, 59, 1500);
        set(img, stride, 79, 60, 1500);
        set(img, stride, 80, 60, 1500);
        set(img, stride, 81, 60, 30000);
        float m = DepthMath.medianDepthMetres(img, stride, 160, 120, 80.5f / 160, 60.5f / 120, 1);
        assertEquals(1.5f, m, 1e-4f);
    }

    @Test
    public void readsFullUnsigned16BitRange() {
        ByteBuffer img = depthImage(10, 10, 20, 40000); // above Short.MAX_VALUE
        assertEquals(40f, DepthMath.medianDepthMetres(img, 20, 10, 10, 0.5f, 0.5f, 1), 1e-3f);
    }

    @Test
    public void tooFewValidPixelsIsUnknown() {
        ByteBuffer img = depthImage(160, 120, 320, 0);
        set(img, 320, 80, 60, 1200);
        set(img, 320, 81, 60, 1200);
        assertEquals(-1f, DepthMath.medianDepthMetres(img, 320, 160, 120, 0.5f, 0.5f, 2), 0f);
    }

    @Test
    public void pointOutsideImageIsUnknown() {
        ByteBuffer img = depthImage(160, 120, 320, 2000);
        assertEquals(-1f, DepthMath.medianDepthMetres(img, 320, 160, 120, -0.1f, 0.5f, 2), 0f);
        assertEquals(-1f, DepthMath.medianDepthMetres(img, 320, 160, 120, 0.5f, 1.2f, 2), 0f);
    }

    @Test
    public void patchAtTheCornerIsClippedNotCrashing() {
        ByteBuffer img = depthImage(160, 120, 320, 900);
        assertEquals(0.9f, DepthMath.medianDepthMetres(img, 320, 160, 120, 1f, 1f, 2), 1e-4f);
        assertEquals(0.9f, DepthMath.medianDepthMetres(img, 320, 160, 120, 0f, 0f, 2), 1e-4f);
    }

    @Test
    public void medianOfEvenCountAveragesTheMiddle() {
        assertEquals(2.5f, DepthMath.median(new int[]{4, 1, 3, 2}, 4), 0f);
    }

    // ---------- urgency, choice and pacing ----------

    @Test
    public void urgentOnlyForHazardsNearerThanOneAndAHalfMetres() {
        assertTrue(DepthMath.isUrgent(true, 0.5f));
        assertTrue(DepthMath.isUrgent(true, 1.49f));
        assertFalse(DepthMath.isUrgent(true, 1.5f));
        assertFalse(DepthMath.isUrgent(true, 3f));
        assertFalse(DepthMath.isUrgent(false, 0.5f));
        assertFalse(DepthMath.isUrgent(true, -1f)); // unknown depth is never urgent by number
    }

    @Test
    public void hazardListFromDetectorFeedsUrgency() {
        assertTrue(DepthMath.isUrgent(OnDeviceDetector.isHazard("person"), 1.0f));
        assertFalse(DepthMath.isUrgent(OnDeviceDetector.isHazard("cup"), 1.0f));
    }

    @Test
    public void nearestUrgentHazardIsSpokenFirst() {
        boolean[] hazard = {false, true, true};
        float[] metres = {0.8f, 1.2f, 0.9f};
        assertEquals(2, DepthMath.pickMostImportant(hazard, metres));
    }

    @Test
    public void withoutUrgentHazardDetectorOrderWins() {
        boolean[] hazard = {true, false};
        float[] metres = {4f, 0.5f};
        assertEquals(0, DepthMath.pickMostImportant(hazard, metres));
    }

    @Test
    public void pacingMatchesObjDetection() {
        // Same thing within 5 s: silent, even if urgent.
        assertFalse(DepthMath.shouldAnnounce(false, true, 2000, 5000, false));
        // Same thing after 5 s: repeated when quiet.
        assertTrue(DepthMath.shouldAnnounce(false, false, 6000, 5000, false));
        // Something new waits while speech is playing, unless urgent.
        assertFalse(DepthMath.shouldAnnounce(true, false, 100, 5000, true));
        assertTrue(DepthMath.shouldAnnounce(true, true, 100, 5000, true));
    }

    // ---------- geometry and colour ----------

    @Test
    public void rotationForPortraitBackCamera() {
        assertEquals(90, DepthMath.bitmapRotation(90, 0));
        assertEquals(0, DepthMath.bitmapRotation(90, 90));
        assertEquals(180, DepthMath.bitmapRotation(90, 270));
        assertEquals(270, DepthMath.bitmapRotation(270, 0));
    }

    @Test
    public void uprightPointMapsBackToCameraImage() {
        // Camera image rotated 90 degrees clockwise: its top-left corner ends up top-right.
        assertArrayEquals(new float[]{0f, 0f}, DepthMath.toCameraNormalized(1f, 0f, 90), 1e-6f);
        assertArrayEquals(new float[]{0.25f, 0.9f}, DepthMath.toCameraNormalized(0.1f, 0.25f, 90), 1e-6f);
        assertArrayEquals(new float[]{0.9f, 0.75f}, DepthMath.toCameraNormalized(0.1f, 0.25f, 180), 1e-6f);
        assertArrayEquals(new float[]{0.75f, 0.1f}, DepthMath.toCameraNormalized(0.1f, 0.25f, 270), 1e-6f);
        assertArrayEquals(new float[]{0.1f, 0.25f}, DepthMath.toCameraNormalized(0.1f, 0.25f, 0), 1e-6f);
    }

    @Test
    public void yuvConversionGivesGreyAndColour() {
        int w = 2, h = 2;
        byte[] y = {(byte) 128, (byte) 128, (byte) 128, (byte) 128};
        byte[] neutral = {(byte) 128, (byte) 128};
        int[] out = new int[4];
        DepthMath.yuvToArgb(y, neutral, neutral, w, h, 2, 1, 2, 2, out);
        assertEquals(0xFF808080, out[0]);

        byte[] v = {(byte) 255, (byte) 255}; // strong red chroma
        DepthMath.yuvToArgb(y, neutral, v, w, h, 2, 1, 2, 2, out);
        int r = (out[3] >> 16) & 0xFF, b = out[3] & 0xFF;
        assertTrue(r > 200 && b < 140);
    }
}
