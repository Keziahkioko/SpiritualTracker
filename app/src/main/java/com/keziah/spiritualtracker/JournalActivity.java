package com.keziah.spiritualtracker;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.keziah.spiritualtracker.databinding.ActivityJournalBinding;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class JournalActivity extends AppCompatActivity {

    private ActivityJournalBinding binding;
    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private String existingDateStr = null;

    private AudioRecorder audioRecorder;
    private String localAudioPath = null;
    private boolean isRecording = false;

    // --- EDIT MODE VARIABLES ---
    private String existingDocId = null;
    private String existingAudioUrl = null;

    private static final String CLOUD_NAME = "YOUR_CLOUDINARY_CLOUD_NAME";
    private static final String UPLOAD_PRESET = "YOUR_UNSIGNED_UPLOAD_PRESET";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityJournalBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        audioRecorder = new AudioRecorder();

        initCloudinary();

        // --- NEW: CHECK IF WE ARE IN EDIT MODE ---
        if (getIntent().hasExtra("docId")) {
            existingDocId = getIntent().getStringExtra("docId");
            existingAudioUrl = getIntent().getStringExtra("audioUrl");
            existingDateStr = getIntent().getStringExtra("originalDate");

            // Fill the UI with existing data
            binding.etTitle.setText(getIntent().getStringExtra("title"));
            binding.etContent.setText(getIntent().getStringExtra("content"));

            // Change UI to reflect Editing
            binding.btnSaveJournal.setText("UPDATE ENTRY");
            if (existingAudioUrl != null) {
                // Hint to the user that audio already exists
                Toast.makeText(this, "Editing entry with existing audio", Toast.LENGTH_SHORT).show();
            }
        }

        binding.btnRecord.setOnClickListener(v -> {
            if (checkPermissions()) {
                toggleRecording();
            } else {
                requestPermissions();
            }
        });

        binding.btnSaveJournal.setOnClickListener(v -> {
            saveJournalEntry();
        });
    }

    private void toggleRecording() {
        if (!isRecording) {
            localAudioPath = getExternalCacheDir().getAbsolutePath() + "/temp_audio_" + System.currentTimeMillis() + ".mp3";
            audioRecorder.startRecording(localAudioPath);
            isRecording = true;
            binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause);
            binding.btnRecord.setBackgroundTintList(getResources().getColorStateList(android.R.color.holo_red_dark));
            Toast.makeText(this, "Recording Started...", Toast.LENGTH_SHORT).show();
        } else {
            audioRecorder.stopRecording();
            isRecording = false;
            binding.btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
            binding.btnRecord.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#673AB7")));
            Toast.makeText(this, "Audio Captured!", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveJournalEntry() {
        String title = binding.etTitle.getText().toString().trim();
        String content = binding.etContent.getText().toString().trim();

        if (TextUtils.isEmpty(title)) {
            Toast.makeText(this, "Please write a title!", Toast.LENGTH_SHORT).show();
            return;
        }

        binding.btnSaveJournal.setEnabled(false);
        binding.btnSaveJournal.setText("PROCESSING...");

        // SCENARIO 1: New Audio recorded -> Upload first
        if (localAudioPath != null) {
            uploadAudioToCloudinary(title, content);
        }
        // SCENARIO 2: No NEW audio, but KEEPING OLD audio (or no audio at all)
        else {
            saveToFirestore(title, content, existingAudioUrl);
        }
    }

    private void uploadAudioToCloudinary(String title, String content) {
        binding.btnSaveJournal.setText("UPLOADING AUDIO...");
        MediaManager.get().upload(localAudioPath)
                .unsigned(UPLOAD_PRESET)
                .option("resource_type", "auto")
                .callback(new UploadCallback() {
                    @Override public void onStart(String requestId) { }
                    @Override public void onProgress(String requestId, long bytes, long totalBytes) { }

                    @Override
                    public void onSuccess(String requestId, Map resultData) {
                        String audioUrl = (String) resultData.get("secure_url");
                        saveToFirestore(title, content, audioUrl);
                    }

                    @Override
                    public void onError(String requestId, ErrorInfo error) {
                        runOnUiThread(() -> {
                            Toast.makeText(JournalActivity.this, "Upload Failed", Toast.LENGTH_LONG).show();
                            binding.btnSaveJournal.setEnabled(true);
                            binding.btnSaveJournal.setText("SAVE ENTRY");
                        });
                    }
                    @Override public void onReschedule(String requestId, ErrorInfo error) { }
                }).dispatch();
    }

    private void saveToFirestore(String title, String content, String audioUrl) {
        binding.btnSaveJournal.setText("SAVING...");
        String userId = auth.getCurrentUser().getUid();

        if (existingDocId != null) {
            // --- EDIT MODE: UPDATE ONLY SPECIFIC FIELDS ---
            // We use a Map to tell Firestore exactly which fields to change
            Map<String, Object> updates = new HashMap<>();
            updates.put("title", title);
            updates.put("content", content);

            // Only update audioUrl if it's not null (keeping old audio if no new one)
            if (audioUrl != null) {
                updates.put("audioUrl", audioUrl);
            }

            db.collection("journal_entries").document(existingDocId)
                    .update(updates) // .update() keeps the original 'date' field safe!
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(this, "Entry Updated!", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .addOnFailureListener(e -> {
                        binding.btnSaveJournal.setEnabled(true);
                        binding.btnSaveJournal.setText("UPDATE ENTRY");
                        Toast.makeText(this, "Update Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });

        } else {
            // --- CREATE MODE: ADD NEW ENTRY (WITH NEW DATE) ---
            JournalEntry entry = new JournalEntry(title, content, new Timestamp(new Date()), userId);
            if (audioUrl != null) {
                entry.setAudioUrl(audioUrl);
            }

            db.collection("journal_entries")
                    .add(entry)
                    .addOnSuccessListener(documentReference -> {
                        Toast.makeText(this, "Entry Saved!", Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .addOnFailureListener(e -> {
                        binding.btnSaveJournal.setEnabled(true);
                        binding.btnSaveJournal.setText("SAVE ENTRY");
                        Toast.makeText(this, "Save Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
        }
    }

    private void initCloudinary() {
        try {
            Map config = new HashMap();
            config.put("cloud_name", CLOUD_NAME);
            MediaManager.init(this, config);
        } catch (Exception e) { }
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
    }
}