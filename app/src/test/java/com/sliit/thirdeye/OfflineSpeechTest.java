package com.sliit.thirdeye;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sliit.thirdeye.OfflineSpeech.Recogniser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

public class OfflineSpeechTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void androidRecogniserStaysMainWhenOnline() {
        assertEquals(Recogniser.ANDROID, OfflineSpeech.choose(true, true, true));
        assertEquals(Recogniser.ANDROID, OfflineSpeech.choose(true, true, false));
    }

    @Test
    public void whisperWhenOfflineOrNoRecogniser() {
        assertEquals(Recogniser.WHISPER, OfflineSpeech.choose(true, false, true));
        assertEquals(Recogniser.WHISPER, OfflineSpeech.choose(false, true, true));
        assertEquals(Recogniser.WHISPER, OfflineSpeech.choose(false, false, true));
    }

    @Test
    public void withoutWhisperTryAndroidElseNothing() {
        assertEquals(Recogniser.ANDROID, OfflineSpeech.choose(true, false, false));
        assertEquals(Recogniser.NONE, OfflineSpeech.choose(false, false, false));
        assertEquals(Recogniser.NONE, OfflineSpeech.choose(false, true, false));
    }

    @Test
    public void cleanTranscriptDropsNonSpeech() {
        assertEquals("what is this", OfflineSpeech.cleanTranscript("  what is this "));
        assertEquals("", OfflineSpeech.cleanTranscript("[BLANK_AUDIO]"));
        assertEquals("", OfflineSpeech.cleanTranscript(" (music) "));
        assertEquals("read my messages.", OfflineSpeech.cleanTranscript("*coughs* read my messages."));
        assertEquals("", OfflineSpeech.cleanTranscript(" . "));
        assertEquals("", OfflineSpeech.cleanTranscript(null));
    }

    @Test
    public void endpointerStopsAfterSpeechThenSilence() {
        OfflineSpeech.Endpointer e = new OfflineSpeech.Endpointer();
        for (int i = 0; i < 10; i++) assertFalse(e.feed(2000, 100));   // 1 s of speech
        for (int i = 0; i < 11; i++) assertFalse(e.feed(100, 100));    // 1.1 s quiet
        assertTrue(e.feed(100, 100));                                   // 1.2 s quiet: stop
        assertTrue(e.heardSpeech());
    }

    @Test
    public void endpointerGivesUpWhenNobodySpeaks() {
        OfflineSpeech.Endpointer e = new OfflineSpeech.Endpointer();
        for (int i = 0; i < 49; i++) assertFalse(e.feed(50, 100));
        assertTrue(e.feed(50, 100));
        assertFalse(e.heardSpeech());
    }

    @Test
    public void endpointerHasAHardLimit() {
        OfflineSpeech.Endpointer e = new OfflineSpeech.Endpointer();
        for (int i = 0; i < 99; i++) assertFalse(e.feed(5000, 100));
        assertTrue(e.feed(5000, 100));
    }

    @Test
    public void rms() {
        short[] pcm = {1000, -1000, 1000, -1000};
        assertEquals(1000, OfflineSpeech.rms(pcm, 4), 0.001);
        assertEquals(0, OfflineSpeech.rms(pcm, 0), 0.001);
    }

    @Test
    public void voiceLanguageFromStoredCode() {
        assertEquals(VoiceLanguage.SINHALA, VoiceLanguage.fromCode("si"));
        assertEquals(VoiceLanguage.TAMIL, VoiceLanguage.fromCode("TA"));
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.fromCode(null));
        assertEquals(VoiceLanguage.ENGLISH, VoiceLanguage.fromCode("fr"));
        assertEquals("si-LK", VoiceLanguage.SINHALA.recognizerTag);
        assertEquals("ta-LK", VoiceLanguage.TAMIL.recognizerTag);
    }

    @Test
    public void modelFilesPreferNameOrderAndSkipEmptyFiles() throws IOException {
        File a = tmp.newFolder("a");
        File b = tmp.newFolder("b");
        write(new File(b, "ggml-tiny.en.bin"), 10);
        write(new File(a, "ggml-base.en.bin"), 0);          // empty: a failed download
        assertEquals(new File(b, "ggml-tiny.en.bin"),
                ModelFiles.firstExisting(Arrays.asList(a, b), WhisperRecognizer.MODEL_NAMES));
        write(new File(b, "ggml-base.en-q5_1.bin"), 10);
        assertEquals(new File(b, "ggml-base.en-q5_1.bin"),
                ModelFiles.firstExisting(Arrays.asList(a, b), WhisperRecognizer.MODEL_NAMES));
        assertNull(ModelFiles.firstExisting(Arrays.asList(a), WhisperRecognizer.MODEL_NAMES));
    }

    private static void write(File f, int bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[bytes]);
        }
    }
}
