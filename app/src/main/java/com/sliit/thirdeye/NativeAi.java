package com.sliit.thirdeye;

import android.util.Log;

/**
 * Loads libthirdeye_ai.so (whisper.cpp + llama.cpp on one ggml) once. The library is only in
 * the APK when the app is built with -PthirdeyeNativeAi=true; otherwise every offline-AI
 * feature that needs it reports "not available" and the app carries on without it.
 */
final class NativeAi {

    private static final String TAG = "NativeAi";
    private static Boolean loaded;

    private NativeAi() {
    }

    static synchronized boolean isLoaded() {
        if (loaded == null) {
            try {
                System.loadLibrary("thirdeye_ai");
                loaded = true;
            } catch (UnsatisfiedLinkError | SecurityException e) {
                Log.i(TAG, "Offline AI library not in this build: " + e.getMessage());
                loaded = false;
            }
        }
        return loaded;
    }
}
