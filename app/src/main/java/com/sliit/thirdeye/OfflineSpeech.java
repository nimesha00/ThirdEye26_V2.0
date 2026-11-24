package com.sliit.thirdeye;

/**
 * Pure decisions for listening: which recogniser to use, when the user has stopped talking,
 * and tidying Whisper's output. No Android types, so it is covered by JVM unit tests.
 */
final class OfflineSpeech {

    enum Recogniser {
        /** Android's RecognizerIntent: best for Sinhala and Tamil, needs a recogniser app. */
        ANDROID,
        /** whisper.cpp on the phone: English only, works with no internet. */
        WHISPER,
        /** Nothing usable. */
        NONE
    }

    private OfflineSpeech() {
    }

    /**
     * Android's recogniser stays the main one. Whisper is used when there is no recogniser
     * app, or when the phone is offline (most recognisers then fail, unless an offline
     * language pack is installed, which we can't see). With no Whisper model, an offline
     * phone still tries Android's recogniser in case it has that pack.
     */
    static Recogniser choose(boolean androidRecogniserAvailable, boolean online, boolean whisperReady) {
        if (androidRecogniserAvailable && online) return Recogniser.ANDROID;
        if (whisperReady) return Recogniser.WHISPER;
        if (androidRecogniserAvailable) return Recogniser.ANDROID;
        return Recogniser.NONE;
    }

    /**
     * Whisper marks non-speech as "[BLANK_AUDIO]", "(music)", "*coughs*" and so on; drop
     * those and surrounding spaces. Returns "" when nothing was said.
     */
    static String cleanTranscript(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*", " ");
        s = s.replaceAll("\\s+", " ").trim();
        // A lone punctuation mark or "." left over is not speech.
        return s.replaceAll("^[\\p{Punct}\\s]+$", "");
    }

    /**
     * Decides when to stop recording: after the user has spoken and then been quiet for a
     * moment, if they never start, or at a hard limit. Feed it the loudness (RMS of 16-bit
     * samples, 0..32767) of each audio chunk in order.
     */
    static final class Endpointer {
        static final double SPEECH_RMS = 700;       // above this a chunk counts as speech
        static final int SILENCE_AFTER_SPEECH_MS = 1200;
        static final int NO_SPEECH_TIMEOUT_MS = 5000;
        static final int MAX_MS = 10000;

        private int elapsedMs;
        private int quietMs;
        private boolean heardSpeech;

        /** Returns true when recording should stop after this chunk. */
        boolean feed(double rms, int chunkMs) {
            elapsedMs += chunkMs;
            if (rms >= SPEECH_RMS) {
                heardSpeech = true;
                quietMs = 0;
            } else {
                quietMs += chunkMs;
            }
            if (elapsedMs >= MAX_MS) return true;
            if (heardSpeech) return quietMs >= SILENCE_AFTER_SPEECH_MS;
            return elapsedMs >= NO_SPEECH_TIMEOUT_MS;
        }

        boolean heardSpeech() {
            return heardSpeech;
        }
    }

    /** Root mean square of 16-bit PCM samples. */
    static double rms(short[] pcm, int count) {
        if (count <= 0) return 0;
        double sum = 0;
        for (int i = 0; i < count; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / count);
    }
}
