package com.sliit.thirdeye;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keyword tables for spoken commands in Sinhala and Tamil, plus the whole-word yes/no,
 * torch and "speak Tamil" checks that {@link Home} uses for every language.
 *
 * Android's recogniser writes si-LK and ta-LK speech in Sinhala and Tamil script, so those
 * tables match on script substrings (the languages add suffixes to words, e.g. සල්ලි /
 * සල්ලිවලට). Latin words are always matched as whole words, so "now" is never "no" and
 * "flashing" is never "flash".
 *
 * Pure Java, no Android types, so it is covered by JVM unit tests.
 */
final class CommandKeywords {

    /** Commands the Sinhala and Tamil tables can recognise. English uses Home.route(). */
    enum Command {
        WHAT_IS_THIS, MONEY, READ_TEXT, READ_DISPLAY, AROUND_ME, MESSAGES, SEND_MESSAGE,
        CALL, DIRECTIONS, WEATHER, HELP
    }

    enum Torch { ON, OFF, ASK }

    private CommandKeywords() {
    }

    // ------------------------------------------------------------------ tables
    // Each table: script substrings (si, ta), plus whole Latin words people often mix in.

    private static final String[] MESSAGE = {
            "පණිවිඩ", "පණිවුඩ", "මැසේජ්", "මැසෙජ්", "එස්එම්එස්",
            "செய்தி", "மெசேஜ்", "மெசேஜ", "எஸ்எம்எஸ்"};
    // Word starts, not substrings: කියවන්න (read) contains යවන්න (send).
    private static final String[] SEND_PREFIXES = {"යව", "යැව", "அனுப்ப"};
    private static final String[] MONEY = {
            "සල්ලි", "මුදල්", "නෝට්ටු", "නෝට්", "රුපියල්", "කාසි", "කීයද",
            "பணம்", "காசு", "ரூபாய்", "ரூபா", "நோட்டு", "எவ்வளவு"};
    private static final String[] DISPLAY = {
            "තිරය", "තිරයේ", "ඩිස්ප්ලේ", "මීටර්", "මීටරය", "ස්ක්‍රීන්", "ස්ක්රීන්",
            "திரை", "டிஸ்ப்ளே", "ஸ்கிரீன்", "மீட்டர்"};
    private static final String[] READ = {
            "කියවන්න", "කියවපන්", "කියවලා", "කියවන", "ලියලා තියෙන්නේ", "ලියලා තියෙන්නෙ",
            "படிக்க", "வாசி", "என்ன எழுதி"};
    private static final String[] READ_WORDS = {"படி", "படிங்க"};
    private static final String[] AROUND = {
            "වටේ", "අවට", "වටපිටාව", "බාධක",
            "சுற்றி", "சுத்தி", "தடை"};
    private static final String[] WEATHER = {
            "කාලගුණ", "වැස්ස", "වැහි", "උෂ්ණත්ව",
            "வானிலை", "மழை", "வெப்பநிலை"};
    private static final String[] CALL = {
            "කෝල්", "ඇමතුම", "අමතන්න", "ෆෝන් කරන්න",
            "அழை", "போன் பண்ணு", "போன் போடு", "கால் பண்ணு", "கால் போடு"};
    private static final String[] DIRECTIONS = {
            "යන්න ඕන", "යන්නේ කොහොමද", "යන්නෙ කොහොමද", "යන විදිහ", "මඟ පෙන්වන්න",
            "මග පෙන්වන්න", "පාර පෙන්වන්න", "පාර කියන්න",
            "வழி", "போக வேண்டும்", "போகணும்", "எப்படி போவது", "எப்படி போக"};
    private static final String[] HELP = {
            "උදව්", "උපකාර",
            "உதவி"};
    private static final String[] WHAT_IS_THIS = {
            "මේ මොකක්ද", "මොකක්ද මේ", "මේක මොකක්ද", "මේ මොකද්ද", "මේ මොනවද", "මොනවද මේ",
            "හඳුනාගන්න", "විස්තර කරන්න",
            "இது என்ன", "என்ன இது", "இதென்ன", "அடையாளம் காண", "விவரி"};

