package com.sliit.thirdeye;

import static android.content.ContentValues.TAG;
import static com.android.volley.toolbox.Volley.newRequestQueue;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;

import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The voice assistant. The user touches the bottom half of the screen and speaks; the
 * command is matched on the phone first and anything unrecognised goes to the chat server.
 */
public class Home extends AppCompatActivity {

    // Speech recognition request codes, one per question the assistant can ask.
    private static final int RC_COMMAND = 100;
    private static final int RC_PLACE = 101;
    private static final int RC_CALL_NAME = 102;
    private static final int RC_MSG_RECIPIENT = 103;
    private static final int RC_MSG_BODY = 104;
    private static final int RC_MSG_CONFIRM = 105;

    // Runtime permission request codes.
    private static final int REQUEST_LOCATION = 1;
    private static final int REQUEST_PERMS_FOR_ACTION = 123;

    private static final String CHAT_SERVER_URL = "https://thirdeye-37h3nbeslq-el.a.run.app";
    private static final int MAX_MESSAGES_READ = 5;

    private enum Channel { SMS, WHATSAPP }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;
    private Runnable pendingSpeechThen;
    private final Map<String, Runnable> afterSpeech = new ConcurrentHashMap<>();
    private int utteranceSeq;

    private FlashlightUtils flashlightUtils;
    private LocationManager locationManager;
    private String latitude, longitude;
    private volatile String weather;

    // A command waiting for the user to grant permissions.
    private Runnable pendingPermissionAction;
    private String pendingPermissionReason;

    // Message being composed.
    private Channel msgChannel;
    private SmsHelper.Contact msgContact;
    private String msgBody;

    // Language for listening and speaking (English, Sinhala, Tamil), remembered across launches.
    private VoiceLanguage language = VoiceLanguage.ENGLISH;
    // Offline listening with Whisper; the recogniser is created and used only on voiceExecutor.
    private final ExecutorService voiceExecutor = Executors.newSingleThreadExecutor();
    private WhisperRecognizer whisper;
    private boolean offlineListeningExplained;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        flashlightUtils = new FlashlightUtils(getApplicationContext());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        language = VoiceLanguage.fromCode(getSharedPreferences(VoiceLanguage.PREFS, MODE_PRIVATE)
                .getString(VoiceLanguage.PREF_KEY, null));

        initSpeech();

        Button speakButton = findViewById(R.id.button2);
        speakButton.setOnClickListener(v -> speakThen("Speak now.", () -> listen(RC_COMMAND)));

