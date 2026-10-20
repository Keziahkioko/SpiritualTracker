package com.keziah.spiritualtracker;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TreeMap;

/** Month-by-month history of both partners' disciplines, with streaks and trends. */
public class HistoryActivity extends AppCompatActivity {

    private static final String[] LABELS = ProgressMath.LABELS;
    private static final int[]    COLORS = {0xFF7E57C2, 0xFF26A69A, 0xFFFFA726, 0xFFEC407A};
    /** Same hues, dark enough to read as text on white (all >= 4.5:1). */
    private int[]                 TEXT_COLORS = {0xFF5E35B1, 0xFF00796B, 0xFFB45309, 0xFFC2185B};
    /** Light-look colours used on this screen and what each becomes in the Dark look. */
    private static final java.util.Map<Integer, Integer> DARK_OF = new java.util.HashMap<>();
    static {
        int[][] pairs = {
            {0xFF1A1A2E, 0xFFEEE9F5}, {0xFF5F5F64, 0xFFBCB4CA}, {0xFF424242, 0xFFBCB4CA},
            {0xFF9E9E9E, 0xFF8F879D}, {0xFF8A8494, 0xFF8F879D}, {0xFFA59EB0, 0xFF8F879D}, {0xFF6E6E73, 0xFF8F879D},
            {0xFFD0D0D0, 0xFF5A5266}, {0xFF4527A0, 0xFFD7C4FF}, {0xFF5E35B1, 0xFFC9B2F0},
            {0xFFEDE7F6, 0xFF352B47}, {0xFFD3C9E3, 0xFF4A4256}, {0xFFBDBDBD, 0xFF5E5669},
            {0xFFDDD5EA, 0xFF3D3448}, {0xFFF1EDF7, 0xFF332C3D}, {0xFFF6F2FB, 0xFF211C27},
            {0xFFF0EBF6, 0xFF3D3448}, {0xFFF4F1F8, 0xFF2E2836}, {0xFFD9D3E3, 0xFF4A4256},
            {0xFFB9B2C4, 0xFF6E6680}, {0xFF2448A3, 0xFF9DB6F5}, {0xFFFFFFFF, 0xFF28232F},
            // Day-ring tracks for the four practices
            {0xFFE0F2F1, 0xFF1F3A37}, {0xFFFFF3E0, 0xFF3D3020}, {0xFFFCE4EC, 0xFF3D2230},
        };
        for (int[] p : pairs) DARK_OF.put(p[0], p[1]);
    }
    private boolean dark;

    /** A colour for the current look: as written in Light, its Dark partner in Dark. */
    private int k(int light) {
        if (!dark) return light;
        Integer d = DARK_OF.get(light);
        return d != null ? d : light;
    }

    private static final String   HISTORY_START = "2000-01-01";

    private TextView tabMe, tabPartner, tabUs, tvMonthTitle, btnPrev, btnNext;
    private LinearLayout monthSummary, weekdayHeader, calendarLegend, streakContainer, trendContainer, content;
    private GridLayout calendarGrid;
    private ProgressBar progress;

    private String myId, partnerId;
    private String partnerName = "Partner";
    private TreeMap<String, HistoryRepository.DayRecord> myDays = new TreeMap<>();
    private TreeMap<String, HistoryRepository.DayRecord> partnerDays = new TreeMap<>();
    private boolean showingPartner = false;
    /** The "Us" tab: both partners together. */
    private boolean showingUs = false;
    // "Over time" chart: which practice, months or years, and which year's months.
    private int otDiscipline = 0;
    private boolean otYears = false;
    private int otYear = Calendar.getInstance().get(Calendar.YEAR);
    /** What each practice means in a sentence: "days you ___ each month". */
    private static final String[] VERBS = {"read the Bible", "prayed", "wrote in the journal", "practised memory verses"};
    private int pendingLoads = 0;

    /** First day (midnight) of the month being shown. */
    private final Calendar month = firstOfMonth(Calendar.getInstance());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = Appearance.isDark(this);
        // Practice names and counts in text: lighter shades so they read on the dark cards.
        if (dark) TEXT_COLORS = new int[]{0xFFC9B2F0, 0xFF6FD3C6, 0xFFFFC77A, 0xFFF48FB1};
        setContentView(R.layout.activity_history);
        PartnerName.apply(findViewById(android.R.id.content));

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        myId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        tabMe           = findViewById(R.id.tabHistoryMe);
        tabPartner      = findViewById(R.id.tabHistoryPartner);
        tabUs           = findViewById(R.id.tabHistoryUs);
        tvMonthTitle    = findViewById(R.id.tvMonthTitle);
        btnPrev         = findViewById(R.id.btnPrevMonth);
        btnNext         = findViewById(R.id.btnNextMonth);
        monthSummary    = findViewById(R.id.monthSummary);
        weekdayHeader   = findViewById(R.id.weekdayHeader);
        calendarLegend  = findViewById(R.id.calendarLegend);
        streakContainer = findViewById(R.id.streakContainer);
        trendContainer  = findViewById(R.id.trendContainer);
        calendarGrid    = findViewById(R.id.calendarGrid);
        content         = findViewById(R.id.historyContent);
        progress        = findViewById(R.id.pbHistory);

