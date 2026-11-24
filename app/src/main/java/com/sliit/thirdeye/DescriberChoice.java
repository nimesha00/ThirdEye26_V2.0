package com.sliit.thirdeye;

import java.util.Locale;

/**
 * Pure decisions for the offline "What is this?": whether the phone can run SmolVLM2 (and
 * which size), and how to tidy the model's answer before it is spoken. No Android types, so
 * it is covered by JVM unit tests.
 */
final class DescriberChoice {

    enum Engine {
        /** SmolVLM2-500M: better descriptions, needs a 4 GB phone. */
        VLM_500M,
        /** SmolVLM2-256M: the low-memory option. */
        VLM_256M,
        /** ML Kit labels + MediaPipe detector (always available). */
        LABELS
    }

    static final long GB = 1024L * 1024 * 1024;
    static final long MB = 1024L * 1024;
    /** Phone RAM needed before we even try each model (Android itself uses 1.5-2 GB). */
    static final long MIN_TOTAL_RAM_500M = 4 * GB - 512 * MB;   // phones sold as "4 GB" report ~3.6
    static final long MIN_TOTAL_RAM_256M = 3 * GB - 512 * MB;
    /** Free memory needed on top of the model files: context, image encoder, work buffers. */
    static final long HEADROOM_500M = 512 * MB;
    static final long HEADROOM_256M = 320 * MB;

    /** How long a description may take before we give up and use the labels. */
    static final long TIMEOUT_MS = 20_000;

    /** Short and plain: spoken to someone who can't see the photo, so uncertainty matters. */
    static final String PROMPT = "Describe this photo for a blind person in one or two short, plain "
            + "sentences. Say what the main thing is and where it is. If you are not sure, "
            + "say so, for example \"it might be\".";

    static final int MAX_TOKENS = 80;
    private static final int MAX_CHARS = 300;

    private DescriberChoice() {
    }

    /**
     * Picks the best engine this phone can run right now.
     *
     * @param nativeReady  the app was built with the native library and it loaded
     * @param bytes500m    total size of the 500M model + mmproj files, 0 when not downloaded
     * @param bytes256m    same for 256M
     * @param totalRam     ActivityManager.MemoryInfo.totalMem
     * @param availRam     ActivityManager.MemoryInfo.availMem
     * @param lowMemory    ActivityManager.MemoryInfo.lowMemory
     */
    static Engine choose(boolean nativeReady, long bytes500m, long bytes256m,
                         long totalRam, long availRam, boolean lowMemory) {
        if (!nativeReady || lowMemory) return Engine.LABELS;
        if (bytes500m > 0 && totalRam >= MIN_TOTAL_RAM_500M && availRam >= bytes500m + HEADROOM_500M) {
            return Engine.VLM_500M;
        }
        if (bytes256m > 0 && totalRam >= MIN_TOTAL_RAM_256M && availRam >= bytes256m + HEADROOM_256M) {
            return Engine.VLM_256M;
        }
        return Engine.LABELS;
    }

    /**
     * Makes the model's answer fit to speak: no markdown or role prefix, at most two
     * sentences, and an "I think" when the model sounded more certain than it can be.
     * Returns null when nothing usable is left, so the caller falls back to the labels.
     */
    static String tidy(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("<[^>]*>", " ")              // leftover special tokens
                .replaceAll("[*_#`]+", "")
                .replaceAll("^\\s*(Assistant|ASSISTANT)\\s*:", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() < 3) return null;

        // First two sentences.
        int end = -1;
        int sentences = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == s.length() || s.charAt(i + 1) == ' ')) {
                end = i;
                if (++sentences == 2) break;
            }
        }
        if (end >= 0) s = s.substring(0, end + 1);
        if (s.length() > MAX_CHARS) {
            int cut = s.lastIndexOf(' ', MAX_CHARS);
            s = s.substring(0, cut > 0 ? cut : MAX_CHARS) + ".";
        }
        if (!s.endsWith(".") && !s.endsWith("!") && !s.endsWith("?")) s += ".";

        if (!soundsUnsure(s)) {
            boolean keepCapital = s.startsWith("I ") || s.startsWith("I'");
            s = "I think " + (keepCapital ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1));
        }
        return s;
    }

    private static boolean soundsUnsure(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        for (String hedge : new String[]{"might", "maybe", "may be", "probably", "possibly", "seems",
                "appears", "looks like", "not sure", "unclear", "hard to tell", "i think", "likely"}) {
            if (l.contains(hedge)) return true;
        }
        return false;
    }
}
