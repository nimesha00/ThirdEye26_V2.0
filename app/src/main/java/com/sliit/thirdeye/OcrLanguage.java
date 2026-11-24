package com.sliit.thirdeye;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure decisions for the offline text reader (no Android classes, so it is unit tested on the JVM):
 * which script a reading is in, when to fall back from ML Kit to Tesseract, which Tesseract
 * languages to load, which voice to speak with and how sure to sound.
 */
public final class OcrLanguage {

    private OcrLanguage() {}

    /** The writing system a piece of text is mostly in. */
    public enum Script { LATIN, SINHALA, TAMIL, NONE }

    /** What the user asked the reader to expect. AUTO reads English with ML Kit and falls back. */
    public enum Preference {
        AUTO, SINHALA, TAMIL;

        /** Stored in SharedPreferences; unknown or missing values mean AUTO. */
        public static Preference fromStored(String value) {
            if (value != null) {
                for (Preference p : values()) {
                    if (p.name().equals(value)) return p;
                }
            }
            return AUTO;
        }
    }

    /** How the result should be spoken. */
    public enum Speech {
        /** English (or digits/punctuation only): speak with the normal English voice. */
        ENGLISH,
        SINHALA_VOICE,
        TAMIL_VOICE,
        /** Sinhala text but the phone has no Sinhala voice: explain in English, show the text. */
        SINHALA_NO_VOICE,
        TAMIL_NO_VOICE
    }

    /** How sure the app should sound about a Tesseract reading. */
    public enum Certainty { SURE, FAIRLY_SURE, UNSURE }

    /** Tesseract language codes, as named in tessdata_fast (sin.traineddata, ...). */
    public static final String SINHALA_CODE = "sin";
    public static final String TAMIL_CODE = "tam";
    public static final String ENGLISH_CODE = "eng";

    /** ML Kit readings with fewer letters or digits than this count as "little or no text". */
    static final int MIN_USEFUL_CHARS = 10;

    /** meanConfidence() is 0..100. Below these the app says it is unsure. */
    static final int SURE_CONFIDENCE = 75;
    static final int FAIRLY_SURE_CONFIDENCE = 50;

    static boolean isSinhala(int cp) { return cp >= 0x0D80 && cp <= 0x0DFF; }

    static boolean isTamil(int cp) { return cp >= 0x0B80 && cp <= 0x0BFF; }

