package com.keziah.spiritualtracker;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Change and position the couple's home photo. The frame at the top has the home screen photo's
 * exact size (it runs behind the status bar the same way), so pinching and dragging here sets
 * what both phones show.
 */
public class HomePhotoActivity extends AppCompatActivity {

    public static final String EXTRA_PARTNER = "partnerId";
    /** The home screen's number, shown faintly where it sits on the photo. */
    public static final String EXTRA_SCORE = "score";

    private FramedImageView frame;
    private MaterialButton btnSave;
    private com.google.android.material.slider.Slider zoomSlider;
    private View hint;
    private String me, partnerId;
    /** What will be saved: an uploaded photo's address, a new local file, or null for the built-in photo. */
    private String url, localPath;
    private boolean changed;
    private String myName = UserProfile.FALLBACK_NAME;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickPhoto =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::onPicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_photo);

        me = FirebaseAuth.getInstance().getUid();
        partnerId = getIntent().getStringExtra(EXTRA_PARTNER);
        if (me == null || partnerId == null) { finish(); return; }

        frame = findViewById(R.id.ivFrame);
        frame.setEditable(true);
        btnSave = findViewById(R.id.btnSavePhoto);
        drawBehindStatusBar();

        ((android.widget.TextView) findViewById(R.id.tvPhotoInfo)).setText(
                "Shared with " + PartnerName.get(this) + " · changes on both phones");
        String score = getIntent().getStringExtra(EXTRA_SCORE);
        if (score != null) ((android.widget.TextView) findViewById(R.id.tvGhostScore)).setText(score);

        // Pinching and the slider stay in step; the hint goes once the photo has been moved.
        hint = findViewById(R.id.tvFrameHint);
        zoomSlider = findViewById(R.id.sliderZoom);
        zoomSlider.setValueTo(PhotoFrame.MAX_ZOOM);
        zoomSlider.addOnChangeListener((s, value, fromUser) -> {
            if (!fromUser) return;
            frame.setMagnification(value);
            changed = true;
            hideHint();
        });
        frame.setOnFrameChanged(() -> {
            changed = true;
            hideHint();
            syncSlider();
        });
        // Whenever a photo arrives (or the frame is laid out), put the slider where the photo is.
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> syncSlider());

        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me).get()
                .addOnSuccessListener(doc -> myName = UserProfile.displayName(doc));

        // Start from what the home screen shows now.
        frame.setImageResource(R.drawable.header_bg);
        HomePhoto.ref(me, partnerId).get().addOnSuccessListener(doc -> {
            if (changed || isFinishing()) return;
            url = doc.exists() ? doc.getString(HomePhoto.URL) : null;
            HomePhoto.show(frame, doc);
            frame.postDelayed(this::syncSlider, 300);
        });

        findViewById(R.id.btnPhotoBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnChoosePhoto).setOnClickListener(v -> pickPhoto.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        findViewById(R.id.btnOriginalPhoto).setOnClickListener(v -> {
            changed = true;
            url = null;
            localPath = null;
            HomePhoto.show(frame, null, 0.5f, 0.5f, 1f);
            syncSlider();
        });
        btnSave.setOnClickListener(v -> save());
    }

    private void onPicked(Uri uri) {
        if (uri == null) return;
        btnSave.setEnabled(false);
        btnSave.setText("Preparing photo…");
        JournalPhotos.shrink(this, uri, path -> {
            btnSave.setEnabled(true);
            btnSave.setText("Save");
            if (path == null) {
                Toast.makeText(this, "Couldn't open that photo. Try another one.", Toast.LENGTH_LONG).show();
                return;
            }
            changed = true;
            localPath = path;
            HomePhoto.show(frame, path, 0.5f, 0.5f, 1f);
            frame.postDelayed(this::syncSlider, 300);
        });
    }

    private void save() {
        btnSave.setEnabled(false);
        btnSave.setText("Saving…");
        if (localPath == null) {
            store(url);
            return;
        }
        JournalPhotos.uploadAll(new ArrayList<>(Collections.singletonList(localPath)), new JournalPhotos.Uploaded() {
            @Override public void progress(int done, int total) { }
            @Override public void finished(ArrayList<String> urls) {
                if (urls == null || urls.isEmpty()) {
                    btnSave.setEnabled(true);
                    btnSave.setText("Save");
                    Toast.makeText(HomePhotoActivity.this,
                            "Couldn't upload the photo. Check your connection and try again.", Toast.LENGTH_LONG).show();
                    return;
                }
                store(urls.get(0));
            }
        });
    }

    private void store(String photoUrl) {
        HomePhoto.save(me, partnerId, photoUrl, frame.frameX(), frame.frameY(), frame.frameZoom())
                .addOnSuccessListener(x -> {
                    PartnerNotifier.notifyPartner(partnerId, "New home photo",
                            myName + " changed your home photo", "home_photo", "");
                    Toast.makeText(this, "Home photo saved", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .addOnFailureListener(e -> {
                    btnSave.setEnabled(true);
                    btnSave.setText("Save");
                    Toast.makeText(this, "Couldn't save. Check your connection and try again.", Toast.LENGTH_LONG).show();
                });
    }

    private void syncSlider() {
        float m = Math.max(1f, Math.min(PhotoFrame.MAX_ZOOM, frame.magnification()));
        if (Math.abs(zoomSlider.getValue() - m) > 0.001f) zoomSlider.setValue(m);
    }

    private void hideHint() {
        if (hint.getVisibility() != View.VISIBLE) return;
        hint.animate().alpha(0f).setDuration(200).withEndAction(() -> hint.setVisibility(View.GONE));
    }

    /** Same as the home screen: the photo runs up behind the status bar, so the frame is that tall too. */
    private void drawBehindStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        View area = findViewById(R.id.frameArea);
        // Everything on the photo moves down by the status bar's height, as on the home screen.
        View[] pushedDown = {findViewById(R.id.btnPhotoBack), findViewById(R.id.ghostScore), findViewById(R.id.ghostIcons)};
        int[] tops = new int[pushedDown.length];
        for (int i = 0; i < pushedDown.length; i++) {
            tops[i] = ((android.view.ViewGroup.MarginLayoutParams) pushedDown[i].getLayoutParams()).topMargin;
        }
        View saveBar = findViewById(R.id.saveBar);
        int height = area.getLayoutParams().height;
        int bottom = saveBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.photoRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            area.getLayoutParams().height = height + bars.top;
            for (int i = 0; i < pushedDown.length; i++) {
                ((android.view.ViewGroup.MarginLayoutParams) pushedDown[i].getLayoutParams()).topMargin = tops[i] + bars.top;
            }
            saveBar.setPadding(saveBar.getPaddingLeft(), saveBar.getPaddingTop(), saveBar.getPaddingRight(), bottom + bars.bottom);
            area.requestLayout();
            return insets;
        });
    }
}
