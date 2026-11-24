package com.sliit.thirdeye;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Offline English speech recognition with whisper.cpp. Used only when Android's recogniser
 * can't be (no recogniser app, or no internet); see {@link OfflineSpeech#choose}.
 *
 * The model (ggml-base.en-q5_1.bin recommended, ~57 MB) is downloaded by the user into the
 * app's models folder; see {@link ModelFiles}. Not thread-safe: create, use and close it on
 * one background thread.
 */
final class WhisperRecognizer implements AutoCloseable {

    private static final String TAG = "WhisperRecognizer";
    static final int SAMPLE_RATE = 16000;

    /** Preference order: base is noticeably more accurate than tiny, quantised is smaller. */
    static final String[] MODEL_NAMES = {
            "ggml-base.en-q5_1.bin", "ggml-base.en.bin",
            "ggml-tiny.en-q5_1.bin", "ggml-tiny.en.bin"};

    private long handle;

    private WhisperRecognizer(long handle) {
        this.handle = handle;
    }

    /** True when the native library is in this build and a model file is on the phone. Cheap. */
    static boolean isReady(Context context) {
        return ModelFiles.find(context, MODEL_NAMES) != null && NativeAi.isLoaded();
    }

    /** Loads the model, or returns null when the library or model is missing. Takes ~1 s. */
    @Nullable
    @WorkerThread
    static WhisperRecognizer create(Context context) {
        File model = ModelFiles.find(context, MODEL_NAMES);
        if (model == null || !NativeAi.isLoaded()) return null;
        long h;
        try {
            h = nativeInit(model.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Library has no Whisper support", e);
            return null;
        }
        return h == 0 ? null : new WhisperRecognizer(h);
    }

    /**
     * Records from the microphone until the user stops talking (see
     * {@link OfflineSpeech.Endpointer}), then transcribes. Returns "" when nothing was said
     * and null on a recording or recognition error. Needs RECORD_AUDIO.
     */
    @Nullable
    @WorkerThread
    String listenOnce(Context context) {
        float[] audio = record(context);
        if (audio == null) return null;
        if (audio.length == 0) return "";
        return transcribe(audio);
    }

    @Nullable
    @WorkerThread
    String transcribe(float[] samples16k) {
        if (handle == 0) return null;
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        byte[] raw = nativeTranscribe(handle, samples16k, threads, "en");
        return raw == null ? null : OfflineSpeech.cleanTranscript(new String(raw, StandardCharsets.UTF_8));
    }

    /** 16 kHz mono float samples, an empty array when the user never spoke, or null on error. */
    @Nullable
    @WorkerThread
    private static float[] record(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return null;
        int chunk = SAMPLE_RATE / 10;                   // 100 ms
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, chunk * 2 * 4));
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Microphone unavailable", e);
            return null;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            return null;
        }

        short[] all = new short[SAMPLE_RATE * OfflineSpeech.Endpointer.MAX_MS / 1000 + chunk];
        int total = 0;
        OfflineSpeech.Endpointer endpointer = new OfflineSpeech.Endpointer();
        try {
            rec.startRecording();
            short[] buf = new short[chunk];
            while (true) {
                int n = rec.read(buf, 0, chunk);
                if (n < 0) return null;
                int copy = Math.min(n, all.length - total);
                System.arraycopy(buf, 0, all, total, copy);
                total += copy;
                if (endpointer.feed(OfflineSpeech.rms(buf, n), n * 1000 / SAMPLE_RATE)
                        || total >= all.length) {
                    break;
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Recording failed", e);
            return null;
        } finally {
            try {
                rec.stop();
            } catch (IllegalStateException ignored) {
                // never started
            }
            rec.release();
        }
        if (!endpointer.heardSpeech()) return new float[0];

        float[] out = new float[total];
        for (int i = 0; i < total; i++) out[i] = all[i] / 32768f;
        return out;
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private static native long nativeInit(String modelPath);

    private static native byte[] nativeTranscribe(long handle, float[] samples, int threads, String language);

    private static native void nativeFree(long handle);
}