    private static final String[] TORCH = {
            "ටෝච්", "ලයිට්", "ෆ්ලෑෂ්", "විදුලි පන්දම",
            "டார்ச்", "விளக்க", "ஃப்ளாஷ்", "லைட்"};
    private static final Set<String> TORCH_WORDS = words("torch", "flash", "flashlight", "torchlight");
    private static final String[] TORCH_ON = {"ඔන්", "දාන්න", "පත්තු", "දල්වන්න", "ஆன்", "போடு", "எரி"};
    private static final Set<String> ON_WORDS = words("on");
    private static final String[] TORCH_OFF = {"ඕෆ්", "ඔෆ්", "නිවන්න", "නවත්වන්න", "ஆஃப்", "அணை", "ஆப்"};
    private static final Set<String> OFF_WORDS = words("off");

    private static final Set<String> YES_WORDS = words(
            "yes", "yeah", "yep", "yup", "ya", "send", "ok", "okay", "sure", "correct");
    private static final String[] YES = {"ඔව්", "හරි", "ஆம்", "ஆமா", "சரி"};
    private static final Set<String> NO_WORDS = words(
            "no", "nope", "nah", "not", "don't", "dont", "cancel", "stop", "wait");
    private static final String[] NO = {"නැහැ", "නෑ", "එපා", "නැත", "இல்லை", "இல்ல", "வேண்டாம்", "வேணாம்", "நிறுத்து"};

    // Language names: whole Latin words, or script stems (so සිංහලෙන් and தமிழில் match too).
    private static final Set<String> ENGLISH_WORDS = words("english");
    private static final String[] ENGLISH_STEMS = {"ඉංග්‍රීසි", "ඉංග්රීසි", "ஆங்கில"};
    private static final Set<String> SINHALA_WORDS = words("sinhala", "sinhalese", "singhala");
    private static final String[] SINHALA_STEMS = {"සිංහල", "சிங்கள"};
    private static final Set<String> TAMIL_WORDS = words("tamil");
    private static final String[] TAMIL_STEMS = {"දෙමළ", "දෙමල", "தமிழ"};
    // "speak Tamil", "change the language to Sinhala", "සිංහලෙන් කතා කරන්න", "தமிழில் பேசு".
    private static final Set<String> SWITCH_WORDS = words(
            "speak", "language", "switch", "change", "use", "talk");
    private static final String[] SWITCH = {"කතා", "භාෂාව", "பேசு", "பேச", "மொழி"};

    // ----------------------------------------------------------------- matching

    /**
     * The Sinhala or Tamil command in {@code said}, or null when none matches (English
     * commands, names, free questions). More specific commands are checked first.
     */
    @Nullable
    static Command match(String said) {
        String s = normalise(said);
        List<String> w = tokens(s);
        if (has(s, MESSAGE) && hasPrefix(w, SEND_PREFIXES)) return Command.SEND_MESSAGE;
        if (has(s, MESSAGE)) return Command.MESSAGES;
        if (has(s, MONEY)) return Command.MONEY;
        if (has(s, DISPLAY)) return Command.READ_DISPLAY;
        if (has(s, READ) || hasWord(w, READ_WORDS)) return Command.READ_TEXT;
        if (has(s, AROUND)) return Command.AROUND_ME;
        if (has(s, WEATHER)) return Command.WEATHER;
        if (has(s, CALL)) return Command.CALL;
        if (has(s, DIRECTIONS)) return Command.DIRECTIONS;
        if (has(s, HELP)) return Command.HELP;
        if (has(s, WHAT_IS_THIS)) return Command.WHAT_IS_THIS;
        return null;
    }

