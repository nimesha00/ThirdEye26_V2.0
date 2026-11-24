package com.sliit.thirdeye;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Decides what the note reader says. Pure Java (no Android types) so it can be unit tested.
 *
 * Feed it the detections of each analysed camera frame with {@link #onFrame}; it returns the
 * sentence to speak, or null to stay quiet. It:
 * <ul>
 *   <li>keeps only labels that are Sri Lankan note values (20, 50, 100, 500, 1000, 5000);</li>
 *   <li>drops weak guesses and merges two guesses for the same note (overlapping boxes);</li>
 *   <li>waits until the same notes are seen in {@link #STABLE_FRAMES} frames in a row, so a
 *       single flickering guess is never spoken;</li>
 *   <li>speaks a set of notes once, and repeats the same set only after {@link #REPEAT_AFTER_MS};</li>
 *   <li>names each value and the total when several notes are visible;</li>
 *   <li>after a while with no note, gives a short hint on how to hold the note.</li>
 * </ul>
 * Not thread-safe; call it from one thread.
 */
final class NoteAnnouncer {

    static final int[] DENOMINATIONS = {20, 50, 100, 500, 1000, 5000};

    /** At or above this score: "This looks like". Below it (down to MIN_SCORE): "This might be". */
    static final float SURE_SCORE = 0.70f;
    /** Detections below this are ignored. */
    static final float MIN_SCORE = 0.45f;
    /** Boxes overlapping more than this (intersection over union) are the same note. */
    static final float SAME_NOTE_IOU = 0.5f;
    /** The same notes must be seen in this many analysed frames in a row before speaking. */
    static final int STABLE_FRAMES = 2;
    /** The same notes are repeated at most this often while they stay in view. */
    static final long REPEAT_AFTER_MS = 8000;
    /** Once no note has been seen for this long, the same note shown again counts as new. */
    static final long FORGET_AFTER_MS = 2500;
    /** With nothing found for this long, say how to hold the note (then again after as long). */
    static final long HINT_AFTER_MS = 10000;

    static final String HINT =
            "No note found yet. Hold the note flat, about 20 centimetres from the camera, in good light.";

    /** One detection: label, score and box in any consistent coordinates. */
    static final class Seen {
        final String label;
        final float score;
        final float left, top, right, bottom;

        Seen(String label, float score, float left, float top, float right, float bottom) {
            this.label = label;
            this.score = score;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    /** A recognised note: its value in rupees and how sure the model is. */
    static final class Note {
        final int value;
        final float score;
        final Seen seen;

        Note(int value, float score, Seen seen) {
            this.value = value;
            this.score = score;
            this.seen = seen;
        }

        boolean sure() {
            return score >= SURE_SCORE;
        }
    }

    private String candidateKey = "";
    private int candidateFrames;
    private String lastSpokenKey = "";
    private long lastSpokenAt = Long.MIN_VALUE / 2;
    private long lastNoteOrHintAt;
    private long lastNoteSeenAt = Long.MIN_VALUE / 2;
    private boolean started;

    /**
     * Call once per analysed frame. Returns the sentence to speak now, or null.
     *
     * @param nowMs a monotonic clock in milliseconds (e.g. SystemClock.elapsedRealtime())
     */
    String onFrame(List<Seen> detections, long nowMs) {
        if (!started) {
            started = true;
            lastNoteOrHintAt = nowMs;
        }
        List<Note> notes = recognise(detections);
        String key = key(notes);

        if (notes.isEmpty()) {
            candidateKey = "";
            candidateFrames = 0;
            if (nowMs - lastNoteOrHintAt >= HINT_AFTER_MS) {
                lastNoteOrHintAt = nowMs;
                return HINT;
            }
            return null;
        }
        // After a real gap (not one missed frame), the same note held up again is new.
        if (nowMs - lastNoteSeenAt > FORGET_AFTER_MS) {
            lastSpokenKey = "";
            candidateKey = "";
            candidateFrames = 0;
        }
        lastNoteSeenAt = nowMs;
        lastNoteOrHintAt = nowMs;

        if (key.equals(candidateKey)) {
            candidateFrames++;
        } else {
            candidateKey = key;
            candidateFrames = 1;
        }
        if (candidateFrames < STABLE_FRAMES) return null;

        boolean isNew = !key.equals(lastSpokenKey);
        if (!isNew && nowMs - lastSpokenAt < REPEAT_AFTER_MS) return null;
        lastSpokenKey = key;
        lastSpokenAt = nowMs;
        return sentence(notes);
    }

    /** Valid, confident-enough notes, one per physical note, largest value first. */
    static List<Note> recognise(List<Seen> detections) {
        List<Note> candidates = new ArrayList<>();
        for (Seen s : detections) {
            int value = valueOf(s.label);
            if (value > 0 && s.score >= MIN_SCORE) candidates.add(new Note(value, s.score, s));
        }
        // Best guesses first, then drop any box that overlaps one already kept.
        Collections.sort(candidates, (a, b) -> Float.compare(b.score, a.score));
        List<Note> kept = new ArrayList<>();
        for (Note c : candidates) {
            boolean duplicate = false;
            for (Note k : kept) {
                if (iou(c.seen, k.seen) > SAME_NOTE_IOU) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) kept.add(c);
        }
        Collections.sort(kept, (a, b) -> {
            int byValue = Integer.compare(b.value, a.value);
            return byValue != 0 ? byValue : Float.compare(b.score, a.score);
        });
        return kept;
    }

    /**
     * Note value from a model label: the first number in it, if it is a real note value.
     * "1000", "1000_front", "LKR 1000" -> 1000; "background", "25" -> 0.
     */
    static int valueOf(String label) {
        if (label == null) return 0;
        int i = 0, n = label.length();
        while (i < n && !Character.isDigit(label.charAt(i))) i++;
        int j = i;
        while (j < n && Character.isDigit(label.charAt(j))) j++;
        if (i == j || j - i > 5) return 0;
        int value = Integer.parseInt(label.substring(i, j));
        for (int d : DENOMINATIONS) {
            if (d == value) return value;
        }
        return 0;
    }

    /** What to say for these notes (non-empty, sorted as by {@link #recognise}). */
    static String sentence(List<Note> notes) {
        if (notes.size() == 1) {
            Note n = notes.get(0);
            return (n.sure() ? "This looks like a " : "This might be a ") + n.value + " rupee note.";
        }
        StringBuilder sb = new StringBuilder();
        boolean allSure = true;
        int total = 0;
        for (Note n : notes) {
            allSure &= n.sure();
            total += n.value;
        }
        sb.append(allSure ? "I can see " : "I think I can see ").append(notes.size()).append(" notes: ");
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append(i == notes.size() - 1 ? " and " : ", ");
            sb.append(notes.get(i).value);
        }
        sb.append(" rupees. Total ").append(total).append(" rupees.");
        if (!allSure) sb.append(" Please check, I'm not sure about all of them.");
        return sb.toString();
    }

    /** Short on-screen text, e.g. "1000 + 500 = Rs 1500". */
    static String screenText(List<Note> notes) {
        if (notes.isEmpty()) return "";
        if (notes.size() == 1) return String.format(Locale.ROOT, "Rs %d", notes.get(0).value);
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < notes.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(notes.get(i).value);
            total += notes.get(i).value;
        }
        return sb.append(" = Rs ").append(total).toString();
    }

    /** Identity of a set of notes, ignoring order and confidence: "1000,500,500". */
    private static String key(List<Note> notes) {
        StringBuilder sb = new StringBuilder();
        for (Note n : notes) {
            if (sb.length() > 0) sb.append(',');
            sb.append(n.value);
        }
        return sb.toString();
    }

    static float iou(Seen a, Seen b) {
        float w = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        float h = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (w <= 0 || h <= 0) return 0f;
        float inter = w * h;
        float union = area(a) + area(b) - inter;
        return union <= 0 ? 0f : inter / union;
    }

    private static float area(Seen s) {
        return Math.max(0f, s.right - s.left) * Math.max(0f, s.bottom - s.top);
    }
}
