package com.keziah.spiritualtracker;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.Filter;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ConnectionFragment extends Fragment {

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private String currentUserId;
    private String partnerId = "";
    private String myName = "Your partner";

    // Top Cards UI
    private CheckBox cbMyPrayedToday, cbMyPrayedPartner;
    private TextView tvPartnerNameStatus;
    private CheckBox cbPartnerPrayedToday, cbPartnerPrayedPartner;
    private FrameLayout btnNudgePartner;

    // Chat Feed UI
    private RecyclerView rvConnectionFeed;
    private JournalReplyAdapter chatAdapter;
    private List<JournalReply> chatList;

    // Input UI
    private EditText etMessageInput;
    private FloatingActionButton btnSendOrRecord;

    // --- SWIPE TO REPLY UI ---
    private LinearLayout layoutMessagePreview;
    private TextView tvMessagePreviewName, tvMessagePreviewText;
    private ImageButton btnClosePreview;
    private JournalReply selectedReplyForQuoting = null;
    private String editingReplyId = null;

    // --- TYPING INDICATOR VARIABLES ---
    private TextView tvTypingIndicator;
    private ListenerRegistration typingListener;
    private ListenerRegistration partnerStatusListener;
    private ListenerRegistration chatFeedListener;
    private ListenerRegistration chatReceivedListener;
    private List<DocumentSnapshot> sentDocs = new ArrayList<>();
    private List<DocumentSnapshot> receivedDocs = new ArrayList<>();

    /** Stops us from stacking duplicate chat listeners when the user doc updates often. */
    private String chatFeedPartnerId = null;
    private String typingPartnerId = null;
    private String partnerStatusPartnerId = null;
    private ListenerRegistration selfListener;
    private boolean isCurrentlyTyping = false;
    private Handler typingHandler = new Handler();
    private Runnable typingTimeoutRunnable = () -> {
        isCurrentlyTyping = false;
        updateTypingStatus("");
    };

    // --- READ RECEIPTS / DIVIDER VARIABLES ---
    private String firstUnreadMessageId = null;
    private boolean hasScrolledToUnread = false;

    // --- AUDIO RECORDING VARIABLES ---
    private static final String UPLOAD_PRESET = BuildConfig.CLOUDINARY_UPLOAD_PRESET;
    private LinearLayout layoutRecordingUI;
    private TextView tvRecordTimer;
    private ImageButton btnDiscardAudio;
    private ImageButton btnSendAudio;

    private AudioRecorder audioRecorder;
    private String localAudioPath = null;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean isTyping = false;

    private Handler timerHandler = new Handler();
    private int secondsElapsed = 0;
    private String currentDurationStr = "00:00";

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

    public ConnectionFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_connection, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        if (mAuth.getCurrentUser() != null) {
            currentUserId = mAuth.getCurrentUser().getUid();
        }
        audioRecorder = new AudioRecorder(this.requireContext());

        cbMyPrayedToday = view.findViewById(R.id.cbMyPrayedToday);
        cbMyPrayedPartner = view.findViewById(R.id.cbMyPrayedPartner);
        tvPartnerNameStatus = view.findViewById(R.id.tvPartnerNameStatus);
        cbPartnerPrayedToday = view.findViewById(R.id.cbPartnerPrayedToday);
        cbPartnerPrayedPartner = view.findViewById(R.id.cbPartnerPrayedPartner);
        btnNudgePartner = view.findViewById(R.id.btnNudgePartner);

        rvConnectionFeed = view.findViewById(R.id.rvConnectionFeed);
        etMessageInput = view.findViewById(R.id.etMessageInput);
        btnSendOrRecord = view.findViewById(R.id.btnSendOrRecord);

        // Preview & Typing UI
        layoutMessagePreview = view.findViewById(R.id.layoutMessagePreview);
        tvMessagePreviewName = view.findViewById(R.id.tvMessagePreviewName);
        tvMessagePreviewText = view.findViewById(R.id.tvMessagePreviewText);
        btnClosePreview = view.findViewById(R.id.btnClosePreview);
        tvTypingIndicator = view.findViewById(R.id.tvTypingIndicator);

        if (btnClosePreview != null) btnClosePreview.setOnClickListener(v -> clearReplyPreview());

        // Audio UI Elements
        layoutRecordingUI = view.findViewById(R.id.layoutRecordingUI);
        tvRecordTimer = view.findViewById(R.id.tvRecordTimer);
        btnDiscardAudio = view.findViewById(R.id.btnDiscardAudio);
        btnSendAudio = view.findViewById(R.id.btnSendAudio);

        // Setup RecyclerView & Adapter Listeners
        chatList = new ArrayList<>();
        chatAdapter = new JournalReplyAdapter(chatList);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        rvConnectionFeed.setLayoutManager(layoutManager);
        rvConnectionFeed.setAdapter(chatAdapter);

        // --- PREMIUM ADAPTER LISTENERS ---
        chatAdapter.setOnReactionClickListener((reply, emoji) -> saveReactionToFirebase(reply, emoji));

        chatAdapter.setOnMessageActionListener(new JournalReplyAdapter.OnMessageActionClickListener() {
            @Override
            public void onEditClick(JournalReply reply) { startEditingMessage(reply); }
            @Override
            public void onDeleteClick(JournalReply reply) { deleteMessage(reply); }
        });

        chatAdapter.setOnQuoteClickListener(quotedMessageId -> {
            for (int i = 0; i < chatList.size(); i++) {
                if (chatList.get(i).getReplyId() != null && chatList.get(i).getReplyId().equals(quotedMessageId)) {
                    rvConnectionFeed.smoothScrollToPosition(i);
                    chatAdapter.setHighlightedMessage(quotedMessageId);
                    break;
                }
            }
        });

        setupSwipeToReply();
        setupInputLogic();
        loadUserDataAndListen();
    }

    private void setupInputLogic() {
        etMessageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                boolean hasText = s.toString().trim().length() > 0;

                // Toggle Send/Mic Button
                if (hasText && !isTyping) {
                    btnSendOrRecord.setImageResource(android.R.drawable.ic_menu_send);
                    isTyping = true;
                } else if (!hasText && isTyping) {
                    btnSendOrRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
                    isTyping = false;
                }

                // Handle Typing Indicator for Partner
                if (hasText) {
                    if (!isCurrentlyTyping) {
                        isCurrentlyTyping = true;
                        updateTypingStatus("prayer_chat");
                    }
                    typingHandler.removeCallbacks(typingTimeoutRunnable);
                    typingHandler.postDelayed(typingTimeoutRunnable, 3000);
                } else {
                    if (isCurrentlyTyping) {
                        isCurrentlyTyping = false;
                        updateTypingStatus("");
                        typingHandler.removeCallbacks(typingTimeoutRunnable);
                    }
                }
            }
        });

        btnSendOrRecord.setOnClickListener(v -> {
            if (isTyping) {
                String text = etMessageInput.getText().toString().trim();
                if (!text.isEmpty()) {
                    sendTextMessage(text);
                }
            } else {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    toggleRecording();
                } else {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 200);
                }
            }
            updateTypingStatus("");
            isCurrentlyTyping = false;
        });

        btnDiscardAudio.setOnClickListener(v -> discardAudio());
        btnSendAudio.setOnClickListener(v -> sendAudioMessage());

        cbMyPrayedToday.setOnClickListener(v -> updateMyCheckbox("prayedTodayDate", cbMyPrayedToday.isChecked()));
        cbMyPrayedPartner.setOnClickListener(v -> updateMyCheckbox("prayedForPartnerDate", cbMyPrayedPartner.isChecked()));

        btnNudgePartner.setOnClickListener(v -> {
            incrementPartnerUnread("unreadPrayer");
            notifyPartner("Gentle Nudge", myName + " is gently reminding you to pray! 🙏", "prayer_nudge");
            Toast.makeText(getContext(), "Nudge sent!", Toast.LENGTH_SHORT).show();
        });
    }

    // ==========================================
    // SWIPE, EDIT, DELETE & TYPING LOGIC
    // ==========================================

    private void updateTypingStatus(String status) {
        if (currentUserId != null) {
            db.collection("users").document(currentUserId)
                    .set(java.util.Collections.singletonMap("typingInPrayer", status),
                            com.google.firebase.firestore.SetOptions.merge());
        }
    }

    private void updateRecordingStatus(String status) {
        if (currentUserId != null) {
            db.collection("users").document(currentUserId)
                    .set(java.util.Collections.singletonMap("recordingInPrayer", status),
                            com.google.firebase.firestore.SetOptions.merge());
        }
    }

    private void listenToPartnerActivity() {
        if (partnerId == null || partnerId.isEmpty()) return;
        if (typingListener != null && partnerId.equals(typingPartnerId)) return;
        if (typingListener != null) {
            typingListener.remove();
            typingListener = null;
        }
        typingPartnerId = partnerId;

        typingListener = db.collection("users").document(partnerId).addSnapshotListener((doc, error) -> {
            if (error != null || doc == null || !doc.exists()) return;

            String typingIn = doc.getString("typingInPrayer");
            String recordingIn = doc.getString("recordingInPrayer");

            if (recordingIn != null && recordingIn.equals("prayer_chat")) {
                tvTypingIndicator.setText("Partner is recording audio...");
                tvTypingIndicator.setVisibility(View.VISIBLE);
            } else if (typingIn != null && typingIn.equals("prayer_chat")) {
                tvTypingIndicator.setText("Partner is typing...");
                tvTypingIndicator.setVisibility(View.VISIBLE);
            } else {
                tvTypingIndicator.setVisibility(View.GONE);
            }
        });
    }

    private void setupSwipeToReply() {
        ItemTouchHelper.SimpleCallback touchHelperCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) { return false; }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                if (position == RecyclerView.NO_POSITION || position >= chatList.size()) return;
                selectedReplyForQuoting = chatList.get(position);
                editingReplyId = null;

                if (layoutMessagePreview != null) {
                    layoutMessagePreview.setVisibility(View.VISIBLE);
                    boolean isMyMessage = (selectedReplyForQuoting.getSenderId() != null && selectedReplyForQuoting.getSenderId().equals(currentUserId));
                    tvMessagePreviewName.setText(isMyMessage ? "You" : "Partner");

                    if ("audio".equals(selectedReplyForQuoting.getType())) {
                        tvMessagePreviewText.setText("🎤 Voice Note (" + selectedReplyForQuoting.getDuration() + ")");
                    } else {
                        tvMessagePreviewText.setText(selectedReplyForQuoting.getMessage());
                    }
                }
                chatAdapter.notifyItemChanged(position);
            }

            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    float maxSwipeDistance = recyclerView.getWidth() / 10.0f;
                    super.onChildDraw(c, recyclerView, viewHolder, Math.min(dX, maxSwipeDistance), dY, actionState, isCurrentlyActive);
                } else {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                }
            }
        };
        new ItemTouchHelper(touchHelperCallback).attachToRecyclerView(rvConnectionFeed);
    }

    private void clearReplyPreview() {
        if (layoutMessagePreview != null) layoutMessagePreview.setVisibility(View.GONE);
        selectedReplyForQuoting = null;
        if (editingReplyId != null) {
            editingReplyId = null;
            etMessageInput.setText("");
        }
    }

    private void startEditingMessage(JournalReply reply) {
        editingReplyId = reply.getReplyId();
        selectedReplyForQuoting = null;
        etMessageInput.setText(reply.getMessage());
        etMessageInput.setSelection(etMessageInput.getText().length());
        if (layoutMessagePreview != null) {
            layoutMessagePreview.setVisibility(View.VISIBLE);
            tvMessagePreviewName.setText("✏️ Editing Message");
            tvMessagePreviewText.setText(reply.getMessage());
        }
    }

    private void deleteMessage(JournalReply reply) {
        db.collection("prayer_messages").document(reply.getReplyId()).delete()
                .addOnSuccessListener(aVoid -> Toast.makeText(getContext(), "Message deleted", Toast.LENGTH_SHORT).show());
    }

    // ==========================================
    // AUDIO RECORDING & UPLOAD LOGIC
    // ==========================================

    private void toggleRecording() {
        if (!isRecording && !isPaused) {
            java.io.File cacheDir = requireContext().getExternalCacheDir();
            if (cacheDir == null) cacheDir = requireContext().getCacheDir();
            localAudioPath = cacheDir.getAbsolutePath() + "/chat_vn_" + System.currentTimeMillis() + ".m4a";
            audioRecorder.startRecording(localAudioPath);
            updateRecordingStatus("prayer_chat");
            isRecording = true;
            isPaused = false;
            secondsElapsed = 0;

            etMessageInput.setVisibility(View.INVISIBLE);
            layoutRecordingUI.setVisibility(View.VISIBLE);
            tvRecordTimer.setText("00:00");
            timerHandler.postDelayed(timerRunnable, 1000);

            updateUIForRecording();
        } else if (isRecording) {
            audioRecorder.pauseRecording();
            updateRecordingStatus("");
            isRecording = false;
            isPaused = true;

            timerHandler.removeCallbacks(timerRunnable);
            updateUIForPaused();
        } else if (isPaused) {
            audioRecorder.resumeRecording();
            updateRecordingStatus("prayer_chat");
            isRecording = true;
            isPaused = false;

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
        if (isRecording || isPaused) {
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
        isPaused = false;
        secondsElapsed = 0;
        if (timerHandler != null) {
            timerHandler.removeCallbacks(timerRunnable);
        }

        layoutRecordingUI.setVisibility(View.GONE);
        etMessageInput.setVisibility(View.VISIBLE);
        btnSendOrRecord.setImageResource(android.R.drawable.ic_btn_speak_now);
        btnSendOrRecord.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#673AB7")));
    }

    private void sendAudioMessage() {
        if (isRecording || isPaused) {
            audioRecorder.stopRecording();
            updateRecordingStatus("");
            isRecording = false;
            isPaused = false;
            timerHandler.removeCallbacks(timerRunnable);
        }

        if (localAudioPath == null || currentUserId == null || partnerId == null || partnerId.isEmpty()) return;

        Toast.makeText(getContext(), "Uploading Voice Note...", Toast.LENGTH_SHORT).show();
        btnSendAudio.setEnabled(false);

        MediaManager.get().upload(localAudioPath)
                .unsigned(UPLOAD_PRESET)
                .option("resource_type", "auto")
                .callback(new UploadCallback() {
                    @Override public void onStart(String requestId) {}
                    @Override public void onProgress(String requestId, long bytes, long totalBytes) {}

                    @Override
                    public void onSuccess(String requestId, Map resultData) {
                        String uploadedAudioUrl = (String) resultData.get("secure_url");

                        Map<String, Object> messageData = new HashMap<>();
                        messageData.put("senderId", currentUserId);
                        messageData.put("receiverId", partnerId);
                        messageData.put("type", "audio");
                        messageData.put("audioUrl", uploadedAudioUrl);
                        messageData.put("duration", currentDurationStr);
                        messageData.put("timestamp", FieldValue.serverTimestamp());
                        messageData.put("status", "sent");

                        if (selectedReplyForQuoting != null) {
                            messageData.put("quotedMessageId", selectedReplyForQuoting.getReplyId());
                            messageData.put("quotedMessageSenderId", selectedReplyForQuoting.getSenderId());
                            messageData.put("quotedMessageText", "audio".equals(selectedReplyForQuoting.getType()) ? "🎤 Voice Note" : selectedReplyForQuoting.getMessage());
                            if (getActivity() != null) getActivity().runOnUiThread(() -> clearReplyPreview());
                        }

                        DocumentReference newRef = db.collection("prayer_messages").document();
                        messageData.put("replyId", newRef.getId());

                        newRef.set(messageData).addOnSuccessListener(doc -> {
                            incrementPartnerUnread("unreadPrayer");
                            notifyPartner("New Voice Note", myName + " sent you a voice note 🎤", "prayer_chat");
                            discardAudio();
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(() -> btnSendAudio.setEnabled(true));
                            }
                        });
                    }

                    @Override
                    public void onError(String requestId, ErrorInfo errorInfo) {
                        if (getActivity() != null) getActivity().runOnUiThread(() -> {
                            Toast.makeText(getContext(), "Upload Error", Toast.LENGTH_LONG).show();
                            btnSendAudio.setEnabled(true);
                        });
                    }

                    @Override public void onReschedule(String requestId, ErrorInfo errorInfo) {}
                }).dispatch();
    }

    // ==========================================
    // SENDING TEXT & FIREBASE DATA LISTENERS
    // ==========================================

    private void sendTextMessage(String text) {
        if (currentUserId == null || partnerId == null || partnerId.isEmpty()) return;

        if (editingReplyId != null) {
            db.collection("prayer_messages").document(editingReplyId)
                    .update("message", text, "isEdited", true)
                    .addOnSuccessListener(aVoid -> {
                        etMessageInput.setText("");
                        clearReplyPreview();
                    })
                    .addOnFailureListener(e -> Toast.makeText(getContext(), "Failed to edit", Toast.LENGTH_SHORT).show());
        } else {
            Map<String, Object> messageData = new HashMap<>();
            messageData.put("senderId", currentUserId);
            messageData.put("receiverId", partnerId);
            messageData.put("type", "text");
            messageData.put("message", text);
            messageData.put("timestamp", FieldValue.serverTimestamp());
            messageData.put("status", "sent");

            if (selectedReplyForQuoting != null) {
                messageData.put("quotedMessageId", selectedReplyForQuoting.getReplyId());
                messageData.put("quotedMessageSenderId", selectedReplyForQuoting.getSenderId());
                messageData.put("quotedMessageText", "audio".equals(selectedReplyForQuoting.getType()) ? "🎤 Voice Note" : selectedReplyForQuoting.getMessage());
            }

            DocumentReference newRef = db.collection("prayer_messages").document();
            messageData.put("replyId", newRef.getId());

            newRef.set(messageData).addOnSuccessListener(doc -> {
                etMessageInput.setText("");
                clearReplyPreview();
                incrementPartnerUnread("unreadPrayer");
                notifyPartner("New Message", myName + ": " + text, "prayer_chat");
            });
        }
    }

    private void loadUserDataAndListen() {
        if (currentUserId == null) return;

        selfListener = db.collection("users").document(currentUserId).addSnapshotListener((documentSnapshot, e) -> {
            if (e != null || documentSnapshot == null || !documentSnapshot.exists()) return;

            myName = UserProfile.displayName(documentSnapshot);

            String today = ActivityLog.today();

            
            // Date-based Checkbox Reset
            String prayedTodayDate = documentSnapshot.getString("prayedTodayDate");
            String prayedForPartnerDate = documentSnapshot.getString("prayedForPartnerDate");

            cbMyPrayedToday.setChecked(today.equals(prayedTodayDate));
            cbMyPrayedPartner.setChecked(today.equals(prayedForPartnerDate));

            String newPartnerId = documentSnapshot.getString("partnerId");
            partnerId = newPartnerId != null ? newPartnerId : "";
            if (!partnerId.isEmpty()) {
                listenToPartnerStatus();
                listenToPartnerActivity();
                listenToChatFeed();
            } else {
                stopPartnerStatusListener();
                stopPartnerActivityListener();
        stopChatFeed();
            }
        });
    }

    private void stopPartnerStatusListener() {
        if (partnerStatusListener != null) {
            partnerStatusListener.remove();
            partnerStatusListener = null;
        }
        partnerStatusPartnerId = null;
        if (tvPartnerNameStatus != null) {
            tvPartnerNameStatus.setText("Partner's Status");
        }
        if (cbPartnerPrayedToday != null) cbPartnerPrayedToday.setChecked(false);
        if (cbPartnerPrayedPartner != null) cbPartnerPrayedPartner.setChecked(false);
        if (btnNudgePartner != null) btnNudgePartner.setVisibility(View.VISIBLE);
    }

    private void stopPartnerActivityListener() {
        if (typingListener != null) {
            typingListener.remove();
            typingListener = null;
        }
        typingPartnerId = null;
        if (tvTypingIndicator != null) tvTypingIndicator.setVisibility(View.GONE);
    }

    private void stopChatFeed() {
        if (chatFeedListener != null) {
            chatFeedListener.remove();
            chatFeedListener = null;
        }
        if (chatReceivedListener != null) {
            chatReceivedListener.remove();
            chatReceivedListener = null;
        }
        sentDocs = new ArrayList<>();
        receivedDocs = new ArrayList<>();

        chatFeedPartnerId = null;
        hasScrolledToUnread = false;
        firstUnreadMessageId = null;
        if (chatList != null) {
            chatList.clear();
            if (chatAdapter != null) {
                chatAdapter.notifyDataSetChanged();
            }
        }
    }

    private void updateMyCheckbox(String field, boolean isChecked) {
        if (currentUserId == null) return;
        String today = ActivityLog.today();
        if ("prayedTodayDate".equals(field)) ActivityLog.setPrayed(currentUserId, isChecked);
        else ActivityLog.setPrayedForPartner(currentUserId, isChecked);

        Map<String, Object> data = new HashMap<>();
        data.put(field, isChecked ? today : "");
        db.collection("users").document(currentUserId).set(data, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(aVoid -> {
                    if (isChecked) {
                        incrementPartnerUnread("unreadPrayer");
                        String msg = field.contains("Today") ? myName + " has prayed today!" : myName + " prayed for you today ❤️";
                        notifyPartner("Prayer Update", msg, "prayer_update");
                    }
                });
    }

    private void listenToPartnerStatus() {
        if (partnerId == null || partnerId.isEmpty()) return;
        if (partnerStatusListener != null && partnerId.equals(partnerStatusPartnerId)) return;
        if (partnerStatusListener != null) {
            partnerStatusListener.remove();
            partnerStatusListener = null;
        }
        partnerStatusPartnerId = partnerId;

        partnerStatusListener = db.collection("users").document(partnerId).addSnapshotListener((snapshot, error) -> {
            if (error != null || snapshot == null || !snapshot.exists()) return;

            tvPartnerNameStatus.setText(UserProfile.partnerName(snapshot) + "'s Status");

            String today = ActivityLog.today();

            
            boolean hasPartnerPrayedToday = today.equals(snapshot.getString("prayedTodayDate"));
            boolean hasPartnerPrayedForMe = today.equals(snapshot.getString("prayedForPartnerDate"));

            cbPartnerPrayedToday.setChecked(hasPartnerPrayedToday);
            cbPartnerPrayedPartner.setChecked(hasPartnerPrayedForMe);

            // NUDGE VISIBILITY Logic
            if (hasPartnerPrayedToday && hasPartnerPrayedForMe) {
                btnNudgePartner.setVisibility(View.GONE);
            } else {
                btnNudgePartner.setVisibility(View.VISIBLE);
            }
        });
    }

    private void listenToChatFeed() {
        if (currentUserId == null || partnerId == null || partnerId.isEmpty()) {
            stopChatFeed();
            return;
        }
        if (chatFeedListener != null && partnerId.equals(chatFeedPartnerId)) {
            return;
        }
        stopChatFeed();
        chatFeedPartnerId = partnerId;
        hasScrolledToUnread = false;
        firstUnreadMessageId = null;

        // Two plain equality queries instead of one OR + orderBy query. The OR form needs a
        // composite index; without it the listener fails and each person only ever saw their
        // own (locally cached) messages. Equality-only queries need no index; we sort here.
        sentDocs = new ArrayList<>();
        receivedDocs = new ArrayList<>();
        chatFeedListener = db.collection("prayer_messages")
                .whereEqualTo("senderId", currentUserId)
                .whereEqualTo("receiverId", partnerId)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) { Log.e("ConnectionFragment", "Sent messages listener failed", error); return; }
                    if (snapshots == null) return;
                    sentDocs = snapshots.getDocuments();
                    rebuildChat();
                });
        chatReceivedListener = db.collection("prayer_messages")
                .whereEqualTo("senderId", partnerId)
                .whereEqualTo("receiverId", currentUserId)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) { Log.e("ConnectionFragment", "Received messages listener failed", error); return; }
                    if (snapshots == null) return;
                    receivedDocs = snapshots.getDocuments();
                    rebuildChat();
                });
    }

    private void rebuildChat() {
        if (!isAdded() || rvConnectionFeed == null) return;

        List<JournalReply> merged = new ArrayList<>();
        for (List<DocumentSnapshot> docs : java.util.Arrays.asList(sentDocs, receivedDocs)) {
            for (DocumentSnapshot doc : docs) {
                JournalReply reply = doc.toObject(JournalReply.class);
                if (reply == null) continue;
                reply.setReplyId(doc.getId());
                merged.add(reply);
            }
        }
        // A message still being sent has no server timestamp yet; keep it at the bottom.
        java.util.Collections.sort(merged, (a, b) -> {
            long ta = a.getTimestamp() != null ? a.getTimestamp().toDate().getTime() : Long.MAX_VALUE;
            long tb = b.getTimestamp() != null ? b.getTimestamp().toDate().getTime() : Long.MAX_VALUE;
            return Long.compare(ta, tb);
        });

        LinearLayoutManager layoutManager = (LinearLayoutManager) rvConnectionFeed.getLayoutManager();
        int lastVisiblePosition = layoutManager != null ? layoutManager.findLastVisibleItemPosition() : -1;
        int totalItemsBefore = chatList.size();
        boolean wasAtBottom = (lastVisiblePosition >= totalItemsBefore - 2);

        chatList.clear();
        firstUnreadMessageId = null;
        int targetScrollIndex = -1;

        for (JournalReply reply : merged) {
            if (firstUnreadMessageId == null) {
                boolean isFromPartner = reply.getSenderId() != null && !reply.getSenderId().equals(currentUserId);
                if (isFromPartner && !"read".equals(reply.getStatus())) firstUnreadMessageId = reply.getReplyId();
            }
            if (firstUnreadMessageId != null && firstUnreadMessageId.equals(reply.getReplyId())) {
                reply.setFirstUnread(true);
                targetScrollIndex = chatList.size();
            } else {
                reply.setFirstUnread(false);
            }
            chatList.add(reply);
        }
        chatAdapter.notifyDataSetChanged();
        markIncomingMessagesAsRead(chatList);

        if (targetScrollIndex != -1 && !hasScrolledToUnread) {
            rvConnectionFeed.scrollToPosition(targetScrollIndex);
            hasScrolledToUnread = true;
        } else if (chatList.size() > totalItemsBefore) {
            JournalReply lastMessage = chatList.get(chatList.size() - 1);
            if ((lastMessage.getSenderId() != null && lastMessage.getSenderId().equals(currentUserId)) || wasAtBottom) {
                rvConnectionFeed.scrollToPosition(chatList.size() - 1);
            }
        }
    }

    private void markIncomingMessagesAsRead(List<JournalReply> loadedReplies) {
        if (currentUserId == null) return;
        for (JournalReply reply : loadedReplies) {
            if (reply.getSenderId() != null && !reply.getSenderId().equals(currentUserId)) {
                if (!"read".equals(reply.getStatus())) {
                    db.collection("prayer_messages").document(reply.getReplyId()).update("status", "read");
                }
            }
        }
    }

    private void saveReactionToFirebase(JournalReply reply, String emoji) {
        if (currentUserId == null) return;
        DocumentReference replyRef = db.collection("prayer_messages").document(reply.getReplyId());
        String existingEmoji = (reply.getReactions() != null) ? reply.getReactions().get(currentUserId) : null;
        if (emoji.equals(existingEmoji)) replyRef.update("reactions." + currentUserId, FieldValue.delete());
        else replyRef.update("reactions." + currentUserId, emoji);
    }

    private void incrementPartnerUnread(String field) {
        PartnerNotifier.incrementUnread(partnerId, field);
    }

    private void notifyPartner(String title, String body, String type) {
        PartnerNotifier.notifyPartner(partnerId, title, body, type, currentUserId, BuildConfig.PIPEDREAM_URL);
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPartnerActivityListener();
        stopPartnerStatusListener();
        stopChatFeed();
        if (selfListener != null) selfListener.remove();
        updateTypingStatus("");
        updateRecordingStatus("");
        if (isRecording || isPaused) audioRecorder.stopRecording();
        typingHandler.removeCallbacks(typingTimeoutRunnable);
        timerHandler.removeCallbacks(timerRunnable);
    }
}
