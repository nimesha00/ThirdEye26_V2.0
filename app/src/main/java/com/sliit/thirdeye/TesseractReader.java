package com.sliit.thirdeye;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline Sinhala, Tamil and English OCR with Tesseract (tessdata_fast language files).
 *
 * <p>Language files live in {@code <filesDir>/tessdata/<code>.traineddata}. If the APK bundles
 * them in {@code assets/tessdata/}, they are copied there on first use; they can also be pushed
 * straight into that folder. A missing file is never a crash: {@link #installedLanguages} simply
 * leaves it out and the caller tells the user.
 */
public final class TesseractReader {

    private static final String TAG = "TesseractReader";
    private static final String DIR = "tessdata";
    private static final String EXT = ".traineddata";
    private static final String[] CODES = {
            OcrLanguage.SINHALA_CODE, OcrLanguage.TAMIL_CODE, OcrLanguage.ENGLISH_CODE};

    /** What Tesseract read, with its 0..100 mean confidence. */
    public static final class Result {
        public final String text;
        public final int confidence;

        Result(String text, int confidence) {
            this.text = text;
            this.confidence = confidence;
        }
    }

    private TesseractReader() {}

    /**
     * Copies any bundled language files into the files directory (first use only) and returns
     * the codes ("sin", "tam", "eng") that are ready to use.
     */
    @WorkerThread
    public static Set<String> installedLanguages(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "Can't create " + dir);
        }
        AssetManager assets = context.getAssets();
        Set<String> ready = new HashSet<>();
        for (String code : CODES) {
            File file = new File(dir, code + EXT);
            if (!file.isFile() || file.length() == 0) {
                copyFromAssets(assets, DIR + "/" + code + EXT, file);
            }
            if (file.isFile() && file.length() > 0) ready.add(code);
        }
        return ready;
    }

    /** Copies via a temp file so a half-written file is never mistaken for a good one. */
    private static void copyFromAssets(AssetManager assets, String assetPath, File target) {
        File tmp = new File(target.getPath() + ".part");
        try (InputStream in = assets.open(assetPath); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (IOException e) {
            // Not bundled: normal when the user hasn't added that language.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            Log.e(TAG, "Couldn't move " + tmp + " to " + target);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Reads the photo with the given languages ("sin", "tam", "eng" codes in priority order).
     * Slow (several seconds): call off the main thread. Returns null if Tesseract won't start.
     */
    @WorkerThread
    @Nullable
    public static Result read(Context context, Bitmap photo, List<String> languages) {
        if (languages.isEmpty()) return null;
        Bitmap prepared = prepare(photo);
        TessBaseAPI tess = new TessBaseAPI();
        try {
            // init() wants the folder that *contains* tessdata/.
            if (!tess.init(context.getFilesDir().getAbsolutePath(), OcrLanguage.joinLanguages(languages))) {
                Log.e(TAG, "Tesseract init failed for " + languages);
                return null;
            }
            // Photos hold signs, labels and pages: let Tesseract find the text blocks itself.
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            tess.setImage(prepared);
            String text = tess.getUTF8Text();
            int confidence = tess.meanConfidence();
            return new Result(text == null ? "" : text.trim(), confidence);
        } catch (RuntimeException e) {
            // IllegalArgumentException for a missing/corrupt data folder, or a native error.
            Log.e(TAG, "Tesseract failed", e);
            return null;
        } finally {
            tess.recycle();
            if (prepared != photo) prepared.recycle();
        }
    }

    /**
     * Grey, contrast-stretched, scaled to a size Tesseract reads well, and rotated level.
     * Tesseract does its own black-and-white thresholding, so the result stays greyscale.
     */
    static Bitmap prepare(Bitmap photo) {
        float scale = OcrImageOps.scaleFor(photo.getWidth(), photo.getHeight());
        Bitmap scaled = photo;
        if (scale != 1f) {
            int w = Math.max(1, Math.round(photo.getWidth() * scale));
            int h = Math.max(1, Math.round(photo.getHeight() * scale));
            scaled = Bitmap.createScaledBitmap(photo, w, h, true);
        }
        int w = scaled.getWidth(), h = scaled.getHeight();
        int[] pixels = new int[w * h];
        scaled.getPixels(pixels, 0, w, 0, 0, w, h);
        if (scaled != photo) scaled.recycle();

        int[] gray = OcrImageOps.toGray(pixels);
        OcrImageOps.stretchContrast(gray);
        float skew = OcrImageOps.estimateSkewDegrees(gray, w, h);
        Bitmap level = Bitmap.createBitmap(OcrImageOps.toArgb(gray), w, h, Bitmap.Config.ARGB_8888);
        if (skew == 0f) return level;

        // Rotate about the centre onto a canvas big enough to keep the corners, filled with the
        // paper colour (white) so the new edges don't look like text.
        Matrix m = new Matrix();
        m.postRotate(-skew, w / 2f, h / 2f);
        double rad = Math.toRadians(Math.abs(skew));
        int outW = (int) Math.ceil(w * Math.cos(rad) + h * Math.sin(rad));
        int outH = (int) Math.ceil(h * Math.cos(rad) + w * Math.sin(rad));
        m.postTranslate((outW - w) / 2f, (outH - h) / 2f);
        Bitmap rotated = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rotated);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(level, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        level.recycle();
        return rotated;
    }
}
