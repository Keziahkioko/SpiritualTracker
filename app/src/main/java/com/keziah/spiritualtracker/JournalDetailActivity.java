package com.keziah.spiritualtracker;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JournalDetailActivity extends AppCompatActivity {

    private TextView tvTitle, tvContent, tvDate, tvAuthor, tvDuration;
    private LinearLayout audioContainer;
    private ImageButton btnPlayAudio;
    private ProgressBar pbEntryDownload;
    private ImageButton btnEditEntry;
    private SeekBar seekBarAudio;

    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private boolean isPaused = false;
    private String audioUrl;
    private String docId;
    private String status;
    private String audioDuration;
    private String authorId;

    private Handler playbackHandler = new Handler();
    private Runnable playbackRunnable;

    // --- CHAT & RECORDING VIEWS ---
    private EditText etReplyInput;
    private FloatingActionButton btnSendOrRecord;
    private LinearLayout layoutRecordingUI;
    private TextView tvRecordTimer;
    private ImageButton btnDiscardAudio;
    private ImageButton btnSendAudio;
    private boolean isTyping = false;

    // --- SWIPE TO REPLY ADDITIONS ---
    private LinearLayout layoutReplyPreview;
    private TextView tvReplyPreviewName, tvReplyPreviewText;
    private ImageButton btnCloseReply;
    private JournalReply selectedReplyForQuoting = null;

    // --- EDIT MESSAGE TRACKER ---
    private String editingReplyId = null;

    // --- RECYCLER VIEW VARIABLES ---
    private RecyclerView rvReplies;
    private JournalReplyAdapter replyAdapter;
    private List<JournalReply> replyList;

    // --- FIRESTORE LISTENER (MEMORY LEAK FIX) ---
    private ListenerRegistration replyListener;

    // --- AUDIO RECORDING VARIABLES ---
    private AudioRecorder audioRecorder;
    private String localAudioPath = null;
    private boolean isRecording = false;
    private boolean isCurrentlyRecordingPaused = false;
    private Handler timerHandler = new Handler();
    private int secondsElapsed = 0;
    private String currentDurationStr = "00:00";

    // --- NOTIFICATION VARIABLE ---
    private String partnerId = null;

    // --- VARIABLES FOR UNREAD DIVIDER ---
    private String firstUnreadMessageId = null;
    private boolean hasScrolledToUnread = false;

    // --- TYPING INDICATOR VARIABLES ---
    private TextView tvTypingIndicator;
    private com.google.firebase.firestore.ListenerRegistration typingListener;
    private boolean isCurrentlyTyping = false;

    private static final String UPLOAD_PRESET = BuildConfig.CLOUDINARY_UPLOAD_PRESET;


    // --- NEW: THE TIMEOUT TIMER ---
    private android.os.Handler typingHandler = new android.os.Handler();
    private Runnable typingTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            // If 3 seconds pass with no typing, turn it off!
            isCurrentlyTyping = false;
            updateTypingStatus("");
        }
    };

    private Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording) {
                secondsElapsed++;
                int mins = secondsElapsed / 60;
                int secs = secondsElapsed % 60;
                currentDurationStr = String.format("%02d:%02d", mins, secs);
                tvRecordTimer.setText(currentDurationStr);
                timerHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_journal_detail);

        // Fetch partner ID for notifications
        fetchPartnerId();

        // 1. Initialize Views
        tvTitle = findViewById(R.id.tvDetailTitle);
        tvContent = findViewById(R.id.tvDetailContent);
        tvDate = findViewById(R.id.tvDetailDate);
        tvAuthor = findViewById(R.id.tvDetailAuthor);
        tvDuration = findViewById(R.id.tvDetailDuration);
        audioContainer = findViewById(R.id.audioPlayerContainer);
        btnPlayAudio = findViewById(R.id.btnPlayEntryAudio);
        pbEntryDownload = findViewById(R.id.pbEntryDownload);
        btnEditEntry = findViewById(R.id.btnEditEntry);
        seekBarAudio = findViewById(R.id.seekBarAudio);

        // --- SWIPE TO REPLY UI ---
        layoutReplyPreview = findViewById(R.id.layoutReplyPreview);
        tvReplyPreviewName = findViewById(R.id.tvReplyPreviewName);
        tvReplyPreviewText = findViewById(R.id.tvReplyPreviewText);
        btnCloseReply = findViewById(R.id.btnCloseReply);

        tvTypingIndicator = findViewById(R.id.tvTypingIndicator);

        btnCloseReply.setOnClickListener(v -> clearReplyPreview());

        // 2. Get Data from Intent
        String title = getIntent().getStringExtra("title");
        String content = getIntent().getStringExtra("content");
        String date = getIntent().getStringExtra("date");
        String authorName = getIntent().getStringExtra("authorName");
        authorId = getIntent().getStringExtra("authorId");
        audioUrl = getIntent().getStringExtra("audioUrl");
        docId = getIntent().getStringExtra("docId");
        status = getIntent().getStringExtra("status");
        audioDuration = getIntent().getStringExtra("audioDuration");

        if (docId == null || docId.isEmpty() || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }

        markJournalAsRead();


        // 3. Set Text & Styling
        if ("draft".equals(status)) {
            tvTitle.setText(title + " (Draft)");
            tvTitle.setTextColor(Color.parseColor("#EF6C00"));
        } else {
            tvTitle.setText(title);
        }

        tvContent.setText(content);
        tvDate.setText(date);

        // --- Handle Author Display and Edit Permissions ---
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            String currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

            if (authorId != null && authorId.equals(currentUid)) {
                tvAuthor.setText("By: Me");
                tvAuthor.setTextColor(Color.parseColor("#673AB7"));
                btnEditEntry.setVisibility(View.VISIBLE);
            } else {
                String displayAuthor = (authorName != null) ? authorName : "Partner";
                tvAuthor.setText("By: " + displayAuthor);
                tvAuthor.setTextColor(Color.parseColor("#2E7D32"));
                tvAuthor.setBackgroundColor(Color.parseColor("#E8F5E9"));
                btnEditEntry.setVisibility(View.GONE);
            }
        }

        // 4. Setup Audio Player
        if (audioUrl != null && !audioUrl.isEmpty()) {
            audioContainer.setVisibility(View.VISIBLE);

            String displayTime = "00:00 / " + (audioDuration != null ? audioDuration : "--:--");
            tvDuration.setText(displayTime);

            File cacheFile = getCacheFile(this, audioUrl);
            String currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            boolean isMine = (authorId != null && authorId.equals(currentUid));

            // WhatsApp Style: Show play button immediately if it's mine or already downloaded
            if (isMine || cacheFile.exists()) {
                btnPlayAudio.setImageResource(android.R.drawable.ic_media_play);
            } else {
                btnPlayAudio.setImageResource(android.R.drawable.stat_sys_download);
            }

            btnPlayAudio.setOnClickListener(v -> {
                if (isPlaying) {
                    pauseAudio();
                } else if (isPaused) {
                    resumeAudio();
                } else {
                    // Always check cache first (prepareAndPlayAudio does this)
                    prepareAndPlayAudio(audioUrl);
                }
            });

            seekBarAudio.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && mediaPlayer != null) {
                        mediaPlayer.seekTo(progress);
                        updateTimeLabel(progress / 1000);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        } else {
            audioContainer.setVisibility(View.GONE);
        }

        // 5. Setup Edit Button
        btnEditEntry.setOnClickListener(v -> {
            Intent intent = new Intent(JournalDetailActivity.this, JournalActivity.class);
            intent.putExtra("docId", docId);
            intent.putExtra("title", title);
            intent.putExtra("content", content);
            intent.putExtra("audioUrl", audioUrl);
            intent.putExtra("originalDate", date);
            intent.putExtra("status", status);
            intent.putExtra("audioDuration", audioDuration);
            startActivity(intent);
            finish();
        });

        // 6. INITIALIZE CHAT & RECORDING VIEWS
        etReplyInput = findViewById(R.id.etReplyInput);

        // Listen to your own typing
        etReplyInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                boolean hasText = s.toString().trim().length() > 0;

                if (hasText) {
                    // 1. Turn on typing status if it isn't on already
                    if (!isCurrentlyTyping) {
                        isCurrentlyTyping = true;
                        updateTypingStatus(docId);
                    }

                    // 2. Restart the countdown timer!
                    // (Cancel the old 3-second timer, and start a fresh one)
                    typingHandler.removeCallbacks(typingTimeoutRunnable);
                    typingHandler.postDelayed(typingTimeoutRunnable, 3000); // 3000 milliseconds = 3 seconds

                } else {
                    // If they manually hit backspace until the box is empty
                    if (isCurrentlyTyping) {
                        isCurrentlyTyping = false;
                        updateTypingStatus("");
                        typingHandler.removeCallbacks(typingTimeoutRunnable); // Stop the timer
                    }
                }
            }
        });
        btnSendOrRecord = findViewById(R.id.btnSendOrRecord);
        layoutRecordingUI = findViewById(R.id.layoutRecordingUI);
        tvRecordTimer = findViewById(R.id.tvRecordTimer);
        btnDiscardAudio = findViewById(R.id.btnDiscardAudio);
        btnSendAudio = findViewById(R.id.btnSendAudio);
        audioRecorder = new AudioRecorder(this);

        // Keyboard watcher (Swaps Mic to Send when typing)
        etReplyInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.toString().trim().length() > 0) {
                    if (!isTyping) {
                        btnSendOrRecord.setImageResource(android.R.drawable.ic_menu_send);
                        isTyping = true;
                    }
                } else {
                    if (isTyping) {
                        btnSendOrRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
                        isTyping = false;
                    }
                }
            }
        });

        // Main FAB Click
        btnSendOrRecord.setOnClickListener(v -> {
            if (isTyping) {
                sendTextMessage();
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    toggleRecording();
                } else {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
                }
            }
            updateTypingStatus("");
            isCurrentlyTyping = false;
        });

        btnDiscardAudio.setOnClickListener(v -> discardAudio());
        btnSendAudio.setOnClickListener(v -> sendAudioMessage());

        // 7. SETUP RECYCLER VIEW
        rvReplies = findViewById(R.id.rvReplies);
        rvReplies.setLayoutManager(new LinearLayoutManager(this));
        replyList = new ArrayList<>();

        replyAdapter = new JournalReplyAdapter(replyList);
        rvReplies.setAdapter(replyAdapter);

        replyAdapter.setOnReactionClickListener((reply, emoji) -> {
            saveReactionToFirebase(reply, emoji);
        });

        // --- NEW: EDIT & DELETE LISTENERS ---
        replyAdapter.setOnMessageActionListener(new JournalReplyAdapter.OnMessageActionClickListener() {
            @Override
            public void onEditClick(JournalReply reply) {
                startEditingMessage(reply);
            }

            @Override
            public void onDeleteClick(JournalReply reply) {
                deleteMessage(reply);
            }
        });
        // --- NEW: TAP QUOTE TO SCROLL ---
        replyAdapter.setOnQuoteClickListener(quotedMessageId -> {
            for (int i = 0; i < replyList.size(); i++) {
                if (replyList.get(i).getReplyId() != null && replyList.get(i).getReplyId().equals(quotedMessageId)) {
                    // 1. Scroll smoothly to the original message
                    rvReplies.smoothScrollToPosition(i);

                    // 2. Trigger the brief highlight effect
                    replyAdapter.setHighlightedMessage(quotedMessageId);
                    break;
                }
            }
        });

        // --- ATTACH SWIPE GESTURE ---
        setupSwipeToReply();

        // Fetch messages
        loadReplies();

        // Clear any lingering notifications when the app is opened
        android.app.NotificationManager notificationManager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancelAll();
        }
    }

    // ==========================================
    // AUDIO CACHING & PLAYBACK LOGIC
    // ==========================================

    private File getCacheFile(Context context, String url) {
        if (url == null) return new File("");
        return new File(context.getCacheDir(), "entry_audio_" + Math.abs(url.hashCode()) + ".m4a");
    }

    private void prepareAndPlayAudio(String url) {
        File cacheFile = getCacheFile(this, url);

        if (cacheFile.exists()) {
            // Instant Play from local cache!
            playAudioFromPath(cacheFile.getAbsolutePath());
        } else {
            // Download once, then play
            downloadAndPlayAudio(url, cacheFile);
        }
    }

    private void downloadAndPlayAudio(String url, File targetFile) {
        btnPlayAudio.setVisibility(View.INVISIBLE);
        pbEntryDownload.setVisibility(View.VISIBLE);

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() -> {
                    btnPlayAudio.setVisibility(View.VISIBLE);
                    pbEntryDownload.setVisibility(View.GONE);
                    Toast.makeText(JournalDetailActivity.this, "Failed to load audio", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        btnPlayAudio.setVisibility(View.VISIBLE);
                        pbEntryDownload.setVisibility(View.GONE);
                        Toast.makeText(JournalDetailActivity.this, "Server error", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    runOnUiThread(() -> {
                        btnPlayAudio.setVisibility(View.VISIBLE);
                        pbEntryDownload.setVisibility(View.GONE);
                        playAudioFromPath(targetFile.getAbsolutePath());
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        btnPlayAudio.setVisibility(View.VISIBLE);
                        pbEntryDownload.setVisibility(View.GONE);
                        Toast.makeText(JournalDetailActivity.this, "Cache error", Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }

    private void playAudioFromPath(String path) {
        try {
            stopAudio(); // Release any previous instance
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepareAsync();

            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isPlaying = true;
                isPaused = false;
                btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause);
                seekBarAudio.setMax(mp.getDuration());
                startPlaybackTimer();
            });

            mediaPlayer.setOnCompletionListener(mp -> stopAudio());

        } catch (IOException e) {
            Log.e("JournalDetail", "Playback failed", e);
            Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show();
        }
    }

    private void pauseAudio() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            isPaused = true;
            btnPlayAudio.setImageResource(android.R.drawable.ic_media_play);
            if (playbackRunnable != null) playbackHandler.removeCallbacks(playbackRunnable);
        }
    }

    private void resumeAudio() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            isPlaying = true;
            isPaused = false;
            btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause);
            startPlaybackTimer();
        }
    }

    private void startPlaybackTimer() {
        playbackRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    int currentPos = mediaPlayer.getCurrentPosition();
                    seekBarAudio.setProgress(currentPos);
                    updateTimeLabel(currentPos / 1000);
                    playbackHandler.postDelayed(this, 500);
                }
            }
        };
        playbackHandler.postDelayed(playbackRunnable, 0);
    }

    private void updateTimeLabel(int currentSeconds) {
        int mins = currentSeconds / 60;
        int secs = currentSeconds % 60;
        String timeElapsed = String.format("%02d:%02d", mins, secs);
        String total = (audioDuration != null) ? audioDuration : "--:--";
        tvDuration.setText(timeElapsed + " / " + total);
    }

    private void stopAudio() {
        if (playbackRunnable != null) {
            playbackHandler.removeCallbacks(playbackRunnable);
        }

        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        isPlaying = false;
        isPaused = false;
        btnPlayAudio.setImageResource(android.R.drawable.ic_media_play);
        seekBarAudio.setProgress(0);

        String total = (audioDuration != null) ? audioDuration : "--:--";
        tvDuration.setText("00:00 / " + total);
    }

    // ==========================================
    // SWIPE TO REPLY LOGIC
    // ==========================================

    @Override
    protected void onPause() {
        super.onPause();
        // Clear typing & recording status if we leave the chat screen
        updateTypingStatus("");
        updateRecordingStatus("");
        isCurrentlyTyping = false;
    }

    private void listenToPartnerActivity() { // Renamed slightly to make sense for both!
        if (partnerId == null) return;

        typingListener = FirebaseFirestore.getInstance().collection("users")
                .document(partnerId)
                .addSnapshotListener((doc, error) -> {
                    if (error != null || doc == null || !doc.exists()) return;

                    String typingIn = doc.getString("typingIn");
                    String recordingIn = doc.getString("recordingIn");

                    // 1. Check if they are recording first (Audio takes priority!)
                    if (recordingIn != null && recordingIn.equals(docId)) {
                        tvTypingIndicator.setText("Partner is recording audio...");
                        tvTypingIndicator.setVisibility(android.view.View.VISIBLE);
                    }
                    // 2. If not recording, check if they are typing
                    else if (typingIn != null && typingIn.equals(docId)) {
                        tvTypingIndicator.setText("Partner is typing...");
                        tvTypingIndicator.setVisibility(android.view.View.VISIBLE);
                    }
                    // 3. If neither, hide it!
                    else {
                        tvTypingIndicator.setVisibility(android.view.View.GONE);
                    }
                });
    }


    private void updateTypingStatus(String journalId) {
        String currentUid = FirebaseAuth.getInstance().getUid();
        if (currentUid != null) {
            FirebaseFirestore.getInstance().collection("users")
                                        .document(currentUid).set(java.util.Collections.singletonMap("typingIn", journalId),
                            com.google.firebase.firestore.SetOptions.merge());
        }
    }

    private void updateRecordingStatus(String journalId) {
        String currentUid = FirebaseAuth.getInstance().getUid();
        if (currentUid != null) {
            FirebaseFirestore.getInstance().collection("users")
                                        .document(currentUid).set(java.util.Collections.singletonMap("recordingIn", journalId),
                            com.google.firebase.firestore.SetOptions.merge());
        }
    }

    private void setupSwipeToReply() {
        ItemTouchHelper.SimpleCallback touchHelperCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION || position >= replyList.size()) return;
                selectedReplyForQuoting = replyList.get(position);
                editingReplyId = null; // Clear edit mode if they swipe

                // Show the preview UI
                layoutReplyPreview.setVisibility(View.VISIBLE);

                if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                    String currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
                    String senderId = selectedReplyForQuoting.getSenderId();

                    // BUG FIX: Added a null check and explicitly ensured the strings match perfectly
                    boolean isMyMessage = (senderId != null && senderId.trim().equals(currentUid.trim()));
                    tvReplyPreviewName.setText(isMyMessage ? "You" : "Partner");

                    // Check if quoting audio or text
                    if ("audio".equals(selectedReplyForQuoting.getType())) {
                        tvReplyPreviewText.setText("🎤 Voice Note (" + selectedReplyForQuoting.getDuration() + ")");
                    } else {
                        tvReplyPreviewText.setText(selectedReplyForQuoting.getMessage());
                    }
                }

                // Snap the swiped message back into place immediately
                replyAdapter.notifyItemChanged(position);
            }

            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    // Limit the visual swipe distance
                    float maxSwipeDistance = recyclerView.getWidth() / 10.0f;
                    float limitedDx = Math.min(dX, maxSwipeDistance);

                    super.onChildDraw(c, recyclerView, viewHolder, limitedDx, dY, actionState, isCurrentlyActive);
                } else {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                }
            }
        };

        new ItemTouchHelper(touchHelperCallback).attachToRecyclerView(rvReplies);
    }

    private void clearReplyPreview() {
        layoutReplyPreview.setVisibility(View.GONE);
        selectedReplyForQuoting = null;

        // If we were editing, cancel the edit and clear the box
        if (editingReplyId != null) {
            editingReplyId = null;
            etReplyInput.setText("");
        }
    }

    // ==========================================
    // EDIT AND DELETE LOGIC
    // ==========================================

    private void startEditingMessage(JournalReply reply) {
        editingReplyId = reply.getReplyId();
        selectedReplyForQuoting = null; // Cancel any quoting

        // Put text into the box and move cursor to end
        etReplyInput.setText(reply.getMessage());
        etReplyInput.setSelection(etReplyInput.getText().length());

        // Show the banner to indicate we are editing
        layoutReplyPreview.setVisibility(View.VISIBLE);
        tvReplyPreviewName.setText("✏️ Editing Message");
        tvReplyPreviewText.setText(reply.getMessage());
    }

    private void deleteMessage(JournalReply reply) {
        FirebaseFirestore.getInstance().collection("journals")
                .document(docId).collection("replies")
                .document(reply.getReplyId())
                .delete()
                .addOnSuccessListener(aVoid -> Toast.makeText(this, "Message deleted", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Failed to delete", Toast.LENGTH_SHORT).show());
    }

    // ==========================================
    // UPLOAD AND FIREBASE LOGIC
    // ==========================================

    private void sendTextMessage() {
        String messageText = etReplyInput.getText().toString().trim();
        if (messageText.isEmpty()) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        if (editingReplyId != null) {
            // --- EDIT EXISTING MESSAGE ---
            db.collection("journals")
                    .document(docId).collection("replies")
                    .document(editingReplyId)
                    .update("message", messageText, "isEdited", true)
                    .addOnSuccessListener(aVoid -> {
                        etReplyInput.setText("");
                        clearReplyPreview();
                    })
                    .addOnFailureListener(e -> Toast.makeText(this, "Failed to edit: " + e.getMessage(), Toast.LENGTH_SHORT).show());

        } else {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
            // --- SEND NEW MESSAGE (OPTIMIZED) ---
            Map<String, Object> reply = new HashMap<>();
            reply.put("message", messageText);
            reply.put("type", "text");
            reply.put("journalId", docId);
            reply.put("timestamp", FieldValue.serverTimestamp());
            reply.put("senderId", FirebaseAuth.getInstance().getCurrentUser().getUid());
            reply.put("status", "sent");

            // Inject quoted message data
            if (selectedReplyForQuoting != null) {
                reply.put("quotedMessageId", selectedReplyForQuoting.getReplyId());
                reply.put("quotedMessageSenderId", selectedReplyForQuoting.getSenderId());
                if ("audio".equals(selectedReplyForQuoting.getType())) {
                    reply.put("quotedMessageText", "🎤 Voice Note");
                } else {
                    reply.put("quotedMessageText", selectedReplyForQuoting.getMessage());
                }
            }

            // OPTIMIZATION: Create the document reference first, grab its ID, and save everything in one step
            DocumentReference newReplyRef = db.collection("journals").document(docId).collection("replies").document();
            reply.put("replyId", newReplyRef.getId()); // Save ID immediately

            newReplyRef.set(reply)
                    .addOnSuccessListener(aVoid -> {
                        etReplyInput.setText("");
                        clearReplyPreview();

                        // ✨ FIRE NOTIFICATION ✨
                        incrementPartnerUnread("unreadJournal");
                        notifyPartner("New Message", messageText);
                    })
                    .addOnFailureListener(e -> Toast.makeText(this, "Failed to send: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        }
    }

    private void sendAudioMessage() {
        if (isRecording || isCurrentlyRecordingPaused) {
            audioRecorder.stopRecording();
            updateRecordingStatus("");
            isRecording = false;
            isCurrentlyRecordingPaused = false;
        }
        if (localAudioPath == null) return;

        Toast.makeText(this, "Uploading Voice Note...", Toast.LENGTH_SHORT).show();
        btnSendAudio.setEnabled(false);

        MediaManager.get().upload(localAudioPath)
                .unsigned(UPLOAD_PRESET)
                .option("resource_type", "auto")
                .callback(new UploadCallback() {
                    @Override
                    public void onStart(String requestId) {}

                    @Override
                    public void onProgress(String requestId, long bytes, long totalBytes) {}

                    @Override
                    public void onSuccess(String requestId, Map resultData) {
                        String uploadedAudioUrl = (String) resultData.get("secure_url");
                        
                        // ✨ CACHE BEFORE DISCARD ✨
                        cacheUploadedAudio(uploadedAudioUrl);
                        
                        saveAudioReplyToFirebase(uploadedAudioUrl, currentDurationStr);
                        discardAudio();

                        runOnUiThread(() -> {
                            btnSendAudio.setEnabled(true);
                            Toast.makeText(JournalDetailActivity.this, "Sent!", Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onError(String requestId, ErrorInfo errorInfo) {
                        Log.e("CLOUDINARY_ERROR", "Msg: " + errorInfo.getDescription());
                        runOnUiThread(() -> {
                            Toast.makeText(JournalDetailActivity.this,
                                    "Upload Error: " + errorInfo.getDescription(),
                                    Toast.LENGTH_LONG).show();
                            btnSendAudio.setEnabled(true);
                        });
                    }

                    @Override
                    public void onReschedule(String requestId, ErrorInfo errorInfo) {}
                }).dispatch();
    }

    private void cacheUploadedAudio(String url) {
        if (localAudioPath == null) return;
        File source = new File(localAudioPath);
        File target = getCacheFile(this, url);
        
        try (FileChannel in = new FileInputStream(source).getChannel();
             FileChannel out = new FileOutputStream(target).getChannel()) {
            out.transferFrom(in, 0, in.size());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveAudioReplyToFirebase(String audioUrl, String duration) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        Map<String, Object> reply = new HashMap<>();
        reply.put("audioUrl", audioUrl);
        reply.put("duration", duration);
        reply.put("type", "audio");
        reply.put("journalId", docId);
        reply.put("timestamp", FieldValue.serverTimestamp());
        reply.put("senderId", FirebaseAuth.getInstance().getCurrentUser().getUid());
        reply.put("status", "sent");

        // --- INJECT QUOTED MESSAGE DATA FOR AUDIO MESSAGES ---
        if (selectedReplyForQuoting != null) {
            reply.put("quotedMessageId", selectedReplyForQuoting.getReplyId());
            reply.put("quotedMessageSenderId", selectedReplyForQuoting.getSenderId());
            if ("audio".equals(selectedReplyForQuoting.getType())) {
                reply.put("quotedMessageText", "🎤 Voice Note");
            } else {
                reply.put("quotedMessageText", selectedReplyForQuoting.getMessage());
            }
            runOnUiThread(this::clearReplyPreview); // Clear on UI thread
        }

        // OPTIMIZATION: Create reference first
        DocumentReference newReplyRef = FirebaseFirestore.getInstance().collection("journals").document(docId).collection("replies").document();
        reply.put("replyId", newReplyRef.getId());

        newReplyRef.set(reply)
                .addOnSuccessListener(aVoid -> {
                    // ✨ FIRE NOTIFICATION ✨
                    incrementPartnerUnread("unreadJournal");
                    notifyPartner("New Voice Note", "🎤 Voice Note (" + duration + ")");
                });
    }

    private void loadReplies() {
        // MEMORY LEAK FIX: Assigning to listener variable
        replyListener = FirebaseFirestore.getInstance().collection("journals")
                .document(docId)
                .collection("replies")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null) return;

                    if (value != null) {
                        LinearLayoutManager layoutManager = (LinearLayoutManager) rvReplies.getLayoutManager();
                        if (layoutManager == null) return;
                        int lastVisiblePosition = layoutManager.findLastVisibleItemPosition();
                        int totalItemsBefore = replyList.size();

                        boolean wasAtBottom = (lastVisiblePosition >= totalItemsBefore - 2);

                        replyList.clear();
                        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                            String currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
                            int targetScrollIndex = -1; // Where we should scroll to

                            for (QueryDocumentSnapshot doc : value) {
                                JournalReply reply = doc.toObject(JournalReply.class);
                                reply.setReplyId(doc.getId());

                                // 1. If we haven't found an unread message yet this session, look for one!
                                if (firstUnreadMessageId == null) {
                                    boolean isFromPartner = reply.getSenderId() != null && !reply.getSenderId().equals(currentUid);
                                    if (isFromPartner && !"read".equals(reply.getStatus())) {
                                        firstUnreadMessageId = reply.getReplyId(); // Memorize it!
                                    }
                                }

                                // 2. If this is the memorized message, turn on the divider!
                                if (firstUnreadMessageId != null && firstUnreadMessageId.equals(reply.getReplyId())) {
                                    reply.setFirstUnread(true);
                                    targetScrollIndex = replyList.size(); // The index this item will be at
                                } else {
                                    reply.setFirstUnread(false);
                                }

                                replyList.add(reply);
                            }
                            replyAdapter.notifyDataSetChanged();

                            // READ RECEIPTS BUG FIX: Actually call the read receipts method here!
                            markIncomingMessagesAsRead(replyList);

                            // --- SCROLLING LOGIC ---
                            if (targetScrollIndex != -1 && !hasScrolledToUnread) {
                                // SCROLL TO THE UNREAD DIVIDER! (Only happens once when you open the chat)
                                rvReplies.scrollToPosition(targetScrollIndex);
                                hasScrolledToUnread = true;
                            }
                            else if (replyList.size() > totalItemsBefore) {
                                // NORMAL SCROLLING (If someone sends a new message while you're in the chat)
                                JournalReply lastMessage = replyList.get(replyList.size() - 1);
                                if (lastMessage.getSenderId() != null && lastMessage.getSenderId().equals(currentUid) || wasAtBottom) {
                                    rvReplies.scrollToPosition(replyList.size() - 1);
                                }
                            }
                        }
                    }
                });
    }

    private void incrementPartnerUnread(String field) {
        PartnerNotifier.incrementUnread(partnerId, field);
    }

    // ==========================================
    // VOICE NOTE RECORDING LOGIC
    // ==========================================

    private void toggleRecording() {
        if (!isRecording && !isCurrentlyRecordingPaused) {
            File externalCacheDir = getExternalCacheDir();
            if (externalCacheDir == null) return;
            localAudioPath = externalCacheDir.getAbsolutePath() + "/chat_vn_" + System.currentTimeMillis() + ".m4a";
            audioRecorder.startRecording(localAudioPath);
            updateRecordingStatus(docId);
            isRecording = true;
            isCurrentlyRecordingPaused = false;
            secondsElapsed = 0;

            // Updated from GONE to INVISIBLE as discussed to fix layout jump
            etReplyInput.setVisibility(View.INVISIBLE);
            layoutRecordingUI.setVisibility(View.VISIBLE);
            tvRecordTimer.setText("00:00");
            timerHandler.postDelayed(timerRunnable, 1000);

            updateUIForRecording();
        } else if (isRecording) {
            audioRecorder.pauseRecording();
            updateRecordingStatus("");
            isRecording = false;
            isCurrentlyRecordingPaused = true;

            timerHandler.removeCallbacks(timerRunnable);
            updateUIForPaused();
        } else if (isCurrentlyRecordingPaused) {
            audioRecorder.resumeRecording();
            updateRecordingStatus(docId);
            isRecording = true;
            isCurrentlyRecordingPaused = false;

            timerHandler.postDelayed(timerRunnable, 1000);
            updateUIForRecording();
        }
    }

    private void updateUIForRecording() {
        btnSendOrRecord.setImageResource(android.R.drawable.ic_media_pause);
        btnSendOrRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#673AB7")));
    }

    private void updateUIForPaused() {
        btnSendOrRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
        btnSendOrRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2E7D32")));
    }

    private void discardAudio() {
        if (isRecording || isCurrentlyRecordingPaused) {
            audioRecorder.stopRecording();
            updateRecordingStatus("");
        }

        if (localAudioPath != null) {
            File audioFile = new File(localAudioPath);
            if (audioFile.exists()) {
                audioFile.delete();
            }
            localAudioPath = null;
        }

        isRecording = false;
        isCurrentlyRecordingPaused = false;
        secondsElapsed = 0;
        if (timerHandler != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        layoutRecordingUI.setVisibility(View.GONE);
        etReplyInput.setVisibility(View.VISIBLE);
        btnSendOrRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
        btnSendOrRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#673AB7")));
    }

    // ==========================================
    // EXISTING ANCHOR AUDIO PLAYER LOGIC
    // ==========================================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // MEMORY LEAK FIX: Stop listening when the user closes the screen
        if (replyListener != null) {
            replyListener.remove();
        }
        if (typingListener != null) {
            typingListener.remove();
        }
        typingHandler.removeCallbacks(typingTimeoutRunnable);


        if (playbackHandler != null && playbackRunnable != null) {
            playbackHandler.removeCallbacks(playbackRunnable);
        }
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        if (isRecording || isCurrentlyRecordingPaused) {
            audioRecorder.stopRecording();
            updateRecordingStatus("");
        }
        if (timerHandler != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }
    }

    // ==========================================
    // READ RECEIPTS LOGIC
    // ==========================================

    private void markIncomingMessagesAsRead(List<JournalReply> loadedReplies) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;

        String currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        for (JournalReply reply : loadedReplies) {
            if (reply.getSenderId() != null && !reply.getSenderId().equals(currentUid)) {
                if (!"read".equals(reply.getStatus())) {

                    db.collection("journals")
                            .document(docId)
                            .collection("replies")
                            .document(reply.getReplyId())
                            .update("status", "read")
                            .addOnSuccessListener(aVoid -> {
                            });
                }
            }
        }
    }

    private void saveReactionToFirebase(JournalReply reply, String emoji) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        DocumentReference replyRef = FirebaseFirestore.getInstance()
                .collection("journals")
                .document(docId)
                .collection("replies")
                .document(reply.getReplyId());

        String existingEmoji = null;
        if (reply.getReactions() != null) {
            existingEmoji = reply.getReactions().get(currentUid);
        }

        if (emoji.equals(existingEmoji)) {
            replyRef.update("reactions." + currentUid, com.google.firebase.firestore.FieldValue.delete())
                    .addOnSuccessListener(aVoid -> {
                    });
        } else {
            replyRef.update("reactions." + currentUid, emoji);
        }
    }

    // ==========================================
    // NOTIFICATION & PIPEDREAM LOGIC
    // ==========================================

    private void fetchPartnerId() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseFirestore.getInstance().collection("users").document(currentUserId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists() && documentSnapshot.contains("partnerId")) {
                        partnerId = documentSnapshot.getString("partnerId");
                        // START LISTENING only after we have the partnerId
                        listenToPartnerActivity();
                    }
                });
    }

    private void notifyPartner(String title, String message) {
        PartnerNotifier.notifyPartner(partnerId, title, message, "journal_chat", docId, BuildConfig.PIPEDREAM_URL);
    }

    // ==========================================
    // UNREAD BADGE LOGIC
    // ==========================================
    private void markJournalAsRead() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;

        String currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String currentDocId = getIntent().getStringExtra("docId");
        String authorId = getIntent().getStringExtra("authorId");

        // If the journal exists, and I am NOT the person who wrote it, mark it as read!
        if (currentDocId != null && authorId != null && !currentUid.equals(authorId)) {
            FirebaseFirestore.getInstance()
                    .collection("journal_entries")
                    .document(currentDocId)
                    .update("readByPartner", true)
                    .addOnFailureListener(e -> android.util.Log.e("JournalDetail", "Failed to mark as read", e));
        }
    }
}