        findViewById(R.id.btnHistoryBack).setOnClickListener(v -> finish());
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        StatusBarUtil.padTop(findViewById(R.id.historyHeader));
        View scrollContent = ((android.view.ViewGroup) findViewById(R.id.historyHeader)).getParent() instanceof View
                ? (View) findViewById(R.id.historyHeader).getParent() : null;
        if (scrollContent != null) {
            final int bottom = scrollContent.getPaddingBottom();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scrollContent, (v, insets) -> {
                int nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom + nav);
                return insets;
            });
        }

        btnPrev.setOnClickListener(v -> { month.add(Calendar.MONTH, -1); render(); });
        btnNext.setOnClickListener(v -> { month.add(Calendar.MONTH, 1); render(); });

        tabMe.setOnClickListener(v -> selectTab(0));
        tabPartner.setOnClickListener(v -> {
            if (partnerId == null) {
                Toast.makeText(this, "No partner linked yet", Toast.LENGTH_SHORT).show();
                return;
            }
            selectTab(1);
        });
        tabUs.setOnClickListener(v -> {
            if (partnerId == null) {
                Toast.makeText(this, "Link your partner to see your journey together", Toast.LENGTH_SHORT).show();
                return;
            }
            selectTab(2);
        });


        buildWeekdayHeader();
        buildLegend();
        loadEverything();
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    private void loadEverything() {
        partnerName = PartnerName.get(this); // until their profile loads
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("users").document(myId).get()
                .addOnSuccessListener(me -> {
                    partnerId = me.getString("partnerId");
                    if (partnerId != null && partnerId.isEmpty()) partnerId = null;

                    pendingLoads = partnerId != null ? 2 : 1;
                    loadPerson(myId, false);
                    if (partnerId != null) {
                        db.collection("users").document(partnerId).get()
                                .addOnSuccessListener(p -> {
                                    partnerName = PartnerName.display(this, p);
                                    tabPartner.setText(partnerName);
                                });
                        loadPerson(partnerId, true);
                    } else {
                        tabPartner.setText("No partner");
                    }
                })
                .addOnFailureListener(e -> showError());
    }

    private void loadPerson(String uid, boolean isPartner) {
        HistoryRepository.load(uid, HISTORY_START, ActivityLog.today(), new HistoryRepository.Callback() {
            @Override public void onLoaded(TreeMap<String, HistoryRepository.DayRecord> days) {
                if (isPartner) partnerDays = days; else myDays = days;
                if (--pendingLoads <= 0) {
                    progress.setVisibility(View.GONE);
                    content.setVisibility(View.VISIBLE);
                    render();
                }
            }
            @Override public void onError(Exception e) {
                if (isPartner) {
                    // Still show my own history if the partner's can't be read.
                    if (--pendingLoads <= 0) {
                        progress.setVisibility(View.GONE);
                        content.setVisibility(View.VISIBLE);
                        render();
                    }
                } else {
                    showError();
                }
            }
        });
    }

    private void showError() {
        progress.setVisibility(View.GONE);
        Toast.makeText(this, "Couldn't load your history. Check your connection.", Toast.LENGTH_LONG).show();
    }

    /** 0 = me, 1 = my partner, 2 = us together. */
    private void selectTab(int tab) {
        showingPartner = tab == 1;
        showingUs = tab == 2;
        styleTab(tabMe, tab == 0);
        styleTab(tabPartner, tab == 1);
        styleTab(tabUs, tab == 2);
        render();
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setBackgroundResource(active ? R.drawable.tab_active : 0);
        tab.setTextColor(active ? k(0xFF4527A0) : k(0xFF5F5F64));
        tab.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ─── Rendering ───────────────────────────────────────────────────────────

    private TreeMap<String, HistoryRepository.DayRecord> currentDays() {
        return showingPartner ? partnerDays : myDays;
    }

    private void render() {
        findViewById(R.id.monthNav).setVisibility(showingUs ? View.GONE : View.VISIBLE);
        findViewById(R.id.personRings).setVisibility(showingUs ? View.GONE : View.VISIBLE);
        findViewById(R.id.personContent).setVisibility(showingUs ? View.GONE : View.VISIBLE);
        findViewById(R.id.usContent).setVisibility(showingUs ? View.VISIBLE : View.GONE);
        if (showingUs) {
            renderRings();
            renderUsStreaks();
            renderOverTime(trendContainer, true);
            return;
        }

        tvMonthTitle.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.getTime()));

        int shown = monthIndex(month);
        setEnabled(btnNext, shown < monthIndex(Calendar.getInstance()));
        setEnabled(btnPrev, shown > monthIndex(earliestDay()));

        TreeMap<String, HistoryRepository.DayRecord> days = currentDays();
        renderPersonRings(days);
        renderSummary(days);
        renderCalendar(days);
        renderStreaks(days);
        renderOverTime(findViewById(R.id.personTrendContainer), false);
    }

    private void setEnabled(TextView v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.25f);
    }

    private void renderSummary(TreeMap<String, HistoryRepository.DayRecord> days) {
        monthSummary.removeAllViews();
        int elapsed = elapsedDays(month);
        for (int i = 0; i < LABELS.length; i++) {
            int done = countInMonth(days, month, i);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            col.addView(dot(COLORS[i], 8));

            TextView count = text(String.valueOf(done), 22, TEXT_COLORS[i], true);
            count.setGravity(Gravity.CENTER);
            count.setPadding(0, dp(4), 0, 0);
            col.addView(count);
            TextView name = text(LABELS[i], 12, k(0xFF1A1A2E), true);
            name.setGravity(Gravity.CENTER);
            col.addView(name);
            TextView of = text("of " + elapsed + " days", 11, k(0xFF5F5F64), false);
            of.setGravity(Gravity.CENTER);
            col.addView(of);

            monthSummary.addView(col);
        }
    }

    private void buildWeekdayHeader() {
        String[] names = {"M", "T", "W", "T", "F", "S", "S"};
        for (String n : names) {
            TextView tv = text(n, 11, k(0xFFB39DDB), true);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            weekdayHeader.addView(tv);
        }
    }

    private void buildLegend() {
        for (int i = 0; i < LABELS.length; i++) {
            calendarLegend.addView(dot(COLORS[i], 7));
            TextView tv = text(LABELS[i], 11, k(0xFF9E9E9E), false);
            tv.setPadding(dp(4), 0, dp(10), 0);
            calendarLegend.addView(tv);
        }
    }

    private void renderCalendar(TreeMap<String, HistoryRepository.DayRecord> days) {
        calendarGrid.removeAllViews();
        String todayKey = ActivityLog.today();

        Calendar c = (Calendar) month.clone();
        int leadingBlanks = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday-first
        int daysInMonth = c.getActualMaximum(Calendar.DAY_OF_MONTH);

        for (int i = 0; i < leadingBlanks; i++) calendarGrid.addView(cell(null, null, false, false));

        for (int d = 1; d <= daysInMonth; d++) {
            c.set(Calendar.DAY_OF_MONTH, d);
            String key = ActivityLog.dayKey(c.getTime());
            boolean future = key.compareTo(todayKey) > 0;
            calendarGrid.addView(cell(String.valueOf(d), future ? null : days.get(key), future, key.equals(todayKey)));
            if (!future) {
                View last = calendarGrid.getChildAt(calendarGrid.getChildCount() - 1);
                last.setOnClickListener(v -> showDayDetail(key));
            }
        }
    }

    private View cell(String label, HistoryRepository.DayRecord r, boolean future, boolean isToday) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(0, dp(5), 0, dp(5));

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        cell.setLayoutParams(lp);

        if (label == null) return cell;

        boolean allDone = r != null && r.doneCount() == LABELS.length;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(allDone ? k(0xFFEDE7F6) : Color.TRANSPARENT);
        if (isToday) bg.setStroke(dp(1), k(0xFF7E57C2));
        cell.setBackground(bg);

        TextView num = text(label, 13, future ? k(0xFFD0D0D0) : k(0xFF1A1A2E), isToday || allDone);
        num.setGravity(Gravity.CENTER);
        cell.addView(num);

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(3), 0, 0);
        for (int i = 0; i < LABELS.length; i++) {
            int color = future ? Color.TRANSPARENT : (r != null && isDone(r, i) ? COLORS[i] : k(0xFFD3C9E3));
            View dot = dot(color, 6);
            ((LinearLayout.LayoutParams) dot.getLayoutParams()).setMargins(dp(1), 0, dp(1), 0);
            dots.addView(dot);
        }
        cell.addView(dots);
        return cell;
    }

    private void showDayDetail(String key) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        // Whose tab it is comes first. "Prayed for" names the other person: me for them, them for me.
        if (showingPartner && partnerId != null) {
            addPersonSection(box, partnerName, partnerDays.get(key), "you");
            addPersonSection(box, "You", myDays.get(key), partnerName);
        } else {
            addPersonSection(box, "You", myDays.get(key), partnerName);
            if (partnerId != null) addPersonSection(box, partnerName, partnerDays.get(key), "you");
        }

        Date date = parseKey(key);
        String title = date != null
                ? new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(date) : key;

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    private void addPersonSection(LinearLayout box, String who, HistoryRepository.DayRecord r, String prayedFor) {
        TextView name = text(who, 15, k(0xFF4527A0), true);
        name.setPadding(0, dp(12), 0, dp(4));
        box.addView(name);

        if (r == null || r.doneCount() == 0 && !r.prayedForPartner) {
            box.addView(detailLine(0xFFBDBDBD, "Nothing recorded this day"));
            return;
        }

        String bible = r.bible
                ? "Read the Word" + (r.bibleNote != null && !r.bibleNote.isEmpty() ? ": “" + r.bibleNote + "”" : "")
                : "Didn't mark Bible reading";
        box.addView(detailLine(r.bible ? COLORS[0] : k(0xFFBDBDBD), bible));

        String prayer;
        if (r.prayed && r.prayedForPartner) prayer = "Prayed, and prayed for " + prayedFor;
        else if (r.prayed)                  prayer = "Prayed";
        else if (r.prayedForPartner)        prayer = "Prayed for " + prayedFor;
        else                                prayer = "Didn't mark prayer";
        box.addView(detailLine(r.prayed || r.prayedForPartner ? COLORS[1] : k(0xFFBDBDBD), prayer));

        String journal = r.journal
                ? "Journaled" + (r.journalTitles.isEmpty() ? "" : ": " + String.join(", ", r.journalTitles))
                : "No journal entry";
        box.addView(detailLine(r.journal ? COLORS[2] : k(0xFFBDBDBD), journal));

        String memo = r.memorize
                ? "Practised verses" + (r.memorizeRefs.isEmpty() ? "" : ": " + String.join(", ", r.memorizeRefs))
                : "No verse practice";
        box.addView(detailLine(r.memorize ? COLORS[3] : k(0xFFBDBDBD), memo));
    }

    private View detailLine(int color, String message) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(dot(color, 8));
        TextView tv = text(message, 13, k(0xFF424242), false);
        tv.setPadding(dp(10), 0, 0, 0);
        row.addView(tv);
        return row;
    }

    private void renderStreaks(TreeMap<String, HistoryRepository.DayRecord> days) {
        streakContainer.removeAllViews();
        for (int i = 0; i <= LABELS.length; i++) {
            boolean all = i == LABELS.length;
            int[] s = ProgressMath.streaks(days, all ? ProgressMath.ALL : i, ActivityLog.today());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            row.addView(dot(all ? k(0xFF4527A0) : COLORS[i], 9));
            TextView label = text(all ? "All four" : LABELS[i], 14, k(0xFF1A1A2E), all);
            label.setPadding(dp(10), 0, 0, 0);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            row.addView(text(s[0] + (s[0] == 1 ? " day" : " days"), 13, k(0xFF1A1A2E), true));
            TextView best = text("  ·  best " + s[1], 13, k(0xFF5F5F64), false);
            row.addView(best);
            streakContainer.addView(row);
        }
    }

    /** Current and best streak for each practice, me and my partner side by side. */
    private void renderUsStreaks() {
        LinearLayout box = findViewById(R.id.usStreakContainer);
        box.removeAllViews();
        String today = ActivityLog.today();

        LinearLayout head = streakRow();
        head.addView(cell(text("", 12, k(0xFF1A1A2E), false), 1.3f));
        head.addView(cell(centered(text("You", 13, k(0xFF5E35B1), true)), 1f));
        head.addView(cell(centered(text(partnerName, 13, k(0xFF2448A3), true)), 1f));
        head.setPadding(0, 0, 0, dp(6));
        box.addView(head);

        for (int i = 0; i <= LABELS.length; i++) {
            boolean all = i == LABELS.length;
            int which = all ? ProgressMath.ALL : i;
            int[] mine = ProgressMath.streaks(myDays, which, today);
            int[] theirs = ProgressMath.streaks(partnerDays, which, today);

            LinearLayout row = streakRow();
            LinearLayout label = new LinearLayout(this);
            label.setOrientation(LinearLayout.HORIZONTAL);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.addView(dot(all ? k(0xFF4527A0) : COLORS[i], 9));
            TextView name = text(all ? "All four" : LABELS[i], 14, k(0xFF1A1A2E), all);
            name.setPadding(dp(8), 0, 0, 0);
            label.addView(name);
            row.addView(cell(label, 1.3f));
            row.addView(cell(streakCell(mine, k(0xFF5E35B1)), 1f));
            row.addView(cell(streakCell(theirs, k(0xFF2448A3)), 1f));
            box.addView(row);
        }
    }

    private LinearLayout streakRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        return row;
    }

    private View cell(View v, float weight) {
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight));
        return v;
    }

    private TextView centered(TextView tv) {
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private View streakCell(int[] s, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(text(s[0] + (s[0] == 1 ? " day" : " days"), 14, color, true));
        col.addView(text("best " + s[1], 11, k(0xFF5F5F64), false));
        return col;
    }

    /**
     * Days a practice was done, as plain counts: one row per month of a chosen year, or one row
     * per year. On the Us tab each row has my bar and my partner's bar.
     */
    private void renderOverTime(LinearLayout box, boolean both) {
        box.removeAllViews();
        int i = otDiscipline;
        Calendar now = Calendar.getInstance();
        int firstYear = earliestDay().get(Calendar.YEAR);
        int thisYear = now.get(Calendar.YEAR);
        otYear = Math.max(firstYear, Math.min(thisYear, otYear));

        // Practice picker.
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int d = 0; d < LABELS.length; d++) {
            final int pick = d;
            TextView chip = text(LABELS[d], 12, d == i ? Color.WHITE : k(0xFF424242), d == i);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, dp(7), 0, dp(7));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(16));
            if (d == i) g.setColor(TEXT_COLORS[d]); else { g.setColor(k(Color.WHITE)); g.setStroke(dp(1), k(0xFFDDD5EA)); }
            chip.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (d > 0) lp.setMarginStart(dp(6));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { otDiscipline = pick; render(); });
            chips.addView(chip);
        }
        box.addView(chips);

        // Months / Years switch, and the year arrows for months.
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(0, dp(12), 0, 0);
        LinearLayout toggle = new LinearLayout(this);
        toggle.setBackgroundResource(R.drawable.bg_tab_group);
        toggle.setPadding(dp(3), dp(3), dp(3), dp(3));
        toggle.addView(toggleTab("Months", !otYears, () -> { otYears = false; render(); }));
        toggle.addView(toggleTab("Years", otYears, () -> { otYears = true; render(); }));
        controls.addView(toggle);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        controls.addView(spacer);
        if (!otYears) {
            TextView prev = arrow("‹", "Earlier year", otYear > firstYear, () -> { otYear--; render(); });
            TextView year = text(String.valueOf(otYear), 16, k(0xFF1A1A2E), true);
            year.setPadding(dp(4), 0, dp(4), 0);
            TextView next = arrow("›", "Later year", otYear < thisYear, () -> { otYear++; render(); });
            controls.addView(prev);
            controls.addView(year);
            controls.addView(next);
        }
        box.addView(controls);

        // What the bars mean, in words.
        String who = both ? "you each" : showingPartner ? partnerName : "you";
        TextView title = text("Days " + who + " " + VERBS[i] + (otYears ? ", each year" : ", each month"),
                14, k(0xFF1A1A2E), true);
        title.setPadding(0, dp(14), 0, dp(8));
        box.addView(title);

        TreeMap<String, HistoryRepository.DayRecord> mine = both ? myDays : currentDays();
        java.util.List<Calendar[]> periods = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        SimpleDateFormat mf = new SimpleDateFormat("MMM", Locale.getDefault());
        if (otYears) {
            for (int y = firstYear; y <= thisYear; y++) {
                Calendar from = Calendar.getInstance();
                from.set(y, Calendar.JANUARY, 1);
                Calendar to = Calendar.getInstance();
                to.set(y, Calendar.DECEMBER, 31);
                periods.add(new Calendar[]{from, to});
                names.add(String.valueOf(y));
            }
        } else {
            Calendar first = earliestDay();
            for (int m = 0; m < 12; m++) {
                Calendar from = Calendar.getInstance();
                from.set(otYear, m, 1);
                if (monthIndex(from) > monthIndex(now)) break;
                if (monthIndex(from) < monthIndex(first)) continue;
                Calendar to = (Calendar) from.clone();
                to.set(Calendar.DAY_OF_MONTH, to.getActualMaximum(Calendar.DAY_OF_MONTH));
                periods.add(new Calendar[]{from, to});
                names.add(mf.format(from.getTime()));
            }
        }

        for (int p = 0; p < periods.size(); p++) {
            Calendar from = periods.get(p)[0], to = periods.get(p)[1];
            int length = otYears ? to.getActualMaximum(Calendar.DAY_OF_YEAR) : to.getActualMaximum(Calendar.DAY_OF_MONTH);
            String fromKey = ActivityLog.dayKey(from.getTime()), toKey = ActivityLog.dayKey(to.getTime());
            int a1 = ProgressMath.countBetween(mine, fromKey, toKey, i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));
            TextView label = text(names.get(p), 13, k(0xFF1A1A2E), false);
            label.setLayoutParams(new LinearLayout.LayoutParams(dp(otYears ? 46 : 38), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(label);

            LinearLayout bars = new LinearLayout(this);
            bars.setOrientation(LinearLayout.VERTICAL);
            bars.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            bars.addView(countBar(a1, length, both ? PairedRingView.ME : COLORS[i], both ? 0xFF5E35B1 : TEXT_COLORS[i]));
            if (both) {
                int a2 = ProgressMath.countBetween(partnerDays, fromKey, toKey, i);
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(3)));
                bars.addView(gap);
                bars.addView(countBar(a2, length, PairedRingView.PARTNER, 0xFF2448A3));
            }
            row.addView(bars);
            box.addView(row);
        }

        if (both) {
            LinearLayout legend = new LinearLayout(this);
            legend.setGravity(Gravity.CENTER);
            legend.setPadding(0, dp(10), 0, 0);
            legend.addView(legendItem("You", PairedRingView.ME, null));
            legend.addView(legendItem(partnerName, PairedRingView.PARTNER, null));
            box.addView(legend);
        }
        TextView hint = text(otYears ? "A full bar means every day of the year." : "A full bar means every day of the month.",
                11, k(0xFF5F5F64), false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);
    }

    /** A bar filled in proportion to days done, with the count written after it. */
    private View countBar(int done, int length, int color, int textColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout track = new LinearLayout(this);
        GradientDrawable t = new GradientDrawable();
        t.setCornerRadius(dp(5));
        t.setColor(0xFFF1EDF7);
        track.setBackground(t);
        track.setWeightSum(length);
        track.setLayoutParams(new LinearLayout.LayoutParams(0, dp(10), 1f));
        if (done > 0) {
            View fill = new View(this);
            GradientDrawable f = new GradientDrawable();
            f.setCornerRadius(dp(5));
            f.setColor(color);
            fill.setBackground(f);
            fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, Math.max(done, length / 40f)));
            track.addView(fill);
        }
        row.addView(track);

        TextView count = text(done + (done == 1 ? " day" : " days"), 12, done == 0 ? k(0xFF6E6E73) : textColor, done > 0);
        count.setGravity(Gravity.END);
        count.setLayoutParams(new LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(count);
        return row;
    }

    private TextView toggleTab(String label, boolean active, Runnable onClick) {
        TextView t = text(label, 13, active ? k(0xFF4527A0) : k(0xFF5F5F64), active);
        t.setPadding(dp(14), dp(5), dp(14), dp(5));
        if (active) t.setBackgroundResource(R.drawable.tab_active);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private TextView arrow(String symbol, String description, boolean enabled, Runnable onClick) {
        TextView t = text(symbol, 26, k(0xFF5E35B1), false);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        t.setContentDescription(description);
        t.setOnClickListener(v -> onClick.run());
        setEnabled(t, enabled);
        return t;
    }

    private static void appendColored(android.text.SpannableStringBuilder sb, String part, int color) {
        int at = sb.length();
        sb.append(part);
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), at, sb.length(), 0);
        sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), at, sb.length(), 0);
    }

    /** One paired ring per practice for this week (Monday to today): outer ring me, inner ring my partner. */
    private void renderRings() {
        LinearLayout row = findViewById(R.id.ringsRow);
        LinearLayout legend = findViewById(R.id.ringsLegend);
        row.removeAllViews();
        legend.removeAllViews();

        boolean both = partnerId != null && !partnerDays.isEmpty();
        Calendar c = Calendar.getInstance();
        String to = ActivityLog.dayKey(c.getTime());
        String from = ActivityLog.dayKey(ProgressMath.weekStart(c).getTime());

        for (int i = 0; i < LABELS.length; i++) {
            int mine = ProgressMath.countBetween(myDays, from, to, i);
            int theirs = both ? ProgressMath.countBetween(partnerDays, from, to, i) : -1;

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            PairedRingView ring = new PairedRingView(this);
            ring.setLayoutParams(new LinearLayout.LayoutParams(dp(62), dp(62)));
            ring.setValues(mine / 7f, both ? theirs / 7f : -1f);
            col.addView(ring);
            makePickable(col, i, this::renderRings);

            TextView name = text(LABELS[i], 13, k(0xFF1A1A2E), true);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(8), 0, dp(2));
            col.addView(name);

            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            appendColored(sb, mine + "/7", k(0xFF5E35B1));
            if (both) {
                sb.append("  ");
                appendColored(sb, theirs + "/7", k(0xFF2448A3));
            }
            TextView counts = text("", 12, k(0xFF1A1A2E), false);
            counts.setGravity(Gravity.CENTER);
            counts.setText(sb);
            col.addView(counts);
            row.addView(col);
        }

        legend.addView(legendItem("You", PairedRingView.ME, "outer"));
        if (both) legend.addView(legendItem(partnerName, PairedRingView.PARTNER, "inner"));

        LinearLayout detail = findViewById(R.id.usWeekDetail);
        detail.removeAllViews();
        detail.setVisibility(weekPick >= 0 ? View.VISIBLE : View.GONE);
        if (weekPick >= 0) {
            detail.addView(weekHeading(weekPick));
            detail.addView(weekStrip("You", myDays, weekPick, PairedRingView.ME));
            if (both) detail.addView(weekStrip(partnerName, partnerDays, weekPick, PairedRingView.PARTNER));
        }
    }

    /** One person's week so far (Monday to today): a ring per practice in that practice's colour. */
    private void renderPersonRings(TreeMap<String, HistoryRepository.DayRecord> days) {
        LinearLayout row = findViewById(R.id.personRingsRow);
        row.removeAllViews();
        Calendar c = Calendar.getInstance();
        String to = ActivityLog.dayKey(c.getTime());
        String from = ActivityLog.dayKey(ProgressMath.weekStart(c).getTime());
        int[] tracks = {k(0xFFEDE7F6), k(0xFFE0F2F1), k(0xFFFFF3E0), k(0xFFFCE4EC)};

        for (int i = 0; i < LABELS.length; i++) {
            int done = ProgressMath.countBetween(days, from, to, i);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
            holder.setLayoutParams(new LinearLayout.LayoutParams(dp(62), dp(62)));
            PairedRingView ring = new PairedRingView(this);
            ring.setSingle(done / 7f, COLORS[i], tracks[i]);
            holder.addView(ring, new android.widget.FrameLayout.LayoutParams(-1, -1));
            TextView inside = text(String.valueOf(done), 18, TEXT_COLORS[i], true);
            inside.setGravity(Gravity.CENTER);
            holder.addView(inside, new android.widget.FrameLayout.LayoutParams(-1, -1));
            col.addView(holder);

            TextView name = text(LABELS[i], 13, k(0xFF1A1A2E), true);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(8), 0, 0);
            col.addView(name);
            TextView of = text(done + " of 7 days", 11, k(0xFF5F5F64), false);
            of.setGravity(Gravity.CENTER);
            col.addView(of);
            makePickable(col, i, () -> renderPersonRings(days));
            row.addView(col);
        }

        LinearLayout detail = findViewById(R.id.personWeekDetail);
        detail.removeAllViews();
        detail.setVisibility(weekPick >= 0 ? View.VISIBLE : View.GONE);
        if (weekPick >= 0) {
            detail.addView(weekHeading(weekPick));
            detail.addView(weekStrip(null, days, weekPick, COLORS[weekPick]));
        }
    }

    // ─── This week, day by day ───────────────────────────────────────────────

    /** The practice whose days are open under the rings, or -1. Kept when switching tabs. */
    private int weekPick = -1;

    /** Tapping a ring opens (or closes) its days; the chosen one gets a soft highlight. */
    private void makePickable(LinearLayout col, int discipline, Runnable redraw) {
        col.setPadding(0, dp(6), 0, dp(6));
        if (weekPick == discipline) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFF6F2FB);
            bg.setCornerRadius(dp(16));
            col.setBackground(bg);
        }
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        col.setForeground(androidx.core.content.ContextCompat.getDrawable(this, ripple.resourceId));
        col.setContentDescription(LABELS[discipline] + ": show this week day by day");
        col.setOnClickListener(v -> {
            weekPick = weekPick == discipline ? -1 : discipline;
            redraw.run();
        });
    }

    private View weekHeading(int discipline) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        View line = new View(this);
        line.setBackgroundColor(0xFFF0EBF6);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(8), dp(10), dp(8), 0);
        box.addView(line, lp);
        TextView t = text(LABELS[discipline] + " this week", 13, TEXT_COLORS[discipline], true);
        t.setPadding(dp(12), dp(14), dp(12), dp(2));
        box.addView(t);
        return box;
    }

    /**
     * Monday to Sunday for one person: a filled circle with a tick where the practice was done,
     * an empty one where it was missed, and a faint one for days still to come. {@code who} names
     * the row (Us tab), or null.
     */
    private View weekStrip(String who, TreeMap<String, HistoryRepository.DayRecord> days, int discipline, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(6), dp(8), dp(6));

        Calendar today = Calendar.getInstance();
        String todayKey = ActivityLog.dayKey(today.getTime());
        Calendar c = ProgressMath.weekStart(today);
        int done = 0, missed = 0;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        SimpleDateFormat dayName = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int d = 0; d < 7; d++, c.add(Calendar.DAY_OF_YEAR, 1)) {
            String key = ActivityLog.dayKey(c.getTime());
            boolean future = key.compareTo(todayKey) > 0, isToday = key.equals(todayKey);
            boolean did = ProgressMath.isDone(days.get(key), discipline);
            if (did) done++;
            else if (!future && !isToday) missed++;

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView name = text(dayName.format(c.getTime()), 11, isToday ? k(0xFF4527A0) : k(0xFF8A8494), isToday);
            name.setGravity(Gravity.CENTER);
            col.addView(name);

            TextView mark = new TextView(this);
            mark.setGravity(Gravity.CENTER);
            mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            mark.setTypeface(null, Typeface.BOLD);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            if (did) {
                circle.setColor(color);
                mark.setText("\u2713");
                mark.setTextColor(Color.WHITE);
            } else if (future) {
                circle.setColor(0xFFF4F1F8);
            } else {
                circle.setColor(k(Color.WHITE));
                circle.setStroke(dp(isToday ? 2 : 1), isToday ? color : k(0xFFD9D3E3));
                if (!isToday) {
                    mark.setText("\u2013");
                    mark.setTextColor(0xFFB9B2C4);
                }
            }
            mark.setBackground(circle);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(30), dp(30));
            mp.topMargin = dp(4);
            col.addView(mark, mp);

            TextView date = text(String.valueOf(c.get(Calendar.DAY_OF_MONTH)), 10, k(0xFFA59EB0), false);
            date.setGravity(Gravity.CENTER);
            date.setPadding(0, dp(3), 0, 0);
            col.addView(date);
            col.setContentDescription(dayName.format(c.getTime()) + " " + c.get(Calendar.DAY_OF_MONTH) + ": "
                    + (did ? "done" : future ? "still to come" : isToday ? "not yet today" : "missed"));
            row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }

        String tally = done + (done == 1 ? " day" : " days") + " done" + (missed > 0 ? " \u00b7 " + missed + " missed" : "");
        TextView top = text(who == null ? tally : who + "  \u00b7  " + tally, 12,
                who == null ? k(0xFF5F5F64) : k(0xFF1A1A2E), who != null);
        top.setPadding(dp(4), 0, 0, dp(8));
        box.addView(top);
        box.addView(row);
        return box;
    }

    private View legendItem(String who, int color, String where) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(10), 0, dp(10), 0);
        item.addView(dot(color, 10));
        TextView tv = text(where == null ? who : who + " · " + where + " ring", 12, k(0xFF424242), false);
        tv.setPadding(dp(6), 0, 0, 0);
        item.addView(tv);
        return item;
    }

    // ─── Calculations ────────────────────────────────────────────────────────

    private boolean isDone(HistoryRepository.DayRecord r, int discipline) {
        return ProgressMath.isDone(r, discipline);
    }

    private int countInMonth(TreeMap<String, HistoryRepository.DayRecord> days, Calendar monthStart, int discipline) {
        Calendar end = (Calendar) monthStart.clone();
        end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH));
        int count = 0;
        for (HistoryRepository.DayRecord r : days.subMap(
                ActivityLog.dayKey(monthStart.getTime()), true,
                ActivityLog.dayKey(end.getTime()), true).values()) {
            if (isDone(r, discipline)) count++;
        }
        return count;
    }

    /** Days of the month that have already happened (whole month for past months, 0 for future). */
    private int elapsedDays(Calendar monthStart) {
        Calendar now = Calendar.getInstance();
        int diff = monthIndex(monthStart) - monthIndex(now);
        if (diff > 0) return 0;
        if (diff == 0) return now.get(Calendar.DAY_OF_MONTH);
        return monthStart.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private static int monthIndex(Calendar c) {
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH);
    }

    private Calendar earliestDay() {
        Calendar c = Calendar.getInstance();
        String earliest = null;
        if (!myDays.isEmpty()) earliest = myDays.firstKey();
        if (!partnerDays.isEmpty() && (earliest == null || partnerDays.firstKey().compareTo(earliest) < 0))
            earliest = partnerDays.firstKey();
        Date d = earliest != null ? parseKey(earliest) : null;
        if (d != null) c.setTime(d);
        return c;
    }

    private static Calendar firstOfMonth(Calendar c) {
        Calendar f = (Calendar) c.clone();
        f.set(Calendar.DAY_OF_MONTH, 1);
        f.set(Calendar.HOUR_OF_DAY, 0);
        f.set(Calendar.MINUTE, 0);
        f.set(Calendar.SECOND, 0);
        f.set(Calendar.MILLISECOND, 0);
        return f;
    }

    private static Date parseKey(String key) {
        try {
            return new SimpleDateFormat(ActivityLog.DATE_FMT, Locale.US).parse(key);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── View helpers ────────────────────────────────────────────────────────

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        return tv;
    }

    private View dot(int color, int sizeDp) {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
