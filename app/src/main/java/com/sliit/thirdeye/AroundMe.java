package com.sliit.thirdeye;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.ar.core.ArCoreApk;

/**
 * Picks the "Around me" screen: live detection with real distances (ARCore depth) when the
 * phone can do it, otherwise the plain camera detection screen.
 */
final class AroundMe {

    private static final String TAG = "AroundMe";
    private static final String PREFS = "around_me";
    private static final String KEY_NO_DEPTH = "depth_unavailable";

    private AroundMe() {
    }

    static Intent intent(Context context) {
        return new Intent(context, useDepth(context) ? DepthDetectionActivity.class : ObjDetection.class);
    }

    /**
     * True unless we know this phone has no ARCore depth. "Still checking" and "ARCore not
     * installed" also go to the depth screen, which explains and falls back if needed.
     */
    static boolean useDepth(Context context) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NO_DEPTH, false)) {
            return false;
        }
        try {
            ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(context);
            if (availability.isUnsupported()) {
                rememberNoDepth(context);
                return false;
            }
            return availability.isSupported() || availability.isTransient();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not check ARCore availability", e);
            return false;
        }
    }

    /** The phone lacks ARCore or its Depth API: go straight to the plain screen from now on. */
    static void rememberNoDepth(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NO_DEPTH, true).apply();
    }
}
