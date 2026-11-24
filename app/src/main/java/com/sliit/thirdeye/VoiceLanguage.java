package com.sliit.thirdeye;

import java.util.Locale;

/**
 * The language the user speaks to Third Eye in. Used for Android's speech recogniser and for
 * the speech output voice; remembered in SharedPreferences under {@link #PREF_KEY}.
 */
enum VoiceLanguage {
    ENGLISH("en", "en-LK", new Locale("en", "LK"), "English"),
    SINHALA("si", "si-LK", new Locale("si", "LK"), "Sinhala"),
    TAMIL("ta", "ta-LK", new Locale("ta", "LK"), "Tamil");

    static final String PREFS = "third_eye_settings";
    static final String PREF_KEY = "voice_language";

    /** Short code stored in preferences. */
    final String code;
    /** BCP 47 tag for RecognizerIntent.EXTRA_LANGUAGE. */
    final String recognizerTag;
    /** Locale for TextToSpeech. */
    final Locale ttsLocale;
    /** English name, used in spoken messages. */
    final String englishName;

    VoiceLanguage(String code, String recognizerTag, Locale ttsLocale, String englishName) {
        this.code = code;
        this.recognizerTag = recognizerTag;
        this.ttsLocale = ttsLocale;
        this.englishName = englishName;
    }

    /** The stored code back to a language; anything unknown or missing means English. */
    static VoiceLanguage fromCode(String code) {
        if (code != null) {
            for (VoiceLanguage l : values()) {
                if (l.code.equalsIgnoreCase(code.trim())) return l;
            }
        }
        return ENGLISH;
    }
}