    /**
     * Torch request in any language: needs a torch/flash word AND an on/off word. Returns
     * {@link Torch#ASK} for a torch word without on/off, and null when the torch isn't
     * mentioned at all ("is the light green?", "what's flashing?").
     */
    @Nullable
    static Torch torch(String said) {
        String s = normalise(said);
        List<String> w = tokens(s);
        if (!has(s, TORCH) && !hasAny(w, TORCH_WORDS)) return null;
        if (has(s, TORCH_OFF) || hasAny(w, OFF_WORDS)) return Torch.OFF;
        if (has(s, TORCH_ON) || hasAny(w, ON_WORDS)) return Torch.ON;
        return Torch.ASK;
    }

    /** True for a clear yes ("yes", "ok send it", "ඔව්", "சரி") with no "no" in it. */
    static boolean isYes(String answer) {
        String s = normalise(answer);
        List<String> w = tokens(s);
        boolean yes = hasAny(w, YES_WORDS) || has(s, YES) || hasPrefix(w, SEND_PREFIXES);
        return yes && !isNo(answer);
    }

    /** "no", "don't", "cancel", "නැහැ", "வேண்டாம்"... as whole words, so "now" and "know" aren't no. */
    static boolean isNo(String answer) {
        String s = normalise(answer);
        List<String> w = tokens(s);
        if (hasAny(w, NO_WORDS) || has(s, NO)) return true;
        // "do not send it"
        for (int i = 0; i + 1 < w.size(); i++) {
            if (w.get(i).equals("do") && w.get(i + 1).equals("not")) return true;
        }
        return false;
    }

    /**
     * The language the user asked to switch to ("speak Sinhala", "தமிழில் பேசு", or just
     * "Tamil"), or null. Naming two languages is ambiguous and returns null.
     */
    @Nullable
    static VoiceLanguage languageSwitch(String said) {
        String s = normalise(said);
        List<String> w = tokens(s);
        List<VoiceLanguage> named = new ArrayList<>();
        if (hasAny(w, ENGLISH_WORDS) || has(s, ENGLISH_STEMS)) named.add(VoiceLanguage.ENGLISH);
        if (hasAny(w, SINHALA_WORDS) || has(s, SINHALA_STEMS)) named.add(VoiceLanguage.SINHALA);
        if (hasAny(w, TAMIL_WORDS) || has(s, TAMIL_STEMS)) named.add(VoiceLanguage.TAMIL);
        if (named.size() != 1) return null;
        boolean asked = w.size() == 1 || hasAny(w, SWITCH_WORDS) || has(s, SWITCH);
        return asked ? named.get(0) : null;
    }

    // ------------------------------------------------------------------ helpers

    static String normalise(String said) {
        if (said == null) return "";
        return said.toLowerCase(Locale.ROOT).replace('’', '\'').trim();
    }

    /** Splits on anything that isn't a letter, combining mark, digit, apostrophe or ZWJ/ZWNJ. */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split("[^\\p{L}\\p{M}\\p{N}'\\u200C\\u200D]+")) {
            String trimmed = t.replaceAll("^'+|'+$", "");
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static boolean has(String s, String[] phrases) {
        for (String p : phrases) {
            if (s.contains(p)) return true;
        }
        return false;
    }

    private static boolean hasWord(List<String> tokens, String[] words) {
        for (String w : words) {
            if (tokens.contains(w)) return true;
        }
        return false;
    }

    private static boolean hasPrefix(List<String> tokens, String[] prefixes) {
        for (String t : tokens) {
            for (String p : prefixes) {
                if (t.startsWith(p)) return true;
            }
        }
        return false;
    }

    private static boolean hasAny(List<String> tokens, Set<String> words) {
        for (String t : tokens) {
            if (words.contains(t)) return true;
        }
        return false;
    }

    private static Set<String> words(String... w) {
        return new HashSet<>(Arrays.asList(w));
    }
}
