package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads a verse aloud with the phone's own text-to-speech voice (offline, free).
 * "Listen" reads reference, verse, reference; "Repeat after me" reads a phrase at a time
 * and leaves a pause to say it back. Speed and how many times to play are remembered.
 */
public final class VerseSpeaker {

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f};
    private static final int[]   TIMES  = {1, 3, 5};
    private static final String  PREFS  = "memorize_audio";
    private static final String  END    = "end:";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready, failed;
    private Runnable pending;
    private int nextId;
    /** Puts back the button labels of whichever controls started the current playback. */
    private Runnable onStopped;
    private View playingFrom;
    /** What's playing and where it has got to, so a speed or repeat change can pick up from there. */
    private String playRef, playText;
    private boolean playEcho;
    private volatile int atRound, atLine;

    public VerseSpeaker(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── Controls ────────────────────────────────────────────────────────────

    /** Wires a view_listen_controls row to read {@code text} under {@code reference}. */
    public void bind(View controls, String reference, String text) {
        MaterialButton listen = controls.findViewById(R.id.btnListen);
        MaterialButton echo   = controls.findViewById(R.id.btnEcho);
        TextView speed        = controls.findViewById(R.id.tvSpeed);
        TextView times        = controls.findViewById(R.id.tvTimes);

        Runnable reset = () -> {
            listen.setText("Listen");
            listen.setIconResource(R.drawable.ic_listen);
            listen.setIconTint(null); // keep the icon's own gradient
            echo.setText("Repeat after me");
            echo.setIconResource(R.drawable.ic_repeat_after);
            echo.setIconTint(null);
        };
        reset.run();
        showSettings(speed, times);

        listen.setOnClickListener(v -> toggle(listen, reference, text, false, reset));
        echo.setOnClickListener(v -> toggle(echo, reference, text, true, reset));
        speed.setOnClickListener(v -> {
            prefs().edit().putInt("speed", (speedIndex() + 1) % SPEEDS.length).apply();
            showSettings(speed, times);
            continueWithNewSettings();
        });
        times.setOnClickListener(v -> {
            // No restart: the reading carries on and the new count is used when this round ends.
            prefs().edit().putInt("times", (timesIndex() + 1) % TIMES.length).apply();
            showSettings(speed, times);
        });
    }

    private void toggle(MaterialButton button, String reference, String text, boolean echo, Runnable reset) {
        if (playingFrom == button) { stop(); return; }
        play(reference, text, echo, reset);
        playingFrom = button;
        button.setText("Stop");
        button.setIconResource(R.drawable.ic_stop_audio);
        button.setIconTint(null);
    }

    private void showSettings(TextView speed, TextView times) {
        float s = SPEEDS[speedIndex()];
        speed.setText("Speed " + (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "×");
        int t = TIMES[timesIndex()];
        times.setText(t == 1 ? "Play once" : "Play " + t + " times");
    }

    // ─── Playback ────────────────────────────────────────────────────────────

    private void play(String reference, String text, boolean echo, Runnable whenStopped) {
        stop();
        if (failed) { noVoice(); return; }
        onStopped = whenStopped;
        playRef = reference;
        playText = text;
        playEcho = echo;
        atRound = 0;
        atLine = -1;
        Runnable go = () -> queue(0, -1);
        ensureEngine();
        if (ready) go.run(); else pending = go;
    }

    /** Changed speed mid-way: carry on from the current line at the new speed. */
    private void continueWithNewSettings() {
        if (playingFrom == null || !ready || pending != null) return;
        int round = atRound, line = atLine;
        tts.stop();
        queue(round, line);
    }

    public void stop() {
        pending = null;
        if (tts != null && ready) tts.stop();
        stopped();
    }

    public void shutdown() {
        stop();
        if (tts != null) tts.shutdown();
        tts = null;
        ready = false;
    }

    private void stopped() {
        playingFrom = null;
        Runnable r = onStopped;
        onStopped = null;
        if (r != null) r.run();
    }

    private void ensureEngine() {
        if (tts != null) return;
        tts = new TextToSpeech(context, status -> main.post(() -> {
            if (tts == null) return; // shut down while starting
            if (status != TextToSpeech.SUCCESS) {
                failed = true;
                pending = null;
                stopped();
                noVoice();
                return;
            }
            int lang = tts.setLanguage(Locale.getDefault());
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH);
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    // Ids are "round:line:n"; remember the position for continueWithNewSettings().
                    String[] at = id.split(":");
                    if (at.length == 3) {
                        atRound = Integer.parseInt(at[0]);
                        atLine = Integer.parseInt(at[1]);
                    }
                }
                @Override public void onDone(String id) {
                    if (id.startsWith(END)) {
                        int round = Integer.parseInt(id.substring(END.length()));
                        main.post(() -> roundFinished(round));
                    }
                }
                @Override public void onError(String id) { main.post(VerseSpeaker.this::stopped); }
            });
            ready = true;
            Runnable p = pending;
            pending = null;
            if (p != null) p.run();
        }));
    }

    /** End of a round: play another if the (possibly just changed) count asks for one. */
    private void roundFinished(int round) {
        if (playingFrom == null || tts == null) return;
        if (round + 1 < TIMES[timesIndex()]) {
            pause(1500, round + 1, -1);
            queue(round + 1, -1);
        } else {
            stopped();
        }
    }

    /**
     * Queues one round (reference, the verses, reference) from line {@code fromLine}
     * (-1 = the opening reference). The next round is only queued when this one ends, so
     * changing how many times to play never interrupts the reading.
     */
    private void queue(int round, int fromLine) {
        float speed = SPEEDS[speedIndex()];
        tts.setSpeechRate(speed);
        String ref = referenceForSpeech(playRef, playText);
        List<String> lines = linesForSpeech(playText);

        if (fromLine < 0) {
            say(ref, round, -1);
            pause(500, round, -1);
        }
        for (int i = Math.max(fromLine, 0); i < lines.size(); i++) {
            if (playEcho) {
                for (String phrase : phrases(lines.get(i))) {
                    say(phrase, round, i);
                    // Long enough to say it back: about half a second a word, longer when slowed down.
                    pause((long) (wordCount(phrase) * 450 / speed) + 900, round, i);
                }
            } else {
                say(lines.get(i), round, i);
                pause(250, round, i);
            }
        }
        pause(300, round, lines.size());
        say(ref, round, lines.size());
        tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, END + round);
    }

    private void say(String s, int round, int line) {
        tts.speak(s, TextToSpeech.QUEUE_ADD, null, round + ":" + line + ":" + (nextId++));
    }

    private void pause(long ms, int round, int line) {
        tts.playSilentUtterance(ms, TextToSpeech.QUEUE_ADD, round + ":" + line + ":" + (nextId++));
    }

    private void noVoice() {
        Toast.makeText(context, "This phone has no text-to-speech voice. Add one in Settings → Accessibility → Text-to-speech.",
                Toast.LENGTH_LONG).show();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int speedIndex() {
        int i = prefs().getInt("speed", 2);
        return i >= 0 && i < SPEEDS.length ? i : 2;
    }

    private int timesIndex() {
        int i = prefs().getInt("times", 0);
        return i >= 0 && i < TIMES.length ? i : 0;
    }

    // ─── Text shaping ────────────────────────────────────────────────────────

    private static final java.util.regex.Pattern VERSE_NUMBER =
            java.util.regex.Pattern.compile("^\\s*[\\[(]?(\\d+)[\\])]?[.:]?\\s+");

    /**
     * What to announce before and after the verse. When the text carries verse numbers, the range
     * comes from them, so a part says what it actually covers: "Matthew 23 (Part 4 of 8)" holding
     * verses 17–21 → "Matthew 23, verses 17 to 21". Otherwise the typed reference is read out.
     */
    static String referenceForSpeech(String reference, String text) {
        if (reference == null) return "";
        String plain = reference.replaceAll("\\s*\\(Part \\d+ of \\d+\\)", "").trim();
        List<String> numbers = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\n")) {
                java.util.regex.Matcher m = VERSE_NUMBER.matcher(line);
                if (m.find()) numbers.add(m.group(1));
            }
        }
        if (!numbers.isEmpty()) {
            String chapter = plain.replaceFirst(":.*$", "").trim(); // "Exodus 15:1-21" → "Exodus 15"
            String first = numbers.get(0), last = numbers.get(numbers.size() - 1);
            return chapter + (first.equals(last) ? ", verse " + first : ", verses " + first + " to " + last);
        }
        return plain
                .replaceAll("(\\d+):(\\d+)\\s*[-–—]\\s*(\\d+)", "$1, verses $2 to $3")
                .replaceAll("(\\d+):(\\d+)", "$1, verse $2");
    }

    /** One entry per line, without the verse numbers (the range is announced at the start). */
    static List<String> linesForSpeech(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String clean = VERSE_NUMBER.matcher(line).replaceFirst("").trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    /** Splits at commas and full stops; long stretches without either get cut every six words. */
    static List<String> phrases(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.split("(?<=[,;:.!?])\\s+")) {
            List<String> words = new ArrayList<>(Arrays.asList(part.trim().split("\\s+")));
            words.removeIf(String::isEmpty);
            if (words.size() <= 9) {
                if (!words.isEmpty()) out.add(String.join(" ", words));
                continue;
            }
            for (int i = 0; i < words.size(); i += 6) {
                // Don't leave a one- or two-word scrap at the end.
                int end = words.size() - (i + 6) < 3 ? words.size() : i + 6;
                out.add(String.join(" ", words.subList(i, end)));
                if (end == words.size()) break;
            }
        }
        return out;
    }

    private static int wordCount(String s) {
        return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length;
    }
}
