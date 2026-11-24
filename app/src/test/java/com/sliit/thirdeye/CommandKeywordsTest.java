package com.sliit.thirdeye;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sliit.thirdeye.CommandKeywords.Command;
import com.sliit.thirdeye.CommandKeywords.Torch;

import org.junit.Test;

public class CommandKeywordsTest {

    // ------------------------------------------------------------------ Sinhala

    @Test
    public void sinhalaCommands() {
        assertEquals(Command.WHAT_IS_THIS, CommandKeywords.match("මේ මොකක්ද"));
        assertEquals(Command.MONEY, CommandKeywords.match("මේ නෝට්ටුව කීයද"));
        assertEquals(Command.MONEY, CommandKeywords.match("සල්ලි ගණන් කරන්න"));
        assertEquals(Command.READ_TEXT, CommandKeywords.match("මේක කියවන්න"));
        assertEquals(Command.READ_DISPLAY, CommandKeywords.match("තිරය කියවන්න"));
        assertEquals(Command.AROUND_ME, CommandKeywords.match("මගේ වටේ මොනවද තියෙන්නේ"));
        assertEquals(Command.MESSAGES, CommandKeywords.match("මගේ පණිවිඩ කියවන්න"));
        assertEquals(Command.SEND_MESSAGE, CommandKeywords.match("පණිවිඩයක් යවන්න"));
        assertEquals(Command.CALL, CommandKeywords.match("අම්මට කෝල් එකක් ගන්න"));
        assertEquals(Command.DIRECTIONS, CommandKeywords.match("මට කොළඹ යන්න ඕන"));
        assertEquals(Command.WEATHER, CommandKeywords.match("අද කාලගුණය කොහොමද"));
        assertEquals(Command.HELP, CommandKeywords.match("මට උදව් කරන්න"));
    }

    // -------------------------------------------------------------------- Tamil

    @Test
    public void tamilCommands() {
        assertEquals(Command.WHAT_IS_THIS, CommandKeywords.match("இது என்ன"));
        assertEquals(Command.MONEY, CommandKeywords.match("இந்த நோட்டு எவ்வளவு"));
        assertEquals(Command.MONEY, CommandKeywords.match("பணம்"));
        assertEquals(Command.READ_TEXT, CommandKeywords.match("இதை படி"));
        assertEquals(Command.READ_TEXT, CommandKeywords.match("இதை படிக்கவும்"));
        assertEquals(Command.READ_DISPLAY, CommandKeywords.match("திரையை படி"));
        assertEquals(Command.AROUND_ME, CommandKeywords.match("என்னைச் சுற்றி என்ன இருக்கிறது"));
        assertEquals(Command.MESSAGES, CommandKeywords.match("என் செய்திகளை படி"));
        assertEquals(Command.SEND_MESSAGE, CommandKeywords.match("செய்தி அனுப்பு"));
        assertEquals(Command.CALL, CommandKeywords.match("அம்மாவை அழை"));
        assertEquals(Command.WEATHER, CommandKeywords.match("இன்று வானிலை எப்படி"));
        assertEquals(Command.HELP, CommandKeywords.match("உதவி"));
    }

    @Test
    public void tamilHowToGoIsDirectionsNotRead() {
        // "எப்படி" (how) contains "படி" (read); "படி" is only matched as a whole word.
        assertEquals(Command.DIRECTIONS, CommandKeywords.match("கண்டிக்கு எப்படி போவது"));
        assertEquals(Command.DIRECTIONS, CommandKeywords.match("பஸ் நிலையத்துக்கு வழி சொல்லு"));
    }

    @Test
    public void englishAndUnknownAreLeftToRoute() {
        assertNull(CommandKeywords.match("what is this"));
        assertNull(CommandKeywords.match("read my messages"));
        assertNull(CommandKeywords.match(""));
        assertNull(CommandKeywords.match(null));
    }

    // -------------------------------------------------------------------- torch

    @Test
    public void torchNeedsTorchWordAndOnOff() {
        assertEquals(Torch.ON, CommandKeywords.torch("turn the torch on"));
        assertEquals(Torch.ON, CommandKeywords.torch("switch on the flashlight"));
        assertEquals(Torch.OFF, CommandKeywords.torch("torch off"));
        assertEquals(Torch.OFF, CommandKeywords.torch("turn off the flash"));
        assertEquals(Torch.ASK, CommandKeywords.torch("torch"));
    }