    static boolean isLatinLetter(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= 0x00C0 && cp <= 0x024F);
    }

    /** The script most of the letters in {@code text} belong to; NONE if it has no letters. */
    public static Script detectScript(String text) {
        if (text == null) return Script.NONE;
        int sinhala = 0, tamil = 0, latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isSinhala(cp)) sinhala++;
            else if (isTamil(cp)) tamil++;
            else if (isLatinLetter(cp)) latin++;
        }
        if (sinhala == 0 && tamil == 0 && latin == 0) return Script.NONE;
        // Ties go to the local scripts: a Sinhala sign with an English word is a Sinhala sign.
        if (sinhala >= tamil && sinhala >= latin) return Script.SINHALA;
        if (tamil >= latin) return Script.TAMIL;
        return Script.LATIN;
    }

    /** Letters and digits in any script, ignoring spaces, punctuation and stray marks. */
    public static int usefulChars(String text) {
        if (text == null) return 0;
        int n = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || isSinhala(cp) || isTamil(cp)) n++;
        }
        return n;
    }

    /**
     * Run Tesseract after ML Kit when the user asked for Sinhala or Tamil (ML Kit only reads
     * Latin script), or when ML Kit found little or no text, which is what it returns for a
     * Sinhala or Tamil page.
     */
    public static boolean shouldRunTesseract(String mlKitText, Preference preference) {
        if (preference != Preference.AUTO) return true;
        return usefulChars(mlKitText) < MIN_USEFUL_CHARS;
    }

    /**
     * Picks which reading to show and speak once both engines have run. When the user asked for
     * Sinhala or Tamil, Tesseract wins whenever it found anything. Otherwise the reading with more
     * real characters wins, and a tie keeps ML Kit, which is more accurate on English.
     */
    public static boolean preferTesseract(String mlKitText, String tesseractText, Preference preference) {
        int tess = usefulChars(tesseractText);
        if (tess == 0) return false;
        if (preference != Preference.AUTO) return true;
        return tess > usefulChars(mlKitText);
    }

    /**
     * Tesseract languages to load, in priority order, given which .traineddata files exist.
     * A chosen language narrows the set (faster and more accurate); English is kept as the
     * second language because signs and labels mix in English words.
     */
    public static List<String> languagesFor(Preference preference, Set<String> available) {
        List<String> wanted = new ArrayList<>();
        switch (preference) {
            case SINHALA:
                wanted.add(SINHALA_CODE);
                wanted.add(ENGLISH_CODE);
                break;
            case TAMIL:
                wanted.add(TAMIL_CODE);
                wanted.add(ENGLISH_CODE);
                break;
            default:
                wanted.add(SINHALA_CODE);
                wanted.add(TAMIL_CODE);
                wanted.add(ENGLISH_CODE);
        }
        List<String> result = new ArrayList<>();
        for (String code : wanted) {
            if (available.contains(code)) result.add(code);
        }
        return result;
    }

    /** Tesseract's language argument: "sin+tam+eng". */
    public static String joinLanguages(List<String> codes) {
        StringBuilder sb = new StringBuilder();
        for (String code : codes) {
            if (sb.length() > 0) sb.append('+');
            sb.append(code);
        }
        return sb.toString();
    }

    /**
     * Language files the user's request needs but the phone doesn't have. Asking for Sinhala
     * needs sin; AUTO needs sin and tam (English alone still works through ML Kit).
     */
    public static List<String> missingFor(Preference preference, Set<String> available) {
        List<String> missing = new ArrayList<>();
        if (preference != Preference.TAMIL && !available.contains(SINHALA_CODE)) missing.add(SINHALA_CODE);
        if (preference != Preference.SINHALA && !available.contains(TAMIL_CODE)) missing.add(TAMIL_CODE);
        return missing;
    }

    /** Which voice to use, given what the phone's text-to-speech engine has installed. */
    public static Speech chooseSpeech(Script script, boolean hasSinhalaVoice, boolean hasTamilVoice) {
        switch (script) {
            case SINHALA:
                return hasSinhalaVoice ? Speech.SINHALA_VOICE : Speech.SINHALA_NO_VOICE;
            case TAMIL:
                return hasTamilVoice ? Speech.TAMIL_VOICE : Speech.TAMIL_NO_VOICE;
            default:
                return Speech.ENGLISH;
        }
    }

    /** Voice locales to try, best first: Sri Lankan, then any voice for the language. */
    public static Locale[] voiceCandidates(Script script) {
        switch (script) {
            case SINHALA:
                return new Locale[] {new Locale("si", "LK"), new Locale("si")};
            case TAMIL:
                return new Locale[] {new Locale("ta", "LK"), new Locale("ta", "IN"), new Locale("ta")};
            default:
                return new Locale[] {new Locale("en", "LK"), Locale.UK, Locale.US};
        }
    }

    /** Tesseract's 0..100 mean confidence as something the app can say. */
    public static Certainty certainty(int meanConfidence) {
        if (meanConfidence >= SURE_CONFIDENCE) return Certainty.SURE;
        if (meanConfidence >= FAIRLY_SURE_CONFIDENCE) return Certainty.FAIRLY_SURE;
        return Certainty.UNSURE;
    }

    /**
     * The language named in a spoken command such as "read in Sinhala", or null if none.
     * "English" returns AUTO so the user can switch back.
     */
    public static Preference preferenceFromSpeech(String said) {
        if (said == null) return null;
        String s = said.toLowerCase(Locale.ROOT);
        if (s.contains("sinhala") || s.contains("sinhalese") || s.contains("singhala")
                || s.contains("සිංහල")) {
            return Preference.SINHALA;
        }
        if (s.contains("tamil") || s.contains("தமிழ்")) return Preference.TAMIL;
        if (s.contains("english")) return Preference.AUTO;
        return null;
    }
}
