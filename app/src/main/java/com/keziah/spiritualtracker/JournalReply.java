package com.keziah.spiritualtracker;

import com.google.firebase.Timestamp;
import java.util.HashMap;
import java.util.Map;

public class JournalReply {
    private String replyId;
    private String journalId; // Ties it to the specific entry
    private String senderId;
    private String message;
    private Timestamp timestamp;

    // --- AUDIO FEATURES ---
    private String type; // "text" or "audio"
    private String audioUrl;
    private String duration;

    // --- SWIPE-TO-REPLY FEATURES ---
    // (Names updated to exactly match the Firestore keys from JournalDetailActivity)
    private String quotedMessageId;
    private String quotedMessageText;

    // Premium features
    private String status; // "sent", "delivered", "read"
    private Map<String, String> reactions; // e.g., {"user123": "❤️"}

    // Required empty constructor for Firebase
    public JournalReply() {
        reactions = new HashMap<>();
    }

    public JournalReply(String replyId, String journalId, String senderId, String message, Timestamp timestamp, String type) {
        this.replyId = replyId;
        this.journalId = journalId;
        this.senderId = senderId;
        this.message = message;
        this.timestamp = timestamp;
        this.type = type;
        this.status = "sent"; // Default status
        this.reactions = new HashMap<>();
    }

    // --- GETTERS ---
    public String getReplyId() { return replyId; }
    public String getJournalId() { return journalId; }
    public String getSenderId() { return senderId; }
    public String getMessage() { return message; }
    public Timestamp getTimestamp() { return timestamp; }
    public String getType() { return type; }
    public String getAudioUrl() { return audioUrl; }
    public String getDuration() { return duration; }
    public String getQuotedMessageId() { return quotedMessageId; }
    public String getQuotedMessageText() { return quotedMessageText; }
    public String getStatus() { return status; }
    public Map<String, String> getReactions() { return reactions; }

    // --- SETTERS ---
    public void setReplyId(String replyId) { this.replyId = replyId; }
    public void setJournalId(String journalId) { this.journalId = journalId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public void setMessage(String message) { this.message = message; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }
    public void setType(String type) { this.type = type; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public void setDuration(String duration) { this.duration = duration; }
    public void setQuotedMessageId(String quotedMessageId) { this.quotedMessageId = quotedMessageId; }
    public void setQuotedMessageText(String quotedMessageText) { this.quotedMessageText = quotedMessageText; }
    public void setStatus(String status) { this.status = status; }
    public void setReactions(Map<String, String> reactions) { this.reactions = reactions; }

    // Helper method to add a reaction easily
    public void addReaction(String userId, String emoji) {
        if (this.reactions == null) {
            this.reactions = new HashMap<>();
        }
        this.reactions.put(userId, emoji);
    }

    private String quotedMessageSenderId; // Add this variable

    // Add this Getter
    public String getQuotedMessageSenderId() {
        return quotedMessageSenderId;
    }

    // Add this Setter
    public void setQuotedMessageSenderId(String quotedMessageSenderId) {
        this.quotedMessageSenderId = quotedMessageSenderId;
    }

    // --- TEMPORARY FLAG FOR THE DIVIDER ---
    // (This doesn't get saved to Firebase, it just helps the adapter know where to draw the line)
    private boolean isFirstUnread = false;

    public boolean isFirstUnread() {
        return isFirstUnread;
    }

    public void setFirstUnread(boolean firstUnread) {
        isFirstUnread = firstUnread;
    }
}