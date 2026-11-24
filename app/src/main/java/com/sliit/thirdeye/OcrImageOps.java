package com.sliit.thirdeye;

/**
 * Pixel maths for preparing a phone photo for Tesseract, on plain int arrays so it is unit
 * tested on the JVM. Tesseract reads raw phone photos badly: it wants dark, level lines of text
 * on a plain background at roughly 20-40 pixels per line. {@link TesseractReader} uses these to
 * turn the photo grey, stretch its contrast, scale it and straighten it.
 */
public final class OcrImageOps {

    private OcrImageOps() {}

    /** Photos whose long side is smaller than this are scaled up so small print gets bigger. */
    static final int MIN_LONG_SIDE = 2000;
    /** ...and larger ones are scaled down, which keeps three-language OCR to a few seconds. */
    static final int MAX_LONG_SIDE = 2400;
    /** Never enlarge more than this; beyond it Tesseract only sees blur. */
    static final float MAX_UPSCALE = 2.5f;

    /** Deskew searches this many degrees either side of level. */
    static final float MAX_SKEW_DEGREES = 10f;
    static final float SKEW_STEP_DEGREES = 0.5f;
    /** Ignore tilts smaller than this: rotating costs sharpness. */
    static final float MIN_SKEW_TO_FIX = 0.75f;
    /** Ink pixels sampled for the skew search, to keep it fast on a 12-megapixel photo. */
    static final int MAX_SKEW_SAMPLES = 40000;

    /** ARGB pixels to 0..255 luminance (Rec. 601 weights, integer maths). */
    public static int[] toGray(int[] argb) {
        int[] gray = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000;
        }
        return gray;
    }

    /** Grey values back to opaque ARGB. */
    public static int[] toArgb(int[] gray) {
        int[] argb = new int[gray.length];
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i];
            argb[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        return argb;
    }

    /**
     * Stretches the darkest 1% to black and the brightest 1% to white, in place. Lifts the grey,
     * dim text of indoor photos. Leaves nearly flat images alone so noise isn't amplified.
     */
    public static void stretchContrast(int[] gray) {
        if (gray.length == 0) return;
        int[] hist = histogram(gray);
        int clip = gray.length / 100;
        int lo = 0, seen = 0;
        while (lo < 255 && seen + hist[lo] <= clip) seen += hist[lo++];
        int hi = 255;
        seen = 0;
        while (hi > 0 && seen + hist[hi] <= clip) seen += hist[hi--];
        if (hi - lo < 16) return;
        int range = hi - lo;
        for (int i = 0; i < gray.length; i++) {
            int v = (gray[i] - lo) * 255 / range;
            gray[i] = v < 0 ? 0 : (v > 255 ? 255 : v);
        }
    }

    static int[] histogram(int[] gray) {
        int[] hist = new int[256];
        for (int v : gray) hist[v]++;
        return hist;
    }

    /** Otsu's threshold: the grey level that best separates ink from paper. */
    public static int otsuThreshold(int[] gray) {
        int[] hist = histogram(gray);
        long total = gray.length;
        long sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += (long) i * hist[i];
        long weightBack = 0, sumBack = 0;
        double bestVar = -1;
        int best = 127;
        for (int t = 0; t < 256; t++) {
            weightBack += hist[t];
            if (weightBack == 0) continue;
            long weightFore = total - weightBack;
            if (weightFore == 0) break;
            sumBack += (long) t * hist[t];
            double meanBack = (double) sumBack / weightBack;
            double meanFore = (double) (sumAll - sumBack) / weightFore;
            double between = (double) weightBack * weightFore * (meanBack - meanFore) * (meanBack - meanFore);
            if (between > bestVar) {
                bestVar = between;
                best = t;
            }
        }
        return best;
    }

    /** How much to scale a w x h photo before OCR (1 = leave as is). */
    public static float scaleFor(int width, int height) {
        int longSide = Math.max(width, height);
        if (longSide <= 0) return 1f;
        if (longSide < MIN_LONG_SIDE) return Math.min(MAX_UPSCALE, (float) MIN_LONG_SIDE / longSide);
        if (longSide > MAX_LONG_SIDE) return (float) MAX_LONG_SIDE / longSide;
        return 1f;
    }

    /**
     * The angle, in degrees, that lines of text slope at: positive when they run down to the
     * right (clockwise in screen coordinates). Rotate the image by the negative of this to level
     * it. Projection-profile method: when the guessed angle matches the slope, ink piles up into
     * a few sharp rows, so the sum of squared row counts peaks. Returns 0 when there is too little
     * ink to tell or the tilt is too small to be worth fixing.
     */
    public static float estimateSkewDegrees(int[] gray, int width, int height) {
        if (gray.length != width * height || width < 8 || height < 8) return 0f;
        int threshold = otsuThreshold(gray);
        // Ink is the minority side of the threshold, so white-on-dark signs work too.
        int dark = 0;
        for (int v : gray) if (v <= threshold) dark++;
        boolean inkIsDark = dark <= gray.length - dark;
        int ink = inkIsDark ? dark : gray.length - dark;
        if (ink < 50) return 0f;

        int stride = Math.max(1, ink / MAX_SKEW_SAMPLES);
        int[] xs = new int[ink / stride + 1];
        int[] ys = new int[xs.length];
        int n = 0, seen = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = gray[y * width + x];
                if ((v <= threshold) == inkIsDark) {
                    if (seen++ % stride == 0 && n < xs.length) {
                        xs[n] = x;
                        ys[n] = y;
                        n++;
                    }
                }
            }
        }

        float bestAngle = 0f;
        double bestScore = projectionScore(xs, ys, n, 0f, width, height);
        double levelScore = bestScore;
        for (float a = -MAX_SKEW_DEGREES; a <= MAX_SKEW_DEGREES + 1e-3f; a += SKEW_STEP_DEGREES) {
            double score = projectionScore(xs, ys, n, a, width, height);
            if (score > bestScore) {
                bestScore = score;
                bestAngle = a;
            }
        }
        // Require a clear win over "already level" so noise doesn't trigger a rotation.
        if (Math.abs(bestAngle) < MIN_SKEW_TO_FIX || bestScore < levelScore * 1.05) return 0f;
        return bestAngle;
    }

    private static double projectionScore(int[] xs, int[] ys, int n, float degrees, int width, int height) {
        double tan = Math.tan(Math.toRadians(degrees));
        int offset = (int) Math.ceil(Math.abs(tan) * width);
        int[] rows = new int[height + 2 * offset + 1];
        for (int i = 0; i < n; i++) {
            int r = (int) Math.round(ys[i] - xs[i] * tan) + offset;
            if (r >= 0 && r < rows.length) rows[r]++;
        }
        double score = 0;
        for (int c : rows) score += (double) c * c;
        return score;
    }
}
