package com.keziah.spiritualtracker;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Patterns;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.keziah.spiritualtracker.databinding.ActivityLoginBinding;

public class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;
    private FirebaseAuth mAuth;
    /** One screen for both: false = log in, true = create an account. */
    private boolean creating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // Let the lavender run behind the status bar; keep the bottom bar in the same soft shade.
        getWindow().setStatusBarColor(Color.parseColor("#E4DAF5"));
        getWindow().setNavigationBarColor(Color.parseColor("#FAF8FD"));
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(true);

        mAuth = FirebaseAuth.getInstance();

        binding.btnLogin.setOnClickListener(v -> submit());
        binding.etPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); return true; }
            return false;
        });
        binding.tvRegister.setOnClickListener(v -> { creating = !creating; showMode(); });
        binding.tvForgot.setOnClickListener(v -> resetPassword());

        // Clear a field's error as soon as it is edited.
        binding.etEmail.addTextChangedListener(clearError(binding.tilEmail));
        binding.etPassword.addTextChangedListener(clearError(binding.tilPassword));

        showMode();
    }

    private void showMode() {
        binding.tvLoginTitle.setText(creating ? "Create Account" : "Welcome Back");
        binding.tvLoginSubtitle.setText(creating ? "Start your journey together" : "Sign in to continue your journey");
        binding.btnLogin.setText(creating ? "CREATE ACCOUNT" : "LOG IN");
        binding.tilPassword.setHelperText(creating ? "At least 6 characters" : null);
        binding.tvForgot.setVisibility(creating ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
        binding.tvRegister.setText(link(creating ? "Already have an account? " : "Don't have an account? ",
                creating ? "Log in" : "Create one"));
        noError(binding.tilEmail);
        noError(binding.tilPassword);
    }

    /** Clears the error and gives back the space it took. */
    private static void noError(com.google.android.material.textfield.TextInputLayout til) {
        til.setError(null);
        til.setErrorEnabled(false);
    }

    private static CharSequence link(String lead, String action) {
        SpannableString s = new SpannableString(lead + action);
        int start = lead.length();
        s.setSpan(new ForegroundColorSpan(Color.parseColor("#6A4FB0")), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void submit() {
        String email = binding.etEmail.getText().toString().trim();
        String password = binding.etPassword.getText().toString().trim(); // trimmed like when accounts were created

        boolean ok = true;
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError(email.isEmpty() ? "Enter your email" : "That email doesn't look right");
            ok = false;
        }
        if (TextUtils.isEmpty(password)) {
            binding.tilPassword.setError("Enter your password");
            ok = false;
        } else if (creating && password.length() < 6) {
            binding.tilPassword.setError("Use at least 6 characters");
            ok = false;
        }
        if (!ok) return;

        setBusy(true);
        (creating ? mAuth.createUserWithEmailAndPassword(email, password)
                  : mAuth.signInWithEmailAndPassword(email, password))
                .addOnCompleteListener(this, task -> {
                    setBusy(false);
                    if (task.isSuccessful()) {
                        goToDashboard();
                    } else {
                        showError(task.getException());
                    }
                });
    }

    private void setBusy(boolean busy) {
        binding.btnLogin.setEnabled(!busy);
        binding.tvRegister.setEnabled(!busy);
        binding.btnLogin.setText(busy ? (creating ? "Creating account…" : "Signing in…")
                                      : (creating ? "CREATE ACCOUNT" : "LOG IN"));
    }

    /** Plain words instead of Firebase's technical messages. */
    private void showError(Exception e) {
        if (e instanceof FirebaseNetworkException) {
            toast("No internet connection. Check your data or Wi-Fi and try again.");
        } else if (e instanceof FirebaseTooManyRequestsException) {
            toast("Too many tries. Please wait a few minutes and try again.");
        } else if (e instanceof FirebaseAuthUserCollisionException) {
            binding.tilEmail.setError("An account with this email already exists. Log in instead.");
        } else if (e instanceof FirebaseAuthWeakPasswordException) {
            binding.tilPassword.setError("Choose a stronger password (at least 6 characters)");
        } else if (e instanceof FirebaseAuthInvalidUserException) {
            binding.tilEmail.setError("No account found with this email");
        } else if (e instanceof FirebaseAuthInvalidCredentialsException) {
            binding.tilPassword.setError(creating ? "That email or password can't be used"
                                                  : "Email or password is incorrect");
        } else {
            toast("Something went wrong. Please try again.");
        }
    }

    private void resetPassword() {
        String email = binding.etEmail.getText().toString().trim();
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.setError("Enter your email first, then tap Forgot password");
            binding.etEmail.requestFocus();
            return;
        }
        mAuth.sendPasswordResetEmail(email).addOnCompleteListener(this, task -> {
            if (!task.isSuccessful() && task.getException() instanceof FirebaseNetworkException) {
                showError(task.getException());
                return;
            }
            // Same message either way, so the screen doesn't reveal which emails have accounts.
            new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                    .setTitle("Check your email")
                    .setMessage("If an account exists for " + email + ", we've sent a link to reset your password.")
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    private TextWatcher clearError(com.google.android.material.textfield.TextInputLayout til) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { if (til.getError() != null) noError(til); }
        };
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onStart() {
        super.onStart();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            goToDashboard();
        }
    }

    private void goToDashboard() {
        Intent intent = new Intent(LoginActivity.this, MainActivity.class);
        // A notification tapped while the app was closed lands here; pass its routing info on.
        if (getIntent() != null && getIntent().getExtras() != null) {
            intent.putExtras(getIntent().getExtras());
        }
        startActivity(intent);
        finish(); // Prevents user from pressing "Back" to go back to login
    }
}
