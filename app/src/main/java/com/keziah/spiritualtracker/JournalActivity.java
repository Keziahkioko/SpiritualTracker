package com.keziah.spiritualtracker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.keziah.spiritualtracker.databinding.ActivityJournalBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
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
    private boolean isPaused = false; // NEW: Track the pause state

    // --- TIMER VARIABLES ---
    private Handler timerHandler = new Handler();
    private int secondsElapsed = 0;
    private String currentDuration = "00:00";

    // --- EDIT MODE VARIABLES ---
    private String existingDocId = null;
    private String existingAudioUrl = null;
    private String existingStatus = null;

    // --- PHOTOS: uploaded URLs and/or local files waiting to upload ---
    private final ArrayList<String> photos = new ArrayList<>();
    private final ArrayList<String> originalPhotos = new ArrayList<>();
    private final androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest> pickPhotos =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(JournalPhotos.MAX),
                    this::onPhotosPicked);

    // --- NOTIFICATION VARIABLE ---
    private String partnerId = null;


    private Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) { // Only increment if we are actively recording
                secondsElapsed++;
                int mins = secondsElapsed / 60;
                int secs = secondsElapsed % 60;
                currentDuration = String.format("%02d:%02d", mins, secs);
                binding.tvRecordTimer.setText(currentDuration);
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityJournalBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();
        audioRecorder = new AudioRecorder(this);

        if (auth.getCurrentUser() == null) {
            finish();
            return;
        }

        // Fetch partner ID for notifications
        fetchPartnerId();


        if (getIntent().hasExtra("docId")) {
            existingDocId = getIntent().getStringExtra("docId");
            existingAudioUrl = getIntent().getStringExtra("audioUrl");
            existingDateStr = getIntent().getStringExtra("originalDate");
            existingStatus = getIntent().getStringExtra("status");

            binding.etTitle.setText(getIntent().getStringExtra("title"));
            binding.etContent.setText(getIntent().getStringExtra("content"));

            ArrayList<String> existing = getIntent().getStringArrayListExtra("imageUrls");
            if (existing != null) { photos.addAll(existing); originalPhotos.addAll(existing); }

            binding.btnSaveJournal.setText("Update Entry");
            binding.tvJournalHeading.setText("Edit Entry");
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                checkUnsavedChanges();
            }
        });

        binding.tvJournalDate.setText(new java.text.SimpleDateFormat("EEEE, d MMMM yyyy",
                java.util.Locale.getDefault()).format(new java.util.Date()));

        // Same "save draft?" check as the phone's back button, so nothing is lost.
        binding.btnJournalClose.setOnClickListener(v -> checkUnsavedChanges());

        binding.btnRecord.setOnClickListener(v -> {
            if (checkPermissions()) {
                toggleRecording();
            } else {
                requestPermissions();
            }
        });

        binding.btnAddPhoto.setOnClickListener(v -> {
            if (photos.size() >= JournalPhotos.MAX) return;
            pickPhotos.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                    .setMediaType(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });
        renderPhotos();

        SpringPress.on(binding.btnSaveJournal, 0.95f);
        binding.btnSaveJournal.setOnClickListener(v -> {
            saveJournalEntry("published");
        });

        binding.btnDiscardAudio.setOnClickListener(v -> {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Discard Recording?")
                    .setMessage("Are you sure you want to delete this audio and start over?")
                    .setPositiveButton("Discard", (dialog, which) -> discardAudio())
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void checkUnsavedChanges() {
        String title = binding.etTitle.getText().toString().trim();
        String content = binding.etContent.getText().toString().trim();

        if (localAudioPath != null || isRecording || !TextUtils.isEmpty(title) || !TextUtils.isEmpty(content)
                || !photos.equals(originalPhotos)) {
            new AlertDialog.Builder(this)
                    .setTitle("Save Draft?")
                    .setMessage("You have unsaved changes. Would you like to save this as a draft?")
                    .setPositiveButton("Save Draft", (dialog, which) -> saveJournalEntry("draft"))
                    .setNegativeButton("Discard", (dialog, which) -> finish())
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            finish();
        }
    }

    private void toggleRecording() {
        if (!isRecording && !isPaused) {
            // STATE 1: START FRESH
            File cacheDir = getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir();
            localAudioPath = cacheDir.getAbsolutePath() + "/temp_audio_" + System.currentTimeMillis() + ".m4a";
            audioRecorder.startRecording(localAudioPath);
            isRecording = true;
            isPaused = false;

            secondsElapsed = 0;
            binding.tvRecordTimer.setVisibility(View.VISIBLE);
            binding.btnDiscardAudio.setVisibility(View.VISIBLE);
            binding.tvRecordTimer.setText("00:00");
            timerHandler.postDelayed(timerRunnable, 1000);

            updateUIForRecording();
            Toast.makeText(this, "Recording Started...", Toast.LENGTH_SHORT).show();

        } else if (isRecording) {
            // STATE 2: PAUSE
            audioRecorder.pauseRecording();
            isRecording = false;
            isPaused = true;

            timerHandler.removeCallbacks(timerRunnable); // Stop the clock
            updateUIForPaused();
            Toast.makeText(this, "Recording Paused", Toast.LENGTH_SHORT).show();

        } else if (isPaused) {
            // STATE 3: RESUME
            audioRecorder.resumeRecording();
            isRecording = true;
            isPaused = false;

            timerHandler.postDelayed(timerRunnable, 1000); // Resume the clock
            updateUIForRecording();
            Toast.makeText(this, "Resumed Recording", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUIForRecording() {
        binding.btnRecord.setImageResource(R.drawable.ic_st_pause);
        binding.btnRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#A23FB5")));
    }

    private void updateUIForPaused() {
        // Paused: deep violet (recording is magenta, ready is the usual purple)
        binding.btnRecord.setImageResource(R.drawable.ic_st_mic);
        binding.btnRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4A2C8F")));
    }

    private void stopAndFinalizeRecording() {
        if (isRecording || isPaused) {
            audioRecorder.stopRecording();
            isRecording = false;
            isPaused = false;
            timerHandler.removeCallbacks(timerRunnable);

            binding.btnDiscardAudio.setVisibility(View.GONE);

            // Return icon to normal purple after save/stop logic is complete elsewhere
            binding.btnRecord.setImageResource(R.drawable.ic_st_mic);
            binding.btnRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#673AB7")));
        }
    }

    // NEW METHOD: Discard current recording and reset UI
    private void discardAudio() {
        // 1. Stop the recorder if it's running
        if (isRecording || isPaused) {
            audioRecorder.stopRecording();
        }

        // 2. Delete the physical file so it doesn't waste phone space
        if (localAudioPath != null) {
            java.io.File audioFile = new java.io.File(localAudioPath);
            if (audioFile.exists()) {
                audioFile.delete();
            }
            localAudioPath = null;
        }

        // 3. Reset all logic states
        isRecording = false;
        isPaused = false;
        secondsElapsed = 0;

        // 4. Stop the timer
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        // 5. Reset the UI back to default
        binding.tvRecordTimer.setVisibility(android.view.View.GONE);
        binding.tvRecordTimer.setText("00:00");
        binding.btnDiscardAudio.setVisibility(android.view.View.GONE);

        // Reset Record button back to purple microphone
        binding.btnRecord.setImageResource(R.drawable.ic_st_mic);
        binding.btnRecord.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#673AB7")));

        android.widget.Toast.makeText(this, "Audio discarded", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void saveJournalEntry(String status) {
        String title = binding.etTitle.getText().toString().trim();
        String content = binding.etContent.getText().toString().trim();

        // 1. VALIDATE FIRST (Before touching the audio recorder)
        if (status.equals("published") && TextUtils.isEmpty(title)) {
            Toast.makeText(this, "Please write a title!", Toast.LENGTH_SHORT).show();

            // If they were actively recording, let's auto-pause it so they don't lose the audio
            if (isRecording) {
                audioRecorder.pauseRecording();
                isRecording = false;
                isPaused = true;
                timerHandler.removeCallbacks(timerRunnable);
                updateUIForPaused(); // This turns the button GREEN
                Toast.makeText(this, "Recording paused. Add a title to save.", Toast.LENGTH_SHORT).show();
            }

            // Exit the save process early. Audio is still safely held in the paused state!
            return;
        }

        if (status.equals("draft") && TextUtils.isEmpty(title)) {
            title = "Untitled Draft";
        }

        // 2. ONLY NOW do we finalize and stop the recording
        if (isRecording || isPaused) {
            stopAndFinalizeRecording();
        }

        binding.btnSaveJournal.setEnabled(false);
        binding.btnSaveJournal.setAlpha(0.6f);
        binding.btnSaveJournal.setText(status.equals("draft") ? "Saving draft…" : "Saving…");

        final String finalTitle = title;
        uploadPhotosThen(() -> {
            if (localAudioPath != null) {
                uploadAudioToCloudinary(finalTitle, content, status);
            } else {
                saveToFirestore(finalTitle, content, existingAudioUrl, status);
            }
        });
    }

    // ── Photos ────────────────────────────────────────────────────────────

    private void onPhotosPicked(java.util.List<android.net.Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        int room = JournalPhotos.MAX - photos.size();
        if (uris.size() > room) {
            Toast.makeText(this, "Up to " + JournalPhotos.MAX + " photos per entry", Toast.LENGTH_SHORT).show();
        }
        for (int i = 0; i < Math.min(room, uris.size()); i++) {
            JournalPhotos.shrink(this, uris.get(i), path -> {
                if (isFinishing()) return;
                if (path == null) {
                    Toast.makeText(this, "Couldn't add that photo", Toast.LENGTH_SHORT).show();
                } else if (photos.size() < JournalPhotos.MAX) {
                    photos.add(path);
                    renderPhotos();
                }
            });
        }
    }

    /** Rebuilds the strip: the "Add photo" tile stays first, the photos follow. */
    private void renderPhotos() {
        android.widget.LinearLayout strip = binding.photoStrip;
        while (strip.getChildCount() > 1) strip.removeViewAt(1);
        float d = getResources().getDisplayMetrics().density;
        int size = (int) (76 * d);
        for (int i = 0; i < photos.size(); i++) {
            final int index = i;
            android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(size, size);
            lp.setMarginStart((int) (10 * d));
            tile.setLayoutParams(lp);

            com.google.android.material.imageview.ShapeableImageView iv = new com.google.android.material.imageview.ShapeableImageView(this);
            iv.setShapeAppearanceModel(iv.getShapeAppearanceModel().toBuilder().setAllCornerSizes(14 * d).build());
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setContentDescription("Photo " + (i + 1));
            iv.setOnClickListener(v -> JournalPhotos.open(this, photos, index));
            tile.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
            JournalPhotos.load(iv, photos.get(i), 240);

            android.widget.ImageView remove = new android.widget.ImageView(this);
            remove.setImageResource(R.drawable.ic_close_small);
            remove.setBackgroundResource(R.drawable.bg_photo_remove);
            int pad = (int) (3 * d);
            remove.setPadding(pad, pad, pad, pad);
            remove.setContentDescription("Remove photo");
            remove.setOnClickListener(v -> { photos.remove(index); renderPhotos(); });
            android.widget.FrameLayout.LayoutParams rlp = new android.widget.FrameLayout.LayoutParams((int) (22 * d), (int) (22 * d),
                    android.view.Gravity.TOP | android.view.Gravity.END);
            rlp.setMargins(0, (int) (4 * d), (int) (4 * d), 0);
            tile.addView(remove, rlp);

            strip.addView(tile);
        }
        boolean full = photos.size() >= JournalPhotos.MAX;
        binding.btnAddPhoto.setAlpha(full ? 0.45f : 1f);
        binding.btnAddPhoto.setEnabled(!full);
        binding.tvAddPhoto.setText(full ? "4 of 4" : photos.isEmpty() ? "Add photo" : "Add more");
    }

    private void uploadPhotosThen(Runnable next) {
        boolean anyLocal = false;
        for (String p : photos) if (!p.startsWith("http")) anyLocal = true;
        if (!anyLocal) { next.run(); return; }
        JournalPhotos.uploadAll(photos, new JournalPhotos.Uploaded() {
            @Override public void progress(int done, int total) {
                binding.btnSaveJournal.setText(total == 1 ? "Uploading photo…"
                        : "Uploading photo " + (done + 1) + " of " + total + "…");
            }
            @Override public void finished(ArrayList<String> urls) {
                if (isFinishing()) return;
                if (urls == null) {
                    binding.btnSaveJournal.setEnabled(true);
                    binding.btnSaveJournal.setAlpha(1f);
                    binding.btnSaveJournal.setText(existingDocId != null ? "Update Entry" : "Save Entry");
                    Toast.makeText(JournalActivity.this,
                            "Couldn't upload the photos. Check your connection and try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                photos.clear();
                photos.addAll(urls);
                next.run();
            }
        });
    }

    private void uploadAudioToCloudinary(String title, String content, String status) {
        binding.btnSaveJournal.setText("Uploading voice note…");
        MediaManager.get().upload(localAudioPath)
                .option("folder", RelaySignatureProvider.FOLDER)
                .option("resource_type", "auto")
                .callback(new UploadCallback() {
                    @Override public void onSuccess(String requestId, Map resultData) {
                        String audioUrl = (String) resultData.get("secure_url");
                        
                        // ✨ CACHE FOR INSTANT PLAY ✨
                        cacheUploadedAudio(audioUrl);
                        
                        saveToFirestore(title, content, audioUrl, status);
                    }
                    @Override public void onError(String requestId, ErrorInfo error) {
                        runOnUiThread(() -> {
                            Toast.makeText(JournalActivity.this, "Upload Failed", Toast.LENGTH_LONG).show();
                            binding.btnSaveJournal.setEnabled(true);
                            binding.btnSaveJournal.setAlpha(1f);
                        });
                    }
                    @Override public void onStart(String id) {}
                    @Override public void onProgress(String id, long b, long t) {}
                    @Override public void onReschedule(String id, ErrorInfo e) {}
                }).dispatch();
    }

    private void cacheUploadedAudio(String url) {
        if (localAudioPath == null) return;
        File source = new File(localAudioPath);
        File target = new File(getCacheDir(), "entry_audio_" + Math.abs(url.hashCode()) + ".m4a");
        
        try (FileChannel in = new FileInputStream(source).getChannel();
             FileChannel out = new FileOutputStream(target).getChannel()) {
            out.transferFrom(in, 0, in.size());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveToFirestore(String title, String content, String audioUrl, String status) {
        if (auth.getCurrentUser() == null) return;
        runOnUiThread(() -> binding.btnSaveJournal.setText("Saving…"));
        String userId = auth.getCurrentUser().getUid();

        db.collection("users").document(userId).get().addOnSuccessListener(documentSnapshot -> {
            String authorName = UserProfile.displayName(documentSnapshot);
            String durationToSave = (audioUrl != null) ? currentDuration : null;
            boolean publishing = "published".equals(status);

            if (existingDocId != null) {
                // UPDATE EXISTING ENTRY
                Map<String, Object> updates = new HashMap<>();
                updates.put("title", title);
                updates.put("content", content);
                updates.put("status", status);
                updates.put("authorName", authorName);
                updates.put("imageUrls", new ArrayList<>(photos));
                if (audioUrl != null) {
                    updates.put("audioUrl", audioUrl);
                    updates.put("audioDuration", durationToSave);
                }
                // A draft becomes a real entry on the day it's published, not the day it was started.
                boolean publishingDraft = publishing && "draft".equals(existingStatus);
                if (publishingDraft) updates.put("date", new Timestamp(new Date()));

                Runnable save = () -> db.collection("journal_entries").document(existingDocId)
                        .update(updates)
                        .addOnSuccessListener(aVoid -> {
                            if (publishingDraft) onPublished(userId, authorName, title, existingDocId);
                            closeAfterSave(publishingDraft);
                        })
                        .addOnFailureListener(e -> onSaveFailed());

                // Files taken off the entry while editing: removed photos and a replaced voice note.
                ArrayList<String> dropped = new ArrayList<>();
                for (String p : originalPhotos) if (p.startsWith("http") && !photos.contains(p)) dropped.add(p);
                if (audioUrl != null && existingAudioUrl != null && !audioUrl.equals(existingAudioUrl)) dropped.add(existingAudioUrl);
                // Clean-up first: the relay only deletes files the entry still lists. Save either way.
                if (dropped.isEmpty()) save.run();
                else Relay.deleteJournalMedia(existingDocId, dropped, ok -> save.run());
            } else {
                // CREATE NEW ENTRY
                JournalEntry entry = new JournalEntry(title, content, new Timestamp(new Date()), userId, partnerId, authorName, status, durationToSave);
                if (audioUrl != null) entry.setAudioUrl(audioUrl);
                if (!photos.isEmpty()) entry.setImageUrls(new ArrayList<>(photos));
                entry.setReadByPartner(false);

                db.collection("journal_entries")
                        .add(entry)
                        .addOnSuccessListener(documentReference -> {
                            if (publishing) onPublished(userId, authorName, title, documentReference.getId());
                            closeAfterSave(publishing);
                        })
                        .addOnFailureListener(e -> onSaveFailed());
            }
        }).addOnFailureListener(e -> onSaveFailed());
    }

    /** A newly published entry closes with a short sparkle from the save button; drafts just close. */
    private void closeAfterSave(boolean published) {
        if (!published || isFinishing()) { finish(); return; }
        SparkleBurst.play(getWindow().getDecorView(), binding.btnSaveJournal, 28, 1.1f, 900, false, true, this::finish);
    }

    /** Ticks today's journal box (for both of us) and lets the partner know. */
    private void onPublished(String userId, String authorName, String title, String docId) {
        ActivityLog.addJournal(userId, title);
        db.collection("users").document(userId)
                .set(java.util.Collections.singletonMap("journaledTodayDate", ActivityLog.today()),
                        com.google.firebase.firestore.SetOptions.merge());
        PartnerNotifier.incrementUnread(partnerId, "unreadJournal");
        PartnerNotifier.notifyPartner(partnerId, "New Journal Entry",
                authorName + " published: " + title, "journal_new", docId);
    }

    private void onSaveFailed() {
        binding.btnSaveJournal.setEnabled(true);
        binding.btnSaveJournal.setAlpha(1f);
        binding.btnSaveJournal.setText(existingDocId != null ? "Update Entry" : "Save Entry");
        Toast.makeText(this, "Couldn't save. Check your connection and try again.", Toast.LENGTH_LONG).show();
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
    }

    private void fetchPartnerId() {
        if (auth.getCurrentUser() == null) return;
        db.collection("users").document(auth.getCurrentUser().getUid())
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    String pid = documentSnapshot.getString("partnerId");
                    if (pid != null && !pid.isEmpty()) partnerId = pid;
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timerHandler.removeCallbacks(timerRunnable);
        if (isRecording || isPaused) audioRecorder.stopRecording();
    }
}
