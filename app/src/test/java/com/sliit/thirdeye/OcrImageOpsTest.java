package com.sliit.thirdeye;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OcrImageOpsTest {

    /** White page with dark "lines of text" (solid bars) sloping at {@code degrees}. */
    private static int[] page(int w, int h, double degrees, boolean whiteOnBlack) {
        int paper = whiteOnBlack ? 20 : 235, ink = whiteOnBlack ? 235 : 20;
        int[] g = new int[w * h];
        java.util.Arrays.fill(g, paper);
        double tan = Math.tan(Math.toRadians(degrees));
        for (int line = 0; line < 8; line++) {
            int base = 40 + line * 40;
            for (int x = 20; x < w - 20; x++) {
                if ((x / 12) % 4 == 3) continue; // gaps between "words"
                for (int t = 0; t < 8; t++) {
                    int y = (int) Math.round(base + t + x * tan);
                    if (y >= 0 && y < h) g[y * w + x] = ink;
                }
            }
        }
        return g;
    }

    @Test
    public void grayRoundTrip() {
        int[] gray = OcrImageOps.toGray(new int[] {0xFFFFFFFF, 0xFF000000, 0xFFFF0000});
        assertEquals(255, gray[0]);
        assertEquals(0, gray[1]);
        assertEquals(76, gray[2]);
        assertEquals(0xFF4C4C4C, OcrImageOps.toArgb(new int[] {76})[0]);
    }

    @Test
    public void contrastStretchUsesTheFullRange() {
        int[] g = new int[1000];
        for (int i = 0; i < g.length; i++) g[i] = 100 + (i % 50); // dull 100..149
        OcrImageOps.stretchContrast(g);
        int min = 255, max = 0;
        for (int v : g) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue("min " + min, min <= 5);
        assertTrue("max " + max, max >= 250);
    }

    @Test
    public void contrastStretchLeavesFlatImagesAlone() {
        int[] g = new int[100];
        java.util.Arrays.fill(g, 128);
        g[0] = 130;
        OcrImageOps.stretchContrast(g);
        assertEquals(128, g[5]);
    }

    @Test
    public void otsuSplitsInkFromPaper() {
        int[] g = new int[200];
        for (int i = 0; i < g.length; i++) g[i] = i < 40 ? 30 : 220;
        int t = OcrImageOps.otsuThreshold(g);
        assertTrue("threshold " + t, t >= 30 && t < 220);
    }

    @Test
    public void scaleUpSmallAndDownHugePhotos() {
        assertEquals(2f, OcrImageOps.scaleFor(1000, 750), 1e-4);
        assertEquals(OcrImageOps.MAX_UPSCALE, OcrImageOps.scaleFor(400, 300), 1e-4);
        assertEquals(1f, OcrImageOps.scaleFor(2400, 1800), 1e-4);
        assertEquals(0.6f, OcrImageOps.scaleFor(3000, 4000), 1e-4);
        assertEquals(1f, OcrImageOps.scaleFor(0, 0), 1e-4);
    }

    @Test
    public void levelTextNeedsNoRotation() {
        assertEquals(0f, OcrImageOps.estimateSkewDegrees(page(400, 400, 0, false), 400, 400), 1e-4);
    }

    @Test
    public void findsClockwiseAndAnticlockwiseTilt() {
        assertEquals(4f, OcrImageOps.estimateSkewDegrees(page(400, 420, 4, false), 400, 420), 0.51);
        assertEquals(-6f, OcrImageOps.estimateSkewDegrees(page(400, 420, -6, false), 400, 420), 0.51);
    }

    @Test
    public void findsTiltOnWhiteOnDarkSigns() {
        assertEquals(3f, OcrImageOps.estimateSkewDegrees(page(400, 420, 3, true), 400, 420), 0.51);
    }

    @Test
    public void blankOrTinyImagesAreLeftAlone() {
        int[] blank = new int[100 * 100];
        java.util.Arrays.fill(blank, 200);
        assertEquals(0f, OcrImageOps.estimateSkewDegrees(blank, 100, 100), 1e-4);
        assertEquals(0f, OcrImageOps.estimateSkewDegrees(new int[4], 2, 2), 1e-4);
    }
}
