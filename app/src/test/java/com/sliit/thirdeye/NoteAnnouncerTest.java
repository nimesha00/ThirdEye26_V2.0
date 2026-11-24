package com.sliit.thirdeye;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NoteAnnouncerTest {

    private static NoteAnnouncer.Seen note(String label, float score, float x) {
        // 100x50 boxes; different x means different physical notes.
        return new NoteAnnouncer.Seen(label, score, x, 0, x + 100, 50);
    }

    private static List<NoteAnnouncer.Seen> frame(NoteAnnouncer.Seen... seen) {
        return new ArrayList<>(Arrays.asList(seen));
    }

    private static final List<NoteAnnouncer.Seen> NOTHING = Collections.emptyList();

    // --- labels -------------------------------------------------------------------------

    @Test
    public void valueOf_readsNoteValuesFromLabels() {
        assertEquals(1000, NoteAnnouncer.valueOf("1000"));
        assertEquals(20, NoteAnnouncer.valueOf("20"));
        assertEquals(5000, NoteAnnouncer.valueOf("5000_new_back"));
        assertEquals(500, NoteAnnouncer.valueOf("LKR 500"));
    }

    @Test
    public void valueOf_rejectsNonNotes() {
        assertEquals(0, NoteAnnouncer.valueOf("background"));
        assertEquals(0, NoteAnnouncer.valueOf("25"));
        assertEquals(0, NoteAnnouncer.valueOf("10"));
        assertEquals(0, NoteAnnouncer.valueOf(""));
        assertEquals(0, NoteAnnouncer.valueOf(null));
        assertEquals(0, NoteAnnouncer.valueOf("99999999999"));
    }

    // --- choosing what to say ------------------------------------------------------------

    @Test
    public void sentence_singleSureNote() {
        List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(frame(note("1000", 0.9f, 0)));
        assertEquals("This looks like a 1000 rupee note.", NoteAnnouncer.sentence(notes));
    }

    @Test
    public void sentence_singleUnsureNote() {
        List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(frame(note("100", 0.5f, 0)));
        assertEquals("This might be a 100 rupee note.", NoteAnnouncer.sentence(notes));
    }

    @Test
    public void sentence_severalNotesNamesEachAndTotal() {
        List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(frame(
                note("100", 0.8f, 0), note("1000", 0.9f, 200), note("500", 0.75f, 400)));
        assertEquals("I can see 3 notes: 1000, 500 and 100 rupees. Total 1600 rupees.",
                NoteAnnouncer.sentence(notes));
        assertEquals("1000 + 500 + 100 = Rs 1600", NoteAnnouncer.screenText(notes));
    }

    @Test
    public void sentence_twoSameNotesAreBothCounted() {
        List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(frame(
                note("500", 0.9f, 0), note("500", 0.8f, 300)));
        assertEquals("I can see 2 notes: 500 and 500 rupees. Total 1000 rupees.",
                NoteAnnouncer.sentence(notes));
    }

    @Test
    public void sentence_severalNotesWithAnUnsureOne() {
        List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(frame(
                note("1000", 0.9f, 0), note("50", 0.5f, 200)));
        String s = NoteAnnouncer.sentence(notes);
        assertTrue(s, s.startsWith("I think I can see 2 notes: 1000 and 50 rupees. Total 1050 rupees."));
        assertTrue(s, s.contains("not sure"));
    }

    @Test
    public void recognise_dropsWeakAndUnknownDetections() {
        List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(frame(
                note("1000", 0.3f, 0), note("background", 0.99f, 200), note("20", 0.6f, 400)));
        assertEquals(1, notes.size());
        assertEquals(20, notes.get(0).value);
    }

    @Test
    public void recognise_mergesOverlappingGuessesForOneNote() {
        // The model calls the same note both 100 and 1000; keep only the more confident one.
        List<NoteAnnouncer.Note> notes = NoteAnnouncer.recognise(frame(
                note("100", 0.55f, 0), note("1000", 0.85f, 5)));
        assertEquals(1, notes.size());
        assertEquals(1000, notes.get(0).value);
    }

    @Test
    public void iou_basics() {
        NoteAnnouncer.Seen a = note("20", 1f, 0);
        assertEquals(1f, NoteAnnouncer.iou(a, a), 1e-6);
        assertEquals(0f, NoteAnnouncer.iou(a, note("20", 1f, 500)), 1e-6);
        // Half overlap in x: intersection 50x50, union 150x50.
        assertEquals(1f / 3f, NoteAnnouncer.iou(a, note("20", 1f, 50)), 1e-6);
    }

    // --- timing: stability and cooldown -------------------------------------------------

    @Test
    public void speaksOnlyAfterNoteIsStable() {
        NoteAnnouncer a = new NoteAnnouncer();
        assertNull(a.onFrame(frame(note("1000", 0.9f, 0)), 0));
        assertEquals("This looks like a 1000 rupee note.", a.onFrame(frame(note("1000", 0.9f, 0)), 500));
    }

    @Test
    public void singleFlickeringGuessIsNeverSpoken() {
        NoteAnnouncer a = new NoteAnnouncer();
        assertNull(a.onFrame(frame(note("1000", 0.9f, 0)), 0));
        assertNull(a.onFrame(frame(note("100", 0.9f, 0)), 500));
        assertNull(a.onFrame(frame(note("1000", 0.9f, 0)), 1000));
        assertEquals("This looks like a 1000 rupee note.", a.onFrame(frame(note("1000", 0.9f, 0)), 1500));
    }

    @Test
    public void sameNoteIsNotRepeatedWithinCooldown() {
        NoteAnnouncer a = new NoteAnnouncer();
        long t = 0;
        a.onFrame(frame(note("500", 0.9f, 0)), t);
        assertTrue(a.onFrame(frame(note("500", 0.9f, 0)), t += 500) != null);
        while (t < NoteAnnouncer.REPEAT_AFTER_MS) {
            assertNull(a.onFrame(frame(note("500", 0.9f, 0)), t += 500));
        }
        // Cooldown over, still in view: say it again.
        assertEquals("This looks like a 500 rupee note.", a.onFrame(frame(note("500", 0.9f, 0)), t += 500));
    }

    @Test
    public void confidenceChangeAloneDoesNotRepeat() {
        NoteAnnouncer a = new NoteAnnouncer();
        a.onFrame(frame(note("500", 0.5f, 0)), 0);
        assertTrue(a.onFrame(frame(note("500", 0.5f, 0)), 500) != null);
        assertNull(a.onFrame(frame(note("500", 0.95f, 0)), 1000));
        assertNull(a.onFrame(frame(note("500", 0.95f, 0)), 1500));
    }

    @Test
    public void differentNoteIsSpokenStraightAway() {
        NoteAnnouncer a = new NoteAnnouncer();
        a.onFrame(frame(note("500", 0.9f, 0)), 0);
        a.onFrame(frame(note("500", 0.9f, 0)), 500);
        assertNull(a.onFrame(frame(note("1000", 0.9f, 0)), 1000));
        assertEquals("This looks like a 1000 rupee note.", a.onFrame(frame(note("1000", 0.9f, 0)), 1500));
    }

    @Test
    public void addingANoteAnnouncesTheNewTotal() {
        NoteAnnouncer a = new NoteAnnouncer();
        a.onFrame(frame(note("1000", 0.9f, 0)), 0);
        a.onFrame(frame(note("1000", 0.9f, 0)), 500);
        a.onFrame(frame(note("1000", 0.9f, 0), note("100", 0.9f, 200)), 1000);
        assertEquals("I can see 2 notes: 1000 and 100 rupees. Total 1100 rupees.",
                a.onFrame(frame(note("1000", 0.9f, 0), note("100", 0.9f, 200)), 1500));
    }

    @Test
    public void oneMissedFrameDoesNotCauseARepeat() {
        NoteAnnouncer a = new NoteAnnouncer();
        a.onFrame(frame(note("20", 0.9f, 0)), 0);
        assertTrue(a.onFrame(frame(note("20", 0.9f, 0)), 500) != null);
        assertNull(a.onFrame(NOTHING, 1000));
        assertNull(a.onFrame(frame(note("20", 0.9f, 0)), 1500));
        assertNull(a.onFrame(frame(note("20", 0.9f, 0)), 2000));
    }

    @Test
    public void sameNoteShownAgainAfterAGapIsSpokenAgain() {
        NoteAnnouncer a = new NoteAnnouncer();
        a.onFrame(frame(note("20", 0.9f, 0)), 0);
        assertTrue(a.onFrame(frame(note("20", 0.9f, 0)), 500) != null);
        long t = 500 + NoteAnnouncer.FORGET_AFTER_MS + 500;
        assertNull(a.onFrame(frame(note("20", 0.9f, 0)), t));
        assertEquals("This looks like a 20 rupee note.", a.onFrame(frame(note("20", 0.9f, 0)), t + 500));
    }

    @Test
    public void hintAfterLongTimeWithNoNote() {
        NoteAnnouncer a = new NoteAnnouncer();
        long t = 1000;
        assertNull(a.onFrame(NOTHING, t));
        assertNull(a.onFrame(NOTHING, t + NoteAnnouncer.HINT_AFTER_MS - 1));
        assertEquals(NoteAnnouncer.HINT, a.onFrame(NOTHING, t + NoteAnnouncer.HINT_AFTER_MS));
        // Not again straight away.
        assertNull(a.onFrame(NOTHING, t + NoteAnnouncer.HINT_AFTER_MS + 500));
        assertEquals(NoteAnnouncer.HINT, a.onFrame(NOTHING, t + 2 * NoteAnnouncer.HINT_AFTER_MS));
    }

    @Test
    public void seeingANoteResetsTheHintTimer() {
        NoteAnnouncer a = new NoteAnnouncer();
        a.onFrame(NOTHING, 0);
        a.onFrame(frame(note("50", 0.9f, 0)), 9000);
        assertNull(a.onFrame(NOTHING, NoteAnnouncer.HINT_AFTER_MS + 1000));
    }
}
