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

    private FramedImageView frame;
    private MaterialButton btnSave;
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
                "The box above is exactly what the top of your home screen shows. Shared with "
                        + PartnerName.get(this) + ": it changes on both phones.");

        com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(me).get()
                .addOnSuccessListener(doc -> myName = UserProfile.displayName(doc));

        // Start from what the home screen shows now.
        frame.setImageResource(R.drawable.header_bg);
        HomePhoto.ref(me, partnerId).get().addOnSuccessListener(doc -> {
            if (changed || isFinishing()) return;
            url = doc.exists() ? doc.getString(HomePhoto.URL) : null;
            HomePhoto.show(frame, doc);
        });

        findViewById(R.id.btnPhotoBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnChoosePhoto).setOnClickListener(v -> pickPhoto.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        findViewById(R.id.btnOriginalPhoto).setOnClickListener(v -> {
            changed = true;
            url = null;
            localPath = null;
            HomePhoto.show(frame, null, 0.5f, 0.5f, 1f);
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

    /** Same as the home screen: the photo runs up behind the status bar, so the frame is that tall too. */
    private void drawBehindStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        View area = findViewById(R.id.frameArea);
        View back = findViewById(R.id.btnPhotoBack);
        View content = findViewById(R.id.photoContent);
        int height = area.getLayoutParams().height;
        int backTop = ((android.view.ViewGroup.MarginLayoutParams) back.getLayoutParams()).topMargin;
        int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.photoRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            area.getLayoutParams().height = height + bars.top;
            ((android.view.ViewGroup.MarginLayoutParams) back.getLayoutParams()).topMargin = backTop + bars.top;
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), bottom + bars.bottom);
            content.requestLayout();
            return insets;
        });
    }
}