    @Test
    public void lightAloneDoesNotSwitchTheTorch() {
        // The old code switched the torch on for any sentence containing "light".
        assertNull(CommandKeywords.torch("is the traffic light green"));
        assertNull(CommandKeywords.torch("what is this light on the wall"));
        assertNull(CommandKeywords.torch("is it daylight outside"));
        assertNull(CommandKeywords.torch("what is flashing on the screen"));
        assertNull(CommandKeywords.torch("what is this"));
    }

    @Test
    public void torchInSinhalaAndTamil() {
        assertEquals(Torch.ON, CommandKeywords.torch("ටෝච් එක දාන්න"));
        assertEquals(Torch.OFF, CommandKeywords.torch("ලයිට් එක නිවන්න"));
        assertEquals(Torch.ON, CommandKeywords.torch("டார்ச் ஆன் பண்ணு"));
        assertEquals(Torch.OFF, CommandKeywords.torch("விளக்கை அணை"));
    }

    // ------------------------------------------------------------------ yes / no

    @Test
    public void yesAnswers() {
        assertTrue(CommandKeywords.isYes("yes"));
        assertTrue(CommandKeywords.isYes("Yes, send it now"));
        assertTrue(CommandKeywords.isYes("okay"));
        assertTrue(CommandKeywords.isYes("sure I know"));
        assertTrue(CommandKeywords.isYes("ඔව්"));
        assertTrue(CommandKeywords.isYes("சரி அனுப்பு"));
        assertTrue(CommandKeywords.isYes("යවන්න"));
        assertFalse(CommandKeywords.isYes("කියවන්න"));
    }

    @Test
    public void wordsContainingNoAreNotNo() {
        // The old check treated "now", "know", "nothing" etc. as "no".
        assertFalse(CommandKeywords.isNo("send it now"));
        assertFalse(CommandKeywords.isNo("yes I know"));
        assertFalse(CommandKeywords.isNo("nothing to change, send it"));
        assertFalse(CommandKeywords.isNo("okay snow"));
    }

    @Test
    public void noAnswers() {
        assertTrue(CommandKeywords.isNo("no"));
        assertTrue(CommandKeywords.isNo("No."));
        assertTrue(CommandKeywords.isNo("don't send it"));
        assertTrue(CommandKeywords.isNo("don’t send it"));
        assertTrue(CommandKeywords.isNo("do not send"));
        assertTrue(CommandKeywords.isNo("cancel"));
        assertTrue(CommandKeywords.isNo("එපා"));
        assertTrue(CommandKeywords.isNo("வேண்டாம்"));
        assertFalse(CommandKeywords.isYes("no don't send"));
        assertFalse(CommandKeywords.isYes("yes no"));
        assertFalse(CommandKeywords.isYes("hmm"));
    }

    // ------------------------------------------------------------- language

    @Test
    public void languageSwitch() {
        assertEquals(VoiceLanguage.SINHALA, CommandKeywords.languageSwitch("speak Sinhala"));
        assertEquals(VoiceLanguage.TAMIL, CommandKeywords.languageSwitch("speak tamil"));
        assertEquals(VoiceLanguage.ENGLISH, CommandKeywords.languageSwitch("Speak English"));
        assertEquals(VoiceLanguage.TAMIL, CommandKeywords.languageSwitch("change the language to Tamil"));
        assertEquals(VoiceLanguage.SINHALA, CommandKeywords.languageSwitch("සිංහලෙන් කතා කරන්න"));
        assertEquals(VoiceLanguage.TAMIL, CommandKeywords.languageSwitch("தமிழில் பேசு"));
        assertEquals(VoiceLanguage.ENGLISH, CommandKeywords.languageSwitch("ඉංග්‍රීසියෙන් කතා කරන්න"));
        assertEquals(VoiceLanguage.SINHALA, CommandKeywords.languageSwitch("සිංහල"));
    }

    @Test
    public void mentioningALanguageIsNotASwitch() {
        assertNull(CommandKeywords.languageSwitch("read this tamil newspaper"));
        assertNull(CommandKeywords.languageSwitch("translate sinhala to english"));
        assertNull(CommandKeywords.languageSwitch("what is this"));
    }
}
