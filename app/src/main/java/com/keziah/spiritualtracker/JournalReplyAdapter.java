package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Toast;
import com.google.firebase.auth.FirebaseAuth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JournalReplyAdapter extends RecyclerView.Adapter<JournalReplyAdapter.ReplyViewHolder> {

    private List<JournalReply> replyList;
    private String currentUid;

    private MediaPlayer mediaPlayer;
    private int playingPosition = -1;
    private int lastPausedPosition = -1; 
    private Handler timerHandler = new Handler();
    private Runnable timerRunnable;
    private OkHttpClient okHttpClient = new OkHttpClient();
    private Set<Integer> downloadingPositions = new HashSet<>();

    // --- INTERFACES ---
    public interface OnReactionClickListener { void onReactionClick(JournalReply reply, String emoji); }
    private OnReactionClickListener reactionClickListener;
    public void setOnReactionClickListener(OnReactionClickListener listener) { this.reactionClickListener = listener; }

    public interface OnMessageActionClickListener { void onEditClick(JournalReply reply); void onDeleteClick(JournalReply reply); }
    private OnMessageActionClickListener messageActionListener;
    public void setOnMessageActionListener(OnMessageActionClickListener listener) { this.messageActionListener = listener; }

    public interface OnQuoteClickListener { void onQuoteClick(String quotedMessageId); }
    private OnQuoteClickListener quoteClickListener;
    public void setOnQuoteClickListener(OnQuoteClickListener listener) { this.quoteClickListener = listener; }

    private String highlightedMessageId = null;

    public void setHighlightedMessage(String messageId) {
        this.highlightedMessageId = messageId;
        notifyDataSetChanged();
        new Handler().postDelayed(() -> {
            this.highlightedMessageId = null;
            notifyDataSetChanged();
        }, 1500);
    }

    public JournalReplyAdapter(List<JournalReply> replyList) {
        this.replyList = replyList;
        this.currentUid = (FirebaseAuth.getInstance().getCurrentUser() != null) ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
    }

    @NonNull
    @Override
    public ReplyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_reply, parent, false);
        return new ReplyViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ReplyViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && "TIMER_UPDATE".equals(payloads.get(0))) {
            if (position == playingPosition && mediaPlayer != null) {
                int currentPos = mediaPlayer.getCurrentPosition();
                int totalDuration = mediaPlayer.getDuration();
                int mins = (currentPos / 1000) / 60;
                int secs = (currentPos / 1000) % 60;
                JournalReply reply = replyList.get(position);
                holder.tvReplyDuration.setText(String.format("%02d:%02d / %s", mins, secs, reply.getDuration()));
                holder.seekBarReply.setProgress(currentPos);
                holder.seekBarReply.setMax(totalDuration);
            }
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull ReplyViewHolder holder, int position) {
        JournalReply reply = replyList.get(position);
        Context context = holder.itemView.getContext();

        holder.tvUnreadDivider.setVisibility(reply.isFirstUnread() ? View.VISIBLE : View.GONE);
        holder.itemView.setBackgroundColor((reply.getReplyId() != null && reply.getReplyId().equals(highlightedMessageId)) ? Color.parseColor("#337E57C2") : Color.TRANSPARENT);

        boolean isMine = reply.getSenderId() != null && reply.getSenderId().equals(currentUid);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) holder.messageBubble.getLayoutParams();
        params.gravity = isMine ? Gravity.END : Gravity.START;
        holder.messageBubble.setLayoutParams(params);
        
        // --- ADAPTIVE PREMIUM COLORS ---
        int bubbleColor = Color.parseColor(isMine ? "#7E57C2" : "#F0F0F7"); // Softer Purple and cleaner grey
        int primaryTextColor = isMine ? Color.WHITE : Color.BLACK;
        int secondaryTextColor = isMine ? Color.parseColor("#E0E0E0") : Color.parseColor("#888888");
        int accentColor = Color.parseColor("#7E57C2");
        int iconTintColor = isMine ? accentColor : Color.WHITE;
        int btnBgColor = isMine ? Color.WHITE : accentColor;

        holder.messageBubble.setBackgroundTintList(ColorStateList.valueOf(bubbleColor));
        holder.tvReplyText.setTextColor(primaryTextColor);
        holder.tvReplyTime.setTextColor(secondaryTextColor);
        holder.tvReplyDuration.setTextColor(isMine ? Color.WHITE : accentColor);
        holder.btnPlayReplyAudio.setBackgroundTintList(ColorStateList.valueOf(btnBgColor));
        holder.btnPlayReplyAudio.setColorFilter(iconTintColor);
        
        holder.seekBarReply.setProgressTintList(ColorStateList.valueOf(isMine ? Color.WHITE : accentColor));
        holder.seekBarReply.setThumbTintList(ColorStateList.valueOf(isMine ? Color.WHITE : accentColor));
        holder.seekBarReply.setProgressBackgroundTintList(ColorStateList.valueOf(isMine ? Color.parseColor("#80FFFFFF") : Color.LTGRAY));

        if (isMine) {
            holder.ivReadReceipt.setVisibility(View.VISIBLE);
            holder.ivReadReceipt.setImageResource(R.drawable.ic_check_double);
            holder.ivReadReceipt.setColorFilter(Color.parseColor("read".equals(reply.getStatus()) ? "#4FC3F7" : "#E0E0E0"));
        } else {
            holder.ivReadReceipt.setVisibility(View.GONE);
        }

        // --- QUOTED MESSAGE LOGIC ---
        if (reply.getQuotedMessageText() != null && !reply.getQuotedMessageText().isEmpty()) {
            holder.layoutQuotedMessage.setVisibility(View.VISIBLE);
            holder.tvQuotedText.setText(reply.getQuotedMessageText());
            String quotedSenderId = reply.getQuotedMessageSenderId();
            holder.tvQuotedSender.setText(quotedSenderId != null && quotedSenderId.equals(currentUid) ? "You" : "Partner");

            if (isMine) {
                holder.tvQuotedSender.setTextColor(Color.parseColor("#D1C4E9")); // Light Lavender
                holder.tvQuotedText.setTextColor(Color.parseColor("#F5F5F5"));
            } else {
                holder.tvQuotedSender.setTextColor(accentColor);
                holder.tvQuotedText.setTextColor(Color.parseColor("#555555"));
            }

            holder.layoutQuotedMessage.setOnClickListener(v -> {
                if (quoteClickListener != null && reply.getQuotedMessageId() != null) {
                    quoteClickListener.onQuoteClick(reply.getQuotedMessageId());
                }
            });
        } else {
            holder.layoutQuotedMessage.setVisibility(View.GONE);
        }

        // --- EMOJI REACTIONS ---
        if (reply.getReactions() != null && !reply.getReactions().isEmpty()) {
            holder.tvReaction.setVisibility(View.VISIBLE);
            String firstEmoji = reply.getReactions().values().iterator().next();
            holder.tvReaction.setText(firstEmoji);
            
            if (isMine) {
                holder.tvReaction.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            } else {
                holder.tvReaction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D1C4E9")));
            }
        } else {
            holder.tvReaction.setVisibility(View.GONE);
        }

        if (reply.getTimestamp() != null) {
            String timeAgo = TimeAgoUtil.getTimeAgo(reply.getTimestamp().toDate().getTime());
            holder.tvReplyTime.setText(timeAgo);
        } else {
            holder.tvReplyTime.setText("Just now");
        }

        if ("audio".equals(reply.getType())) {
            holder.tvReplyText.setVisibility(View.GONE);
            holder.layoutReplyAudio.setVisibility(View.VISIBLE);

            File cacheFile = getCacheFile(context, reply.getAudioUrl());
            boolean isDownloaded = cacheFile.exists() || isMine;
            boolean isDownloading = downloadingPositions.contains(position);

            if (isDownloading) {
                holder.btnPlayReplyAudio.setVisibility(View.INVISIBLE);
                holder.pbDownload.setVisibility(View.VISIBLE);
                holder.pbDownload.setIndeterminateTintList(ColorStateList.valueOf(isMine ? Color.WHITE : accentColor));
            } else {
                holder.btnPlayReplyAudio.setVisibility(View.VISIBLE);
                holder.pbDownload.setVisibility(View.GONE);

                if (!isDownloaded) {
                    holder.btnPlayReplyAudio.setImageResource(android.R.drawable.stat_sys_download);
                } else {
                    boolean isThisPlayingOrPaused = (playingPosition == position);
                    if (isThisPlayingOrPaused && mediaPlayer != null) {
                        holder.btnPlayReplyAudio.setImageResource(mediaPlayer.isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
                        int currentPos = mediaPlayer.getCurrentPosition();
                        holder.seekBarReply.setMax(mediaPlayer.getDuration());
                        holder.seekBarReply.setProgress(currentPos);
                        int mins = (currentPos / 1000) / 60;
                        int secs = (currentPos / 1000) % 60;
                        holder.tvReplyDuration.setText(String.format("%02d:%02d / %s", mins, secs, reply.getDuration()));
                    } else {
                        holder.btnPlayReplyAudio.setImageResource(android.R.drawable.ic_media_play);
                        holder.seekBarReply.setProgress(0);
                        holder.tvReplyDuration.setText(reply.getDuration());
                    }
                }
            }

            holder.btnPlayReplyAudio.setOnClickListener(v -> {
                if (!isDownloaded) {
                    downloadAndPlay(position, reply.getAudioUrl(), cacheFile);
                } else {
                    if (playingPosition == position && mediaPlayer != null) {
                        if (mediaPlayer.isPlaying()) pauseAudio();
                        else resumeAudio();
                    } else {
                        playAudio(position, isMine ? reply.getAudioUrl() : cacheFile.getAbsolutePath());
                    }
                }
            });

            holder.seekBarReply.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && playingPosition == position && mediaPlayer != null) {
                        mediaPlayer.seekTo(progress);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

        } else {
            holder.tvReplyText.setVisibility(View.VISIBLE);
            holder.layoutReplyAudio.setVisibility(View.GONE);
            holder.tvReplyText.setText(reply.getMessage());
        }

        holder.messageBubble.setOnLongClickListener(v -> { showEmojiPopup(v, reply); return true; });
    }

    private File getCacheFile(Context context, String url) {
        if (url == null) return new File("");
        return new File(context.getCacheDir(), "vn_" + Math.abs(url.hashCode()) + ".m4a");
    }

    private void downloadAndPlay(int position, String url, File target) {
        downloadingPositions.add(position);
        notifyItemChanged(position);

        Request request = new Request.Builder().url(url).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    downloadingPositions.remove(position);
                    notifyItemChanged(position);
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) return;
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) { fos.write(buffer, 0, read); }
                    new Handler(Looper.getMainLooper()).post(() -> {
                        downloadingPositions.remove(position);
                        playAudio(position, target.getAbsolutePath());
                    });
                }
            }
        });
    }

    private void playAudio(int position, String path) {
        stopAudio();
        playingPosition = position;
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                startTimer();
                notifyItemChanged(playingPosition);
            });
            mediaPlayer.setOnCompletionListener(mp -> stopAudio());
        } catch (IOException e) {
            playingPosition = -1;
        }
    }

    private void pauseAudio() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            lastPausedPosition = mediaPlayer.getCurrentPosition();
            stopTimer();
            notifyItemChanged(playingPosition);
        }
    }

    private void resumeAudio() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            startTimer();
            notifyItemChanged(playingPosition);
        }
    }

    public void stopAudio() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        lastPausedPosition = -1;
        stopTimer();
        int oldPos = playingPosition;
        playingPosition = -1;
        if (oldPos != -1) notifyItemChanged(oldPos);
    }

    private void startTimer() {
        stopTimer();
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    notifyItemChanged(playingPosition, "TIMER_UPDATE");
                    timerHandler.postDelayed(this, 500);
                }
            }
        };
        timerHandler.post(timerRunnable);
    }

    private void stopTimer() { if (timerRunnable != null) timerHandler.removeCallbacks(timerRunnable); }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) { super.onDetachedFromRecyclerView(recyclerView); stopAudio(); }

    private void showEmojiPopup(View anchorView, JournalReply reply) {
        View popupView = LayoutInflater.from(anchorView.getContext()).inflate(R.layout.layout_emoji_picker, null);
        android.widget.PopupWindow popupWindow = new android.widget.PopupWindow(popupView, -2, -2, true);
        popupWindow.setElevation(15f);

        String myCurrentEmoji = (reply.getReactions() != null) ? reply.getReactions().get(currentUid) : null;
        int[] ids = {R.id.emojiHeart, R.id.emojiLaugh, R.id.emojiSurprise, R.id.emojiCry, R.id.emojiPray, R.id.emojiThumbsUp};
        for (int id : ids) {
            TextView ev = popupView.findViewById(id);
            if (ev.getText().toString().equals(myCurrentEmoji)) ev.setBackgroundResource(R.drawable.bg_circle_purple);
            ev.setOnClickListener(v -> { if (reactionClickListener != null) reactionClickListener.onReactionClick(reply, ev.getText().toString()); popupWindow.dismiss(); });
        }

        boolean mine = reply.getSenderId() != null && reply.getSenderId().equals(currentUid);
        popupView.findViewById(R.id.layoutMessageActions).setVisibility(mine ? View.VISIBLE : View.GONE);
        if (mine) {
            popupView.findViewById(R.id.btnEditMessage).setVisibility("text".equals(reply.getType()) ? View.VISIBLE : View.GONE);
            popupView.findViewById(R.id.btnEditMessage).setOnClickListener(v -> { if (messageActionListener != null) messageActionListener.onEditClick(reply); popupWindow.dismiss(); });
            popupView.findViewById(R.id.btnDeleteMessage).setOnClickListener(v -> { if (messageActionListener != null) messageActionListener.onDeleteClick(reply); popupWindow.dismiss(); });
        }
        popupView.measure(0, 0);
        popupWindow.showAsDropDown(anchorView, (anchorView.getWidth()/2)-(popupView.getMeasuredWidth()/2), -(anchorView.getHeight()+popupView.getMeasuredHeight()+10));
    }

    @Override public int getItemCount() { return replyList.size(); }

    public static class ReplyViewHolder extends RecyclerView.ViewHolder {
        TextView tvReplyText, tvReplyDuration, tvReplyTime, tvReaction, tvUnreadDivider, tvQuotedSender, tvQuotedText;
        LinearLayout layoutReplyAudio, messageBubble, layoutQuotedMessage;
        ImageButton btnPlayReplyAudio;
        ImageView ivReadReceipt;
        ProgressBar pbDownload;
        SeekBar seekBarReply;

        public ReplyViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUnreadDivider = itemView.findViewById(R.id.tvUnreadDivider);
            tvReplyText = itemView.findViewById(R.id.tvReplyText);
            layoutReplyAudio = itemView.findViewById(R.id.layoutReplyAudio);
            btnPlayReplyAudio = itemView.findViewById(R.id.btnPlayReplyAudio);
            pbDownload = itemView.findViewById(R.id.pbDownload);
            seekBarReply = itemView.findViewById(R.id.seekBarReply);
            tvReplyDuration = itemView.findViewById(R.id.tvReplyDuration);
            tvReplyTime = itemView.findViewById(R.id.tvReplyTime);
            messageBubble = itemView.findViewById(R.id.messageBubble);
            ivReadReceipt = itemView.findViewById(R.id.ivReadReceipt);
            tvReaction = itemView.findViewById(R.id.tvReaction);
            layoutQuotedMessage = itemView.findViewById(R.id.layoutQuotedMessage);
            tvQuotedSender = itemView.findViewById(R.id.tvQuotedSender);
            tvQuotedText = itemView.findViewById(R.id.tvQuotedText);
        }
    }
}
