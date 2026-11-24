package com.sliit.thirdeye;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the photo taken by {@link TextRecognitionCameraActivity2} and speaks it.
 *
 * <p>ML Kit (fast, Latin script only) runs first. Tesseract (offline Sinhala, Tamil and English)
 * runs when the user asked for Sinhala or Tamil, or when ML Kit found little or no text.
 * The text is always shown on screen; it is spoken in Sinhala or Tamil when the phone has that
 * voice, otherwise the app explains in English how to add one.
 */
public class TextRecognitionResultActivity extends AppCompatActivity {

    private static final String TAG = "TextResult";
    /** Decoding a 12-megapixel photo at full size wastes memory; OCR needs far less. */
    private static final int MAX_DECODE_SIDE = 3200;

    private ShapeableImageView imageIv;
    private EditText recognizedTextEt;
    private ProgressDialog progressDialog;
    private TextRecognizer textRecognizer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextToSpeech tts;
    private boolean ttsReady;
    /** Speech asked for before the engine was ready; voice choice needs the engine, so wait. */
    private Runnable pendingSpeech;

    private OcrLanguage.Preference preference;
    private Bitmap photo;

    /** One piece of speech in one language. */
    private static final class Utterance {
        final Locale locale;
        final String text;

        Utterance(Locale locale, String text) {
            this.locale = locale;
            this.text = text;
        }
    }

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_recognition_result);

        imageIv = findViewById(R.id.imageIv);
        recognizedTextEt = findViewById(R.id.recognizedTextEt);
        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("Please wait");
        progressDialog.setCanceledOnTouchOutside(false);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        Button btnCheckagain = findViewById(R.id.btnCheckagain);
        btnCheckagain.setOnClickListener(v ->
                startActivity(new Intent(this, TextRecognitionCameraActivity2.class)));

        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setSpeechRate(1f);
            tts.setPitch(1f);
            // The listener may run off the main thread; keep all speech on it.
            runOnUiThread(() -> {
                ttsReady = true;
                if (pendingSpeech != null) {
                    Runnable r = pendingSpeech;
                    pendingSpeech = null;
                    r.run();
                }
            });
        });

        preference = TextRecognitionCameraActivity2.savedPreference(this);
        Uri imageUri = photoUri(getIntent());
        if (imageUri == null) {
            sayEnglish(getString(R.string.ocr_failed));
            return;
        }
        progressDialog.setMessage("Preparing image....");
        progressDialog.show();
        worker.execute(() -> {
            Bitmap bitmap = decode(imageUri);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (bitmap == null) {
                    progressDialog.dismiss();
                    sayEnglish(getString(R.string.ocr_failed));
                    return;
                }
                photo = bitmap;
                imageIv.setImageBitmap(bitmap);
                runMlKit();
            });
        });
    }

    /** The camera screen sends the full content URI; older callers sent only its path. */
    private static Uri photoUri(Intent intent) {
        String uri = intent.getStringExtra(TextRecognitionCameraActivity2.EXTRA_IMAGE_URI);
        if (uri != null) return Uri.parse(uri);
        String piclink = intent.getStringExtra("piclink");
        return piclink == null ? null : Uri.parse("content://media/" + piclink);
    }

    /** Decodes the photo, shrunk by a power of two until its long side fits MAX_DECODE_SIDE. */
    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SIDE) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't open the photo", e);
            return null;
        }
    }

    private void runMlKit() {
        progressDialog.setMessage("Recognizing Text...");
        textRecognizer.process(InputImage.fromBitmap(photo, 0))
                .addOnSuccessListener(text -> afterMlKit(text.getText()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "ML Kit failed", e);
                    afterMlKit("");
                });
    }

    private void afterMlKit(String mlKitText) {
        if (isFinishing()) return;
        if (!OcrLanguage.shouldRunTesseract(mlKitText, preference)) {
            progressDialog.dismiss();
            present(mlKitText, -1, null);
            return;
        }
        progressDialog.setMessage("Reading Sinhala and Tamil...");
        sayEnglish(getString(waitMessage()));
        worker.execute(() -> {
            Set<String> installed = TesseractReader.installedLanguages(this);
            List<String> missing = OcrLanguage.missingFor(preference, installed);
            List<String> languages = OcrLanguage.languagesFor(preference, installed);
            TesseractReader.Result result = TesseractReader.read(this, photo, languages);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                progressDialog.dismiss();
                String missingNote = missing.isEmpty() ? null : missingFilesMessage(missing);
                if (result != null && OcrLanguage.preferTesseract(mlKitText, result.text, preference)) {
                    present(result.text, result.confidence, missingNote);
                } else {
                    present(mlKitText, -1, missingNote);
                }
            });
        });
    }

    private int waitMessage() {
        switch (preference) {
            case SINHALA: return R.string.ocr_wait_sinhala;
            case TAMIL: return R.string.ocr_wait_tamil;
            default: return R.string.ocr_wait_local;
        }
    }

    private String missingFilesMessage(List<String> missing) {
        String names = missing.size() > 1
                ? getString(R.string.ocr_lang_sinhala_and_tamil)
                : languageName(missing.get(0));
        return getString(R.string.ocr_missing_files, names);
    }

    private String languageName(String code) {
        return OcrLanguage.TAMIL_CODE.equals(code)
                ? getString(R.string.ocr_lang_tamil) : getString(R.string.ocr_lang_sinhala);
    }

    /**
     * Shows the text and speaks it in the right voice.
     *
     * @param confidence Tesseract's 0..100 confidence, or -1 for ML Kit (which doesn't give one)
     * @param missingNote said first when a language file the user needs isn't installed
     */
    private void present(String rawText, int confidence, String missingNote) {
        String text = rawText == null ? "" : rawText.trim();
        recognizedTextEt.setText(text);
        if (!ttsReady) {
            pendingSpeech = () -> present(text, confidence, missingNote);
            return;
        }
        List<Utterance> parts = new ArrayList<>();
        Locale english = bestVoice(OcrLanguage.Script.LATIN);
        if (missingNote != null) parts.add(new Utterance(english, missingNote));

        if (OcrLanguage.usefulChars(text) == 0) {
            parts.add(new Utterance(english, getString(R.string.ocr_nothing)));
            say(parts);
            return;
        }

        OcrLanguage.Script script = OcrLanguage.detectScript(text);
        Locale sinhala = bestVoice(OcrLanguage.Script.SINHALA);
        Locale tamil = bestVoice(OcrLanguage.Script.TAMIL);
        // Lets TalkBack read the on-screen text with the right voice too.
        recognizedTextEt.setTextLocale(script == OcrLanguage.Script.SINHALA ? new Locale("si", "LK")
                : script == OcrLanguage.Script.TAMIL ? new Locale("ta", "LK") : english);

        String sureness = surenessMessage(confidence);
        switch (OcrLanguage.chooseSpeech(script, sinhala != null, tamil != null)) {
            case SINHALA_VOICE:
                parts.add(new Utterance(english, joinSentences(getString(R.string.ocr_in_language,
                        getString(R.string.ocr_lang_sinhala)), sureness)));
                parts.add(new Utterance(sinhala, text));
                break;
            case TAMIL_VOICE:
                parts.add(new Utterance(english, joinSentences(getString(R.string.ocr_in_language,
                        getString(R.string.ocr_lang_tamil)), sureness)));
                parts.add(new Utterance(tamil, text));
                break;
            case SINHALA_NO_VOICE:
                parts.add(new Utterance(english, joinSentences(getString(R.string.ocr_no_voice,
                        getString(R.string.ocr_lang_sinhala)), sureness)));
                break;
            case TAMIL_NO_VOICE:
                parts.add(new Utterance(english, joinSentences(getString(R.string.ocr_no_voice,
                        getString(R.string.ocr_lang_tamil)), sureness)));
                break;
            default:
                parts.add(new Utterance(english, joinSentences(getString(R.string.ocr_success), sureness)));
                parts.add(new Utterance(english, text));
        }
        parts.add(new Utterance(english, getString(R.string.ocr_check_again_hint)));
        say(parts);
    }

    private String surenessMessage(int confidence) {
        if (confidence < 0) return null;
        switch (OcrLanguage.certainty(confidence)) {
            case UNSURE: return getString(R.string.ocr_unsure);
            case FAIRLY_SURE: return getString(R.string.ocr_fairly_sure);
            default: return null;
        }
    }

    private static String joinSentences(String a, String b) {
        return b == null ? a : a + " " + b;
    }

    /**
     * The first installed voice for the script, Sri Lankan first; null if none is installed.
     * English always returns a locale (the engine's nearest English). Call only once ttsReady.
     */
    private Locale bestVoice(OcrLanguage.Script script) {
        for (Locale locale : OcrLanguage.voiceCandidates(script)) {
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) return locale;
        }
        return script == OcrLanguage.Script.LATIN ? Locale.ENGLISH : null;
    }

    private void sayEnglish(String text) {
        if (!ttsReady) {
            pendingSpeech = () -> sayEnglish(text);
            return;
        }
        List<Utterance> parts = new ArrayList<>();
        parts.add(new Utterance(bestVoice(OcrLanguage.Script.LATIN), text));
        say(parts);
    }

    /** Speaks the parts in order, switching voice between them. Call only once ttsReady. */
    private void say(List<Utterance> parts) {
        for (int i = 0; i < parts.size(); i++) {
            Utterance u = parts.get(i);
            // The language is captured when speak() is called, so later parts can change it.
            tts.setLanguage(u.locale);
            // Queue rather than flush, so "this takes a few seconds" finishes before the result.
            tts.speak(u.text, TextToSpeech.QUEUE_ADD, null, "ocr" + i);
        }
    }

    @Override
    protected void onDestroy() {
        if (progressDialog.isShowing()) progressDialog.dismiss();
        worker.shutdown();
        textRecognizer.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
