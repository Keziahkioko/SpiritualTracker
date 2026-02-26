package com.keziah.spiritualtracker;

import android.media.MediaRecorder;
import android.util.Log;
import java.io.IOException;

public class AudioRecorder {

    private MediaRecorder mediaRecorder;
    private String outputFilePath;
    private boolean isRecording = false;

    // Start Recording
    public void startRecording(String filePath) {
        outputFilePath = filePath;

        // Setup the Phone's Recorder
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); // Standard mp4 format
        mediaRecorder.setOutputFile(outputFilePath);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            Log.d("AudioRecorder", "Recording started");
        } catch (IOException e) {
            Log.e("AudioRecorder", "prepare() failed");
        }
    }

    // Stop Recording
    public void stopRecording() {
        if (isRecording && mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
            } catch (RuntimeException stopException) {
                // Use a generic catch to handle if stop is called too early
            }
            mediaRecorder = null;
            isRecording = false;
            Log.d("AudioRecorder", "Recording stopped");
        }
    }

    public boolean isRecording() {
        return isRecording;
    }
}