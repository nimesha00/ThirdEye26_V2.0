package com.sliit.thirdeye;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds optional model files the user downloads separately (Whisper, SmolVLM). They are
 * looked for in two places, in this order:
 *   1. the app's private files folder:  /data/data/com.sliit.thirdeye/files/models/
 *   2. the app's external files folder: /sdcard/Android/data/com.sliit.thirdeye/files/models/
 * The second one can be filled with a plain "adb push", no root or run-as needed.
 */
final class ModelFiles {

    static final String DIR = "models";

    private ModelFiles() {
    }

    static List<File> searchDirs(Context context) {
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(context.getFilesDir(), DIR));
        File external = context.getExternalFilesDir(null);
        if (external != null) dirs.add(new File(external, DIR));
        return dirs;
    }

    /** The first of {@code names} (in preference order) present in any search folder. */
    @Nullable
    static File find(Context context, String... names) {
        return firstExisting(searchDirs(context), names);
    }

    /** Pure lookup, for tests: preference order of names wins over folder order. */
    @Nullable
    static File firstExisting(List<File> dirs, String... names) {
        for (String name : names) {
            for (File dir : dirs) {
                File f = new File(dir, name);
                if (f.isFile() && f.length() > 0) return f;
            }
        }
        return null;
    }
}
