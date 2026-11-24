package com.sliit.thirdeye;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Optional offline image description with SmolVLM2 (llama.cpp + libmtmd). Needs the app built
 * with -PthirdeyeNativeAi=true and the model + mmproj files downloaded (see docs/OFFLINE_AI.md).
 *
 * API: {@link #load}, {@link #describe}, {@link #close}. Not thread-safe: load, describe and
 * close on one background thread. {@link #abort} may be called from any thread.
 */
final class SmolVlmDescriber implements AutoCloseable {

    private static final String TAG = "SmolVlmDescriber";

    static final String MODEL_500M = "SmolVLM2-500M-Video-Instruct-Q8_0.gguf";
    static final String[] MMPROJ_500M = {
            "mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf", "mmproj-SmolVLM2-500M-Video-Instruct-f16.gguf"};
    static final String MODEL_256M = "SmolVLM2-256M-Video-Instruct-Q8_0.gguf";
    static final String[] MMPROJ_256M = {
            "mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf", "mmproj-SmolVLM2-256M-Video-Instruct-f16.gguf"};

    /** SmolVLM resizes to 512 px anyway; sending less keeps the copy small. */
    private static final int MAX_SIDE = 512;
    private static final int CONTEXT_TOKENS = 2048;

    private volatile long handle;

    private SmolVlmDescriber(long handle) {
        this.handle = handle;
    }

    /** Which engine this phone should use right now. Cheap; safe on any thread. */
    static DescriberChoice.Engine pick(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mem);
        long bytes500 = size(ModelFiles.find(context, MODEL_500M), ModelFiles.find(context, MMPROJ_500M));
        long bytes256 = size(ModelFiles.find(context, MODEL_256M), ModelFiles.find(context, MMPROJ_256M));
        // Only load the library when there's something to run with it.
        boolean nativeReady = (bytes500 > 0 || bytes256 > 0) && NativeAi.isLoaded();
        return DescriberChoice.choose(nativeReady, bytes500, bytes256, mem.totalMem, mem.availMem, mem.lowMemory);
    }

    /** Loads the chosen model (a few seconds), or returns null when it can't. */
    @Nullable
    @WorkerThread
    static SmolVlmDescriber load(Context context, DescriberChoice.Engine engine) {
        File model;
        File mmproj;
        if (engine == DescriberChoice.Engine.VLM_500M) {
            model = ModelFiles.find(context, MODEL_500M);
            mmproj = ModelFiles.find(context, MMPROJ_500M);
        } else if (engine == DescriberChoice.Engine.VLM_256M) {
            model = ModelFiles.find(context, MODEL_256M);
            mmproj = ModelFiles.find(context, MMPROJ_256M);
        } else {
            return null;
        }
        if (model == null || mmproj == null || !NativeAi.isLoaded()) return null;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        try {
            long h = nativeLoad(model.getAbsolutePath(), mmproj.getAbsolutePath(), threads, CONTEXT_TOKENS);
            if (h == 0) return null;
            Log.i(TAG, "Loaded " + model.getName());
            return new SmolVlmDescriber(h);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Library has no SmolVLM support", e);
            return null;
        }
    }

    /**
     * The model's raw answer about {@code photo}, or null on error or after {@link #abort}.
     * Takes several seconds; callers apply their own timeout.
     */
    @Nullable
    @WorkerThread
    String describe(Bitmap photo, String prompt) {
        long h = handle;
        if (h == 0 || photo == null) return null;
        Bitmap small = scaleDown(photo);
        int w = small.getWidth();
        int ht = small.getHeight();
        int[] argb = new int[w * ht];
        small.getPixels(argb, 0, w, 0, 0, w, ht);
        if (small != photo) small.recycle();
        byte[] rgb = new byte[w * ht * 3];
        for (int i = 0, j = 0; i < argb.length; i++) {
            int p = argb[i];
            rgb[j++] = (byte) (p >> 16);
            rgb[j++] = (byte) (p >> 8);
            rgb[j++] = (byte) p;
        }
        byte[] out = nativeDescribe(h, rgb, w, ht, prompt, DescriberChoice.MAX_TOKENS);
        return out == null ? null : new String(out, StandardCharsets.UTF_8);
    }

    /** Asks a running {@link #describe} to stop soon. Safe from any thread. */
    void abort() {
        long h = handle;
        if (h != 0) nativeAbort(h);
    }

    @Override
    public void close() {
        long h = handle;
        handle = 0;
        if (h != 0) nativeFree(h);
    }

    private static Bitmap scaleDown(Bitmap b) {
        int side = Math.max(b.getWidth(), b.getHeight());
        if (side <= MAX_SIDE) return b;
        float k = (float) MAX_SIDE / side;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                Math.max(1, Math.round(b.getHeight() * k)), true);
    }

    private static long size(@Nullable File model, @Nullable File mmproj) {
        return model == null || mmproj == null ? 0 : model.length() + mmproj.length();
    }

    private static native long nativeLoad(String modelPath, String mmprojPath, int threads, int contextTokens);

    private static native byte[] nativeDescribe(long handle, byte[] rgb, int width, int height,
                                                String prompt, int maxTokens);

    private static native void nativeAbort(long handle);

    private static native void nativeFree(long handle);
}
