package com.sliit.thirdeye;

import static com.sliit.thirdeye.DescriberChoice.GB;
import static com.sliit.thirdeye.DescriberChoice.MB;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sliit.thirdeye.DescriberChoice.Engine;

import org.junit.Test;

public class DescriberChoiceTest {

    private static final long FILES_500M = 546 * MB;   // Q8_0 model + Q8_0 mmproj
    private static final long FILES_256M = 280 * MB;

    @Test
    public void bigPhoneWithBothModelsUses500M() {
        assertEquals(Engine.VLM_500M, DescriberChoice.choose(true, FILES_500M, FILES_256M, 6 * GB, 3 * GB, false));
    }

    @Test
    public void fourGbPhoneReportingLessStillUses500M() {
        assertEquals(Engine.VLM_500M, DescriberChoice.choose(true, FILES_500M, 0, 3700 * MB, 1200 * MB, false));
    }

    @Test
    public void threeGbPhoneFallsBackTo256M() {
        assertEquals(Engine.VLM_256M, DescriberChoice.choose(true, FILES_500M, FILES_256M, 3 * GB, 1 * GB, false));
    }

    @Test
    public void notEnoughFreeMemoryRightNowFallsBack() {
        // 500M needs files + 512 MB free; 256M needs files + 320 MB free.
        assertEquals(Engine.VLM_256M, DescriberChoice.choose(true, FILES_500M, FILES_256M, 8 * GB, 700 * MB, false));
        assertEquals(Engine.LABELS, DescriberChoice.choose(true, FILES_500M, FILES_256M, 8 * GB, 500 * MB, false));
    }

    @Test
    public void labelsWithoutModelsLibraryOrMemory() {
        assertEquals(Engine.LABELS, DescriberChoice.choose(true, 0, 0, 8 * GB, 4 * GB, false));
        assertEquals(Engine.LABELS, DescriberChoice.choose(false, FILES_500M, FILES_256M, 8 * GB, 4 * GB, false));
        assertEquals(Engine.LABELS, DescriberChoice.choose(true, FILES_500M, FILES_256M, 8 * GB, 4 * GB, true));
        assertEquals(Engine.LABELS, DescriberChoice.choose(true, FILES_500M, FILES_256M, 2 * GB, 1 * GB, false));
    }

    @Test
    public void only256MDownloaded() {
        assertEquals(Engine.VLM_256M, DescriberChoice.choose(true, 0, FILES_256M, 8 * GB, 4 * GB, false));
    }

    @Test
    public void timeoutIsAboutTwentySeconds() {
        assertEquals(20_000, DescriberChoice.TIMEOUT_MS);
    }

    // -------------------------------------------------------------------- tidy

    @Test
    public void tidyKeepsTwoSentencesAndHedges() {
        assertEquals("It looks like a red cup on a table. There is a spoon next to it.",
                DescriberChoice.tidy(" It looks like a red cup on a table. There is a spoon next to it. "
                        + "The table is wooden."));
    }

    @Test
    public void tidyAddsIThinkWhenTheModelSoundsCertain() {
        assertEquals("I think a bottle of water is in the middle of the photo.",
                DescriberChoice.tidy("A bottle of water is in the middle of the photo."));
        assertEquals("I think I see a door ahead.", DescriberChoice.tidy("I see a door ahead"));
    }

    @Test
    public void tidyStripsMarkupAndRolePrefix() {
        assertEquals("This might be a mobile phone.",
                DescriberChoice.tidy("Assistant: This might be a **mobile phone**.<end_of_utterance>"));
    }

    @Test
    public void tidyRejectsEmptyAnswers() {
        assertNull(DescriberChoice.tidy(null));
        assertNull(DescriberChoice.tidy("  "));
        assertNull(DescriberChoice.tidy("<end_of_utterance>"));
    }

    @Test
    public void tidyLimitsVeryLongSentences() {
        StringBuilder sb = new StringBuilder("It might be ");
        for (int i = 0; i < 100; i++) sb.append("very ");
        sb.append("long.");
        String out = DescriberChoice.tidy(sb.toString());
        assertTrue(out.length() <= 302);
        assertTrue(out.endsWith("."));
    }
}
