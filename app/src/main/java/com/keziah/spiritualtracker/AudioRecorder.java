package com.keziah.spiritualtracker;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import java.io.IOException;

public class AudioRecorder {

    private MediaRecorder mediaRecorder;
    private String outputFilePath;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private Context context;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    public AudioRecorder(Context context) {
        this.context = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    // Start Recording
    public void startRecording(String filePath) {
        outputFilePath = filePath;

        // ─── REQUEST AUDIO FOCUS (prevent notifications from interrupting) ───
        requestAudioFocus();

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(outputFilePath);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioSamplingRate(44100);
        mediaRecorder.setAudioEncodingBitRate(128000);

        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            isPaused = false;
            Log.d("AudioRecorder", "✅ Recording started with AUDIO FOCUS");
        } catch (IOException e) {
            Log.e("AudioRecorder", "❌ prepare() failed: " + e.getMessage());
            e.printStackTrace();
            releaseAudioFocus();
        }
    }

    // ─── REQUEST AUDIO FOCUS (prevent notifications from interrupting) ───
    private void requestAudioFocus() {
        if (audioManager == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();

                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(focusChangeListener)
                        .build();

                int result = audioManager.requestAudioFocus(audioFocusRequest);
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.d("AudioRecorder", "✅ Audio Focus GRANTED");
                } else {
                    Log.w("AudioRecorder", "⚠️ Audio Focus DENIED - notifications may interrupt");
                }
            } else {
                // Android 7.x and below
                int result = audioManager.requestAudioFocus(
                        focusChangeListener,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN
                );
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.d("AudioRecorder", "✅ Audio Focus GRANTED (API <26)");
                } else {
                    Log.w("AudioRecorder", "⚠️ Audio Focus DENIED");
                }
            }
        } catch (Exception e) {
            Log.e("AudioRecorder", "❌ Failed to request audio focus: " + e.getMessage());
        }
    }

    // ─── RELEASE AUDIO FOCUS ───
    private void releaseAudioFocus() {
        if (audioManager == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                Log.d("AudioRecorder", "✅ Audio Focus released");
            } else {
                audioManager.abandonAudioFocus(focusChangeListener);
                Log.d("AudioRecorder", "✅ Audio Focus released (API <26)");
            }
        } catch (Exception e) {
            Log.e("AudioRecorder", "❌ Failed to release audio focus: " + e.getMessage());
        }
    }

    // ─── AUDIO FOCUS CHANGE LISTENER ───
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            focusChange -> {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        Log.w("AudioRecorder", "⚠️ Audio focus lost (transient) - notification/call");
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS:
                        Log.e("AudioRecorder", "❌ Audio focus permanently lost");
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                        Log.w("AudioRecorder", "⚠️ Audio focus lost (can duck)");
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        Log.d("AudioRecorder", "✅ Audio focus regained");
                        break;
                }
            };

    // ─── PAUSE RECORDING (Requires API 24+) ───
    public void pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isRecording && !isPaused && mediaRecorder != null) {
                try {
                    mediaRecorder.pause();
                    isRecording = false;
                    isPaused = true;
                    Log.d("AudioRecorder", "✅ Recording paused");
                } catch (RuntimeException e) {
                    Log.e("AudioRecorder", "❌ pause() failed: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                Log.w("AudioRecorder", "⚠️ Cannot pause - not actively recording");
            }
        } else {
            Log.e("AudioRecorder", "❌ Pause not supported (API < 24)");
        }
    }

    // ─── RESUME RECORDING (Requires API 24+) ───
    public void resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (isPaused && !isRecording && mediaRecorder != null) {
                try {
                    mediaRecorder.resume();
                    isRecording = true;
                    isPaused = false;
                    Log.d("AudioRecorder", "✅ Recording resumed");
                } catch (RuntimeException e) {
                    Log.e("AudioRecorder", "❌ resume() failed: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                Log.w("AudioRecorder", "⚠️ Cannot resume - not in paused state");
            }
        } else {
            Log.e("AudioRecorder", "❌ Resume not supported (API < 24)");
        }
    }

    // Stop Recording
    public void stopRecording() {
        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    // If still actively recording, just stop
                    mediaRecorder.stop();
                    Log.d("AudioRecorder", "✅ Recording stopped");
                } else if (isPaused) {
                    // If paused, resume first then stop (some devices need this)
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            mediaRecorder.resume();
                            Thread.sleep(100);
                        }
                    } catch (Exception e) {
                        Log.w("AudioRecorder", "⚠️ Could not resume before stopping: " + e.getMessage());
                    }
                    mediaRecorder.stop();
                    Log.d("AudioRecorder", "✅ Recording stopped (was paused)");
                }

                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                isPaused = false;
                Log.d("AudioRecorder", "✅ MediaRecorder released and finalized");
            } catch (RuntimeException stopException) {
                Log.e("AudioRecorder", "❌ stop() failed: " + stopException.getMessage());
                stopException.printStackTrace();
                // Force cleanup even if stop failed
                try {
                    if (mediaRecorder != null) {
                        mediaRecorder.release();
                        mediaRecorder = null;
                    }
                } catch (Exception e) {
                    Log.e("AudioRecorder", "❌ Failed to release MediaRecorder: " + e.getMessage());
                }
                isRecording = false;
                isPaused = false;
            }
        }

        // ─── RELEASE AUDIO FOCUS ───
        releaseAudioFocus();
    }

    public boolean isRecording() {
        return isRecording;
    }

    public boolean isPaused() {
        return isPaused;
    }
}