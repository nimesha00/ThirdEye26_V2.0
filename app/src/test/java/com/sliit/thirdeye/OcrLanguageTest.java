package com.sliit.thirdeye;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sliit.thirdeye.OcrLanguage.Certainty;
import com.sliit.thirdeye.OcrLanguage.Preference;
import com.sliit.thirdeye.OcrLanguage.Script;
import com.sliit.thirdeye.OcrLanguage.Speech;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class OcrLanguageTest {

    private static final String SINHALA = "ශ්‍රී ලංකාව";        // "Sri Lanka"
    private static final String TAMIL = "இலங்கை வங்கி";          // "Bank of Ceylon"
    private static final Set<String> ALL = new HashSet<>(Arrays.asList("sin", "tam", "eng"));

    // ------------------------------------------------------------ script detection

    @Test
    public void detectsEachScript() {
        assertEquals(Script.SINHALA, OcrLanguage.detectScript(SINHALA));
        assertEquals(Script.TAMIL, OcrLanguage.detectScript(TAMIL));
        assertEquals(Script.LATIN, OcrLanguage.detectScript("Bus stop"));
    }

    @Test
    public void noLettersIsNone() {
        assertEquals(Script.NONE, OcrLanguage.detectScript(""));
        assertEquals(Script.NONE, OcrLanguage.detectScript(null));
        assertEquals(Script.NONE, OcrLanguage.detectScript("123 - 456 !"));
    }

    @Test
    public void mixedTextGoesToTheMajorityScript() {
        assertEquals(Script.SINHALA, OcrLanguage.detectScript("Colombo කොළඹ කොටුව දුම්රිය ස්ථානය"));
        assertEquals(Script.TAMIL, OcrLanguage.detectScript("Rs. 500 கொழும்பு கோட்டை"));
        assertEquals(Script.LATIN, OcrLanguage.detectScript("Pharmacy open 24 hours ශ"));
    }

    @Test
    public void rangeEdges() {
        assertEquals(Script.SINHALA, OcrLanguage.detectScript("඀"));
        assertEquals(Script.SINHALA, OcrLanguage.detectScript("෿"));
        assertEquals(Script.TAMIL, OcrLanguage.detectScript("஀"));
        assertEquals(Script.TAMIL, OcrLanguage.detectScript("௿"));
        // Telugu (U+0C00) and Malayalam (U+0D00) sit between them and are neither.
        assertEquals(Script.NONE, OcrLanguage.detectScript("కക"));
    }

    @Test
    public void usefulCharsIgnoresSpacesAndPunctuation() {
        assertEquals(0, OcrLanguage.usefulChars(" .,-|\n"));
        assertEquals(5, OcrLanguage.usefulChars("Rs. 500!"));
        // Sinhala vowel signs and virama are combining marks, still counted as text.
        assertEquals(SINHALA.replace(" ", "").replace("‍", "").length(), OcrLanguage.usefulChars(SINHALA));
    }

    // ------------------------------------------------- ML Kit vs Tesseract decision

    @Test
    public void autoKeepsMlKitWhenItReadsEnough() {
        assertFalse(OcrLanguage.shouldRunTesseract("Keep left. Pedestrian crossing ahead.", Preference.AUTO));
    }

    @Test
    public void autoFallsBackWhenMlKitFindsLittle() {
        assertTrue(OcrLanguage.shouldRunTesseract("", Preference.AUTO));
        assertTrue(OcrLanguage.shouldRunTesseract(null, Preference.AUTO));
        assertTrue(OcrLanguage.shouldRunTesseract("c. l- ,a", Preference.AUTO)); // ML Kit junk on Sinhala
    }

    @Test
    public void askingForALanguageAlwaysRunsTesseract() {
        String english = "Keep left. Pedestrian crossing ahead.";
        assertTrue(OcrLanguage.shouldRunTesseract(english, Preference.SINHALA));
        assertTrue(OcrLanguage.shouldRunTesseract(english, Preference.TAMIL));
    }

    @Test
    public void picksTheBetterReading() {
        assertTrue(OcrLanguage.preferTesseract("c. l", SINHALA, Preference.AUTO));
        assertFalse(OcrLanguage.preferTesseract("Exit", "Ex1", Preference.AUTO)); // tie keeps ML Kit
        assertFalse(OcrLanguage.preferTesseract("Exit", "", Preference.SINHALA));
        assertTrue(OcrLanguage.preferTesseract("Long English text here", "ශ", Preference.SINHALA));
    }

    // ------------------------------------------------------------- language files

    @Test
    public void languagesNarrowToTheRequest() {
        assertEquals("sin+tam+eng", OcrLanguage.joinLanguages(OcrLanguage.languagesFor(Preference.AUTO, ALL)));
        assertEquals("sin+eng", OcrLanguage.joinLanguages(OcrLanguage.languagesFor(Preference.SINHALA, ALL)));
        assertEquals("tam+eng", OcrLanguage.joinLanguages(OcrLanguage.languagesFor(Preference.TAMIL, ALL)));
    }

    @Test
    public void languagesSkipMissingFiles() {
        Set<String> onlyEnglish = Collections.singleton("eng");
        assertEquals("eng", OcrLanguage.joinLanguages(OcrLanguage.languagesFor(Preference.SINHALA, onlyEnglish)));
        assertTrue(OcrLanguage.languagesFor(Preference.AUTO, Collections.<String>emptySet()).isEmpty());
    }

    @Test
    public void missingFilesAreReportedPerRequest() {
        Set<String> onlyTamil = new HashSet<>(Arrays.asList("tam", "eng"));
        assertEquals(Collections.singletonList("sin"), OcrLanguage.missingFor(Preference.SINHALA, onlyTamil));
        assertTrue(OcrLanguage.missingFor(Preference.TAMIL, onlyTamil).isEmpty());
        assertEquals(Collections.singletonList("sin"), OcrLanguage.missingFor(Preference.AUTO, onlyTamil));
        assertEquals(Arrays.asList("sin", "tam"),
                OcrLanguage.missingFor(Preference.AUTO, Collections.<String>emptySet()));
        assertTrue(OcrLanguage.missingFor(Preference.AUTO, ALL).isEmpty());
    }

    // ------------------------------------------------------------- speech language

    @Test
    public void speaksWithTheMatchingVoiceWhenInstalled() {
        assertEquals(Speech.SINHALA_VOICE, OcrLanguage.chooseSpeech(Script.SINHALA, true, false));
        assertEquals(Speech.TAMIL_VOICE, OcrLanguage.chooseSpeech(Script.TAMIL, false, true));
    }

    @Test
    public void explainsWhenTheVoiceIsMissing() {
        assertEquals(Speech.SINHALA_NO_VOICE, OcrLanguage.chooseSpeech(Script.SINHALA, false, true));
        assertEquals(Speech.TAMIL_NO_VOICE, OcrLanguage.chooseSpeech(Script.TAMIL, true, false));
    }

    @Test
    public void englishAndNumbersUseTheEnglishVoice() {
        assertEquals(Speech.ENGLISH, OcrLanguage.chooseSpeech(Script.LATIN, false, false));
        assertEquals(Speech.ENGLISH, OcrLanguage.chooseSpeech(Script.NONE, true, true));
    }

    @Test
    public void voiceCandidatesPreferSriLanka() {
        assertEquals("si", OcrLanguage.voiceCandidates(Script.SINHALA)[0].getLanguage());
        assertEquals("LK", OcrLanguage.voiceCandidates(Script.SINHALA)[0].getCountry());
        assertEquals("ta", OcrLanguage.voiceCandidates(Script.TAMIL)[0].getLanguage());
        assertEquals("LK", OcrLanguage.voiceCandidates(Script.TAMIL)[0].getCountry());
    }

    @Test
    public void certaintyBands() {
        assertEquals(Certainty.SURE, OcrLanguage.certainty(90));
        assertEquals(Certainty.SURE, OcrLanguage.certainty(75));
        assertEquals(Certainty.FAIRLY_SURE, OcrLanguage.certainty(60));
        assertEquals(Certainty.UNSURE, OcrLanguage.certainty(49));
        assertEquals(Certainty.UNSURE, OcrLanguage.certainty(0));
    }

    // ------------------------------------------------------------ voice commands

    @Test
    public void spokenLanguageChoice() {
        assertEquals(Preference.SINHALA, OcrLanguage.preferenceFromSpeech("Read in Sinhala"));
        assertEquals(Preference.SINHALA, OcrLanguage.preferenceFromSpeech("read this in sinhalese"));
        assertEquals(Preference.TAMIL, OcrLanguage.preferenceFromSpeech("read in Tamil"));
        assertEquals(Preference.AUTO, OcrLanguage.preferenceFromSpeech("read in English"));
        assertNull(OcrLanguage.preferenceFromSpeech("read this"));
    }

    @Test
    public void storedPreferenceIsForgiving() {
        assertEquals(Preference.TAMIL, OcrLanguage.Preference.fromStored("TAMIL"));
        assertEquals(Preference.AUTO, OcrLanguage.Preference.fromStored(null));
        assertEquals(Preference.AUTO, OcrLanguage.Preference.fromStored("klingon"));
    }
}