        // Shortcut tiles run the same actions as the matching voice commands.
        findViewById(R.id.tileDescribe).setOnClickListener(v ->
                openVision(VisionClient.Mode.DESCRIBE, null));
        findViewById(R.id.tileMoney).setOnClickListener(v ->
                openVision(VisionClient.Mode.MONEY, null));
        findViewById(R.id.tileText).setOnClickListener(v ->
                openVision(VisionClient.Mode.TEXT, null));
        findViewById(R.id.tileDisplay).setOnClickListener(v ->
                openVision(VisionClient.Mode.DISPLAY, null));
        findViewById(R.id.tileAround).setOnClickListener(v -> {
            speak("Opening live object detection.");
            startActivity(AroundMe.intent(this));
        });
        findViewById(R.id.tileMessages).setOnClickListener(v -> readMessages());

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
        } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            askToEnableGps();
        } else {
            readLocation();
            new WeatherLookup().execute();
        }

        speak("Hello! I'm Third Eye, your assistant. Touch the big button at the bottom of the screen and tell me what you need. "
                + "For example, say: what is this, what note is this, or read my messages.");
    }

    // ---------------------------------------------------------------- speech out

    private void initSpeech() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) return;
            String missingVoice = applyVoice(language);
            if (missingVoice != null) pendingSpeech = missingVoice + " " + (pendingSpeech == null ? "" : pendingSpeech);
            tts.setSpeechRate(0.95f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    runAfter(id);
                }

                @Override
                public void onError(String id) {
                    runAfter(id);
                }
            });
            ttsReady = true;
            if (pendingSpeech != null) {
                speakThen(pendingSpeech, pendingSpeechThen);
                pendingSpeech = null;
                pendingSpeechThen = null;
            }
        });
    }

    /**
     * Sets the speech output voice for {@code lang}. When the phone has no voice for it,
     * speaks English instead and returns a sentence explaining that; otherwise null.
     */
    @Nullable
    private String applyVoice(VoiceLanguage lang) {
        int r = tts.setLanguage(lang.ttsLocale);
        if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return null;
        int en = tts.setLanguage(VoiceLanguage.ENGLISH.ttsLocale);
        if (en == TextToSpeech.LANG_MISSING_DATA || en == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.UK);
        }
        return lang == VoiceLanguage.ENGLISH ? null : getString(R.string.voice_missing, lang.englishName);
    }

    /** "Speak Sinhala" etc.: remembers the language and switches listening and speaking to it. */
    private void switchLanguage(VoiceLanguage lang) {
        language = lang;
        getSharedPreferences(VoiceLanguage.PREFS, MODE_PRIVATE).edit()
                .putString(VoiceLanguage.PREF_KEY, lang.code).apply();
        String confirm = getString(lang == VoiceLanguage.SINHALA ? R.string.language_now_sinhala
                : lang == VoiceLanguage.TAMIL ? R.string.language_now_tamil : R.string.language_now_english);
        String missingVoice = ttsReady ? applyVoice(lang) : null;
        speak(missingVoice == null ? confirm : getString(R.string.language_set_listening, lang.englishName)
                + " " + missingVoice);
    }

    private void runAfter(String utteranceId) {
        Runnable then = afterSpeech.remove(utteranceId);
        if (then != null) handler.post(then);
    }

    private void speak(String text) {
        speakThen(text, null);
    }

    /** Speaks, then runs {@code then} once speaking has finished (e.g. to start listening). */
    private void speakThen(String text, @Nullable Runnable then) {
        if (!ttsReady) {
            pendingSpeech = text;
            pendingSpeechThen = then;
            return;
        }
        String id = "u" + (++utteranceSeq);
        if (then != null) afterSpeech.put(id, then);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    // ----------------------------------------------------------------- speech in

    /**
     * Android's recogniser when it can work (best for Sinhala and Tamil); on-phone Whisper
     * (English) when there is no recogniser or no internet. Both end in {@link #onHeard}.
     */
    private void listen(int requestCode) {
        OfflineSpeech.Recogniser which = OfflineSpeech.choose(
                SpeechRecognizer.isRecognitionAvailable(this), isOnline(), WhisperRecognizer.isReady(this));
        switch (which) {
            case ANDROID:
                listenWithAndroid(requestCode);
                break;
            case WHISPER:
                listenWithWhisper(requestCode);
                break;
            default:
                speak(getString(R.string.no_recogniser));
        }
    }

    private void listenWithAndroid(int requestCode) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.recognizerTag);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.recognizerTag);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Start speaking");
        if (!isOnline()) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        try {
            startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            if (WhisperRecognizer.isReady(this)) {
                listenWithWhisper(requestCode);
            } else {
                speak(getString(R.string.no_recogniser));
            }
        }
    }

    /** Records and transcribes on the phone, off the UI thread, then continues in onHeard. */
    private void listenWithWhisper(int requestCode) {
        withPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, "listen to you without the internet", () -> {
            Runnable start = () -> voiceExecutor.execute(() -> {
                if (whisper == null) whisper = WhisperRecognizer.create(getApplicationContext());
                if (whisper == null) {
                    handler.post(() -> speak(getString(R.string.no_recogniser)));
                    return;
                }
                beep();
                String said = whisper.listenOnce(getApplicationContext());
                handler.post(() -> {
                    if (!isDestroyed()) onHeard(requestCode, said);
                });
            });
            if (offlineListeningExplained) {
                start.run();
            } else {
                offlineListeningExplained = true;
                String explain = getString(R.string.offline_listening);
                if (language != VoiceLanguage.ENGLISH) {
                    explain += " " + getString(R.string.offline_listening_english_only, language.englishName);
                }
                speakThen(explain, start);
            }
        });
    }

    /** Short tone so the user knows the microphone is now open. */
    private static void beep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            Thread.sleep(200);
            tone.release();
        } catch (RuntimeException | InterruptedException ignored) {
            // No tone is fine; listening still works.
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (SecurityException e) {
            return true;    // can't tell; let Android's recogniser try
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode < RC_COMMAND || requestCode > RC_MSG_CONFIRM) return;

        ArrayList<String> results = (resultCode == RESULT_OK && data != null)
                ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
        onHeard(requestCode, (results == null || results.isEmpty()) ? null : results.get(0));
    }

    /** What the user said, from either recogniser; null or empty when nothing was heard. */
    private void onHeard(int requestCode, @Nullable String said) {
        if (said == null || said.trim().isEmpty()) {
            clearMessageDraft();
            speak("I didn't catch that. Touch the bottom of the screen to try again.");
            return;
        }
        Log.d(TAG, "heard (" + requestCode + "): " + said);

        switch (requestCode) {
            case RC_COMMAND:
                route(said);
                break;
            case RC_PLACE:
                openNavigation(said);
                break;
            case RC_CALL_NAME:
                callContact(said);
                break;
            case RC_MSG_RECIPIENT:
                onMessageRecipient(said);
                break;
            case RC_MSG_BODY:
                onMessageBody(said);
                break;
            case RC_MSG_CONFIRM:
                onMessageConfirm(said);
                break;
        }
    }

    // -------------------------------------------------------------------- router

    /**
     * Matches a spoken command on the phone. Order matters: more specific phrases
     * ("read my messages", "what note is this") are checked before general ones
     * ("read", "what is this").
     */
    private void route(String said) {
        String s = said.toLowerCase(Locale.ROOT).trim();

        // Any language: "speak Tamil", "torch on", then the Sinhala/Tamil command tables.
        VoiceLanguage switchTo = CommandKeywords.languageSwitch(s);
        if (switchTo != null) {
            switchLanguage(switchTo);
            return;
        }
        CommandKeywords.Torch torch = CommandKeywords.torch(s);
        if (torch != null) {
            switchTorch(torch);
            return;
        }
        CommandKeywords.Command command = CommandKeywords.match(s);
        if (command != null) {
            run(command);
            return;
        }

        if (s.contains("delete") && (s.contains("message") || s.contains("sms"))) {
            speak("Sorry, Android only lets your main messaging app delete messages, so I can't do that.");
        } else if (s.contains("whatsapp")) {
            startMessage(Channel.WHATSAPP, nameAfterTo(s));
        } else if ((s.contains("send") && (s.contains("message") || s.contains("sms") || s.contains("text")))
                || s.startsWith("message ") || s.startsWith("text ")) {
            // Before "read messages": "send a new message to Amma" also contains "new".
            startMessage(Channel.SMS, nameAfterTo(s));
        } else if (s.contains("message") && (s.contains("read") || s.contains("check") || s.contains("new")
                || s.contains("any") || s.contains("my messages"))) {
            readMessages();
        } else if (s.contains("read") && OcrLanguage.preferenceFromSpeech(s) != null) {
            // "read in Sinhala" / "read in Tamil": offline reader in that language (remembered).
            openTextReaderIn(OcrLanguage.preferenceFromSpeech(s), said);
        } else if (containsAny(s, "note", "money", "cash", "rupee", "currency", "coin", "how much")) {
            openVision(VisionClient.Mode.MONEY, said);
        } else if (containsAny(s, "display", "screen", "meter", "atm", "board", "monitor")) {
            openVision(VisionClient.Mode.DISPLAY, said);
        } else if (containsAny(s, "read", "what does this say", "what does it say", "what's written", "what is written")) {
            openVision(VisionClient.Mode.TEXT, said);
        } else if (containsAny(s, "obstacle", "walking", "around me", "object detection", "live detection")) {
            speak("Opening live object detection.");
            startActivity(AroundMe.intent(this));
        } else if (containsAny(s, "what is this", "what's this", "what is that", "what's that", "in front",
                "describe", "identify", "what do you see", "what can you see", "look at", "what am i holding",
                "colour", "color")) {
            openVision(VisionClient.Mode.DESCRIBE, said);
        } else if (containsAny(s, "weather", "temperature", "rain")) {
            speakWeather();
        } else if (s.startsWith("call ") || s.equals("call") || s.startsWith("phone ")) {
            String name = s.replaceFirst("^(call|phone)\\s*", "").trim();
            if (name.isEmpty()) {
                askWhoToCall();
            } else {
                callContact(name);
            }
        } else if (containsAny(s, "take me to", "navigate to", "directions to", "go to", "how do i get to")) {
            String place = s.replaceFirst("^.*?(take me to|navigate to|directions to|go to|how do i get to)\\s*", "").trim();
            if (place.isEmpty()) {
                speakThen("Where do you want to go?", () -> listen(RC_PLACE));
            } else {
                openNavigation(place);
            }
        } else if (containsAny(s, "help", "what can you do")) {
            speakHelp();
        } else {
            askChatServer(said);
        }
    }

    /** A command recognised from the Sinhala or Tamil tables. */
    private void run(CommandKeywords.Command command) {
        switch (command) {
            case WHAT_IS_THIS: openVision(VisionClient.Mode.DESCRIBE, null); break;
            case MONEY: openVision(VisionClient.Mode.MONEY, null); break;
            case READ_TEXT: openVision(VisionClient.Mode.TEXT, null); break;
            case READ_DISPLAY: openVision(VisionClient.Mode.DISPLAY, null); break;
            case AROUND_ME:
                speak("Opening live object detection.");
                // Same as the English command: the depth screen when the phone supports it.
                startActivity(AroundMe.intent(this));
                break;
            case MESSAGES: readMessages(); break;
            case SEND_MESSAGE: startMessage(Channel.SMS, ""); break;
            case CALL: askWhoToCall(); break;
            case DIRECTIONS: speakThen("Where do you want to go?", () -> listen(RC_PLACE)); break;
            case WEATHER: speakWeather(); break;
            case HELP: speakHelp(); break;
        }
    }

    private void switchTorch(CommandKeywords.Torch torch) {
        if (torch == CommandKeywords.Torch.OFF) {
            flashlightUtils.turnOffFlashlight();
            speak("Torch off.");
        } else if (torch == CommandKeywords.Torch.ON) {
            flashlightUtils.turnOnFlashlight();
            speak("Torch on.");
        } else {
            speak(getString(R.string.torch_ask));
        }
    }

    private void speakHelp() {
        speak("You can say: what is this, what note is this, read this, read the display, "
                + "what's around me, read my messages, send a message to someone, send a WhatsApp, "
                + "call someone, take me to a place, what's the weather, or turn the torch on. "
                + "To change language, say speak Sinhala, speak Tamil, or speak English.");
    }

    /**
     * Opens the cloud vision screen when this build has the vision service configured.
     * Otherwise goes straight to the matching on-phone reader, so the user never lands on
     * a "not set up" dead end. Home stays underneath, so its speech guides the user in.
     */
    private void openVision(VisionClient.Mode mode, String question) {
        // "What is this?" always uses the Identify screen: it describes on the phone
        // when the cloud service isn't set up or there's no internet.
        if (VisionClient.isConfigured() || mode == VisionClient.Mode.DESCRIBE) {
            IdentifyActivity.start(this, mode, question);
            return;
        }
        switch (mode) {
            case MONEY:
                speak("Opening the note reader. Hold one note at a time in front of the camera.");
                startActivity(new Intent(this, CurrencyDetection.class));
                break;
            case TEXT:
            case DISPLAY:
                speak("Opening the text reader. Hold the page or screen in front of the camera, "
                        + "then press the big yellow button at the bottom to take a photo.");
                startActivity(new Intent(this, TextRecognitionCameraActivity2.class));
                break;
            default:
                speak("Opening live object detection. Point the camera around you.");
                startActivity(AroundMe.intent(this));
        }
    }

    /** Sinhala/Tamil go to the offline Tesseract reader; "read in English" switches back. */
    private void openTextReaderIn(OcrLanguage.Preference language, String said) {
        if (language == OcrLanguage.Preference.AUTO) {
            TextRecognitionCameraActivity2.remember(this, language);
            openVision(VisionClient.Mode.TEXT, said);
            return;
        }
        speak(getString(R.string.ocr_open_language, getString(language == OcrLanguage.Preference.TAMIL
                ? R.string.ocr_lang_tamil : R.string.ocr_lang_sinhala)));
        TextRecognitionCameraActivity2.start(this, language);
    }

    private static boolean containsAny(String s, String... phrases) {
        for (String p : phrases) {
            if (s.contains(p)) return true;
        }
        return false;
    }

    /** "send a message to amma" -> "amma"; "" when no name was given. */
    private static String nameAfterTo(String s) {
        int i = s.lastIndexOf(" to ");
        return i < 0 ? "" : s.substring(i + 4).trim();
    }

    // ------------------------------------------------------------------ messages

    private void readMessages() {
        withPermissions(new String[]{Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS},
                "read your messages", () -> {
                    List<SmsHelper.Message> unread;
                    try {
                        unread = SmsHelper.unreadMessages(this, MAX_MESSAGES_READ);
                    } catch (SecurityException e) {
                        speak("I'm not allowed to read your messages.");
                        return;
                    }
                    if (unread.isEmpty()) {
                        speak("You have no new messages.");
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(unread.size() == 1 ? "You have one new message. "
                            : "Here are your " + unread.size() + " newest unread messages. ");
                    for (int i = 0; i < unread.size(); i++) {
                        SmsHelper.Message m = unread.get(i);
                        if (unread.size() > 1) sb.append("Message ").append(i + 1).append(". ");
                        sb.append("From ").append(m.from).append(". ").append(m.body).append(". ");
                    }
                    speak(sb.toString());
                });
    }

    private void startMessage(Channel channel, String name) {
        String[] perms = channel == Channel.SMS
                ? new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS}
                : new String[]{Manifest.permission.READ_CONTACTS};
        withPermissions(perms, channel == Channel.SMS ? "send text messages" : "find your contacts", () -> {
            clearMessageDraft();
            msgChannel = channel;
            if (name.isEmpty()) {
                speakThen("Who do you want to message?", () -> listen(RC_MSG_RECIPIENT));
            } else {
                onMessageRecipient(name);
            }
        });
    }

    private void onMessageRecipient(String name) {
        if (msgChannel == null) return;
        SmsHelper.Contact contact = SmsHelper.findContact(this, name);
        if (contact == null) {
            clearMessageDraft();
            speak("I couldn't find " + name + " in your contacts.");
            return;
        }
        msgContact = contact;
        speakThen("What should the message to " + contact.name + " say?", () -> listen(RC_MSG_BODY));
    }

    private void onMessageBody(String body) {
        if (msgContact == null) return;
        msgBody = body;
        speakThen("Your message to " + msgContact.name + " says: " + body + ". Should I send it? Say yes or no.",
                () -> listen(RC_MSG_CONFIRM));
    }

    private void onMessageConfirm(String answer) {
        if (msgContact == null || msgBody == null) return;
        // Whole words, any language: "now" or "I know" is not a no.
        if (!CommandKeywords.isYes(answer)) {
            clearMessageDraft();
            speak("Okay, I didn't send it.");
            return;
        }

        SmsHelper.Contact to = msgContact;
        String body = msgBody;
        Channel channel = msgChannel;
        clearMessageDraft();

        if (channel == Channel.WHATSAPP) {
            if (SmsHelper.openWhatsApp(this, to.number, body)) {
                speak("WhatsApp is open with your message to " + to.name
                        + ". Press the green send button at the bottom right of the screen.");
            } else {
                speak("WhatsApp isn't installed on this phone.");
            }
            return;
        }
        try {
            SmsHelper.sendSms(this, to.number, body);
            speak("Message sent to " + to.name + ".");
        } catch (RuntimeException e) {
            Log.e(TAG, "SMS send failed", e);
            speak("Sorry, the message could not be sent.");
        }
    }

    private void clearMessageDraft() {
        msgChannel = null;
        msgContact = null;
        msgBody = null;
    }

    // --------------------------------------------------------------------- calls

    private void askWhoToCall() {
        withPermissions(new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS},
                "make calls", () -> speakThen("Who do you want to call?", () -> listen(RC_CALL_NAME)));
    }

    private void callContact(String name) {
        withPermissions(new String[]{Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS},
                "make calls", () -> {
                    SmsHelper.Contact contact = SmsHelper.findContact(this, name);
                    if (contact == null) {
                        speak("Sorry, I could not find " + name + " in your contacts.");
                        return;
                    }
                    speak("Calling " + contact.name + ".");
                    Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + contact.number));
                    try {
                        startActivity(intent);
                    } catch (SecurityException | ActivityNotFoundException e) {
                        speak("Sorry, I couldn't start the call.");
                    }
                });
    }

    // ---------------------------------------------------------------- navigation

    private void openNavigation(String place) {
        speak("Starting walking directions to " + place + ".");
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + Uri.encode(place) + "&mode=w"));
        intent.setPackage("com.google.android.apps.maps");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            speak("Google Maps isn't installed on this phone.");
        }
    }

    // --------------------------------------------------------------- permissions

    /** Runs {@code action} now if all permissions are granted, otherwise after the user grants them. */
    private void withPermissions(String[] permissions, String reason, Runnable action) {
        List<String> missing = new ArrayList<>();
        for (String p : permissions) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            action.run();
            return;
        }
        pendingPermissionAction = action;
        pendingPermissionReason = reason;
        speak("Please allow access so I can " + reason + ".");
        ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_PERMS_FOR_ACTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    readLocation();
                    new WeatherLookup().execute();
                } else {
                    askToEnableGps();
                }
            }
            return;
        }

        if (requestCode == REQUEST_PERMS_FOR_ACTION) {
            Runnable action = pendingPermissionAction;
            String reason = pendingPermissionReason;
            pendingPermissionAction = null;
            pendingPermissionReason = null;

            boolean granted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (granted && action != null) {
                action.run();
            } else if (reason != null) {
                speak("Without that permission I can't " + reason + ".");
            }
        }
    }

    // ------------------------------------------------------------ chat fallback

    /** Anything the phone doesn't recognise goes to the existing chat server. */
    private void askChatServer(String said) {
        RequestQueue queue = newRequestQueue(this);
        JSONObject body = new JSONObject();
        try {
            body.put("msg", said);
        } catch (JSONException e) {
            return;
        }
        JsonObjectRequest request = new JsonObjectRequest(Request.Method.POST, CHAT_SERVER_URL, body,
                response -> handleChatReply(response.optString("msg", "")),
                error -> speak("Sorry, I couldn't reach the assistant. Please check your internet connection."));
        queue.add(request);
    }

    /** The chat server answers with fixed phrases for some intents; map them to actions. */
    private void handleChatReply(String reply) {
        switch (reply) {
            case "sure. Ishihara test Opening":
            case "Here is ishihara test":
                speak("Sorry, colour blindness testing is not available in this app.");
                break;
            case "sure. Where do u want to go?":
                speakThen("Where do you want to go?", () -> listen(RC_PLACE));
                break;
            case "Who do you want to call":
                askWhoToCall();
                break;
            case "sure. please show the page to mobile camera that you want to read":
                openVision(VisionClient.Mode.TEXT, null);
                break;
            case "sure. currency reader openning. please show the money to camera one by one ":
                openVision(VisionClient.Mode.MONEY, null);
                break;
            case "Opening Third eye object detection":
                speak("Opening live object detection.");
                startActivity(AroundMe.intent(this));
                break;
            case "Opening weather":
                speakWeather();
                break;
            case "flasher is turning on":
                flashlightUtils.turnOnFlashlight();
                speak("Torch on.");
                break;
            case "flasher is turning off":
                flashlightUtils.turnOffFlashlight();
                speak("Torch off.");
                break;
            default:
                speak(reply.isEmpty() ? "Sorry, I didn't understand. Say help to hear what I can do." : reply);
        }
    }

    // ------------------------------------------------------------------- weather

    private void speakWeather() {
        String w = weather;
        speak(w != null ? w : "I'm still checking the weather. Please ask again in a moment.");
    }

    private void readLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER}) {
            Location loc = locationManager.getLastKnownLocation(provider);
            if (loc != null) {
                latitude = String.valueOf(loc.getLatitude());
                longitude = String.valueOf(loc.getLongitude());
                return;
            }
        }
    }

    private void askToEnableGps() {
        new AlertDialog.Builder(this)
                .setMessage("Enable GPS")
                .setCancelable(false)
                .setPositiveButton("YES", (d, i) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                .setNegativeButton("NO", (d, i) -> d.cancel())
                .show();
    }

    /** Scrapes today's weather from weather.com for the last known location. */
    private class WeatherLookup extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            weather = "Sorry, I could not get the weather right now.";
            if (latitude == null || longitude == null) {
                return null;
            }
            try {
                org.jsoup.nodes.Document document =
                        Jsoup.connect("https://weather.com/weather/today/l/" + latitude + "," + longitude).get();
                String temp = document.getElementsByClass("CurrentConditions--tempValue--MHmYY").text();
                String phrase = document.getElementsByClass("CurrentConditions--phraseValue--mZC_p").text();

                // Class names on weather.com change; an empty match must not crash the app.
                String digits = temp.replace("°", "").trim();
                if (digits.isEmpty()) {
                    return null;
                }
                int fahrenheit = Integer.parseInt(digits);
                int celsius = ((fahrenheit - 32) * 5) / 9;
                weather = "Today the weather is " + phrase + " and the temperature is " + celsius + " degrees Celsius.";
            } catch (IOException | NumberFormatException e) {
                Log.e(TAG, "Weather lookup failed", e);
            }
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        // Free the Whisper model on its own thread, after any transcription in progress.
        voiceExecutor.execute(() -> {
            if (whisper != null) whisper.close();
            whisper = null;
        });
        voiceExecutor.shutdown();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
