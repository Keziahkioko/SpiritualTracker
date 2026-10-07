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

    private static final String[] LABELS = {"Bible", "Prayer", "Journal", "Memorize"};
    private static final int[]    COLORS = {0xFF7E57C2, 0xFF26A69A, 0xFFFFA726, 0xFFEC407A};
    private static final String   HISTORY_START = "2000-01-01";

    private TextView tabMe, tabPartner, tvMonthTitle, btnPrev, btnNext;
    private LinearLayout monthSummary, weekdayHeader, calendarLegend, streakContainer, trendContainer, content;
    private GridLayout calendarGrid;
    private ProgressBar progress;

    private String myId, partnerId;
    private String partnerName = "Partner";
    private TreeMap<String, HistoryRepository.DayRecord> myDays = new TreeMap<>();
    private TreeMap<String, HistoryRepository.DayRecord> partnerDays = new TreeMap<>();
    private boolean showingPartner = false;
    private int pendingLoads = 0;

    /** First day (midnight) of the month being shown. */
    private final Calendar month = firstOfMonth(Calendar.getInstance());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        myId = FirebaseAuth.getInstance().getCurrentUser().getUid();

        tabMe           = findViewById(R.id.tabHistoryMe);
        tabPartner      = findViewById(R.id.tabHistoryPartner);
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

        btnPrev.setOnClickListener(v -> { month.add(Calendar.MONTH, -1); render(); });
        btnNext.setOnClickListener(v -> { month.add(Calendar.MONTH, 1); render(); });

        tabMe.setOnClickListener(v -> selectPerson(false));
        tabPartner.setOnClickListener(v -> {
            if (partnerId == null) {
                Toast.makeText(this, "No partner linked yet", Toast.LENGTH_SHORT).show();
                return;
            }
            selectPerson(true);
        });

        buildWeekdayHeader();
        buildLegend();
        loadEverything();
    }

    // ─── Loading ─────────────────────────────────────────────────────────────

    private void loadEverything() {
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
                                    partnerName = UserProfile.partnerName(p);
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

    private void selectPerson(boolean partner) {
        showingPartner = partner;
        styleTab(tabMe, !partner);
        styleTab(tabPartner, partner);
        render();
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setBackgroundResource(active ? R.drawable.tab_active : 0);
        tab.setTextColor(active ? 0xFF4527A0 : 0xFF757575);
        tab.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ─── Rendering ───────────────────────────────────────────────────────────

    private TreeMap<String, HistoryRepository.DayRecord> currentDays() {
        return showingPartner ? partnerDays : myDays;
    }

    private void render() {
        tvMonthTitle.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.getTime()));

        int shown = monthIndex(month);
        setEnabled(btnNext, shown < monthIndex(Calendar.getInstance()));
        setEnabled(btnPrev, shown > monthIndex(earliestDay()));

        TreeMap<String, HistoryRepository.DayRecord> days = currentDays();
        renderSummary(days);
        renderCalendar(days);
        renderStreaks(days);
        renderTrends(days);
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

            TextView count = text(done + "/" + elapsed, 18, 0xFF1A1A2E, true);
            count.setPadding(0, dp(4), 0, 0);
            col.addView(count);
            col.addView(text(LABELS[i], 11, 0xFF757575, false));
            col.addView(text(elapsed == 0 ? "–" : Math.round(done * 100f / elapsed) + "%", 11, COLORS[i], true));

            monthSummary.addView(col);
        }
    }

    private void buildWeekdayHeader() {
        String[] names = {"M", "T", "W", "T", "F", "S", "S"};
        for (String n : names) {
            TextView tv = text(n, 11, 0xFFB39DDB, true);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            weekdayHeader.addView(tv);
        }
    }

    private void buildLegend() {
        for (int i = 0; i < LABELS.length; i++) {
            calendarLegend.addView(dot(COLORS[i], 7));
            TextView tv = text(LABELS[i], 11, 0xFF9E9E9E, false);
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
        bg.setColor(allDone ? 0xFFEDE7F6 : Color.TRANSPARENT);
        if (isToday) bg.setStroke(dp(1), 0xFF7E57C2);
        cell.setBackground(bg);

        TextView num = text(label, 13, future ? 0xFFD0D0D0 : 0xFF1A1A2E, isToday || allDone);
        num.setGravity(Gravity.CENTER);
        cell.addView(num);

        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(3), 0, 0);
        for (int i = 0; i < LABELS.length; i++) {
            int color = future ? Color.TRANSPARENT : (r != null && isDone(r, i) ? COLORS[i] : 0xFFE6E0EE);
            View dot = dot(color, 5);
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

        addPersonSection(box, "Me", myDays.get(key));
        if (partnerId != null) addPersonSection(box, partnerName, partnerDays.get(key));

        Date date = parseKey(key);
        String title = date != null
                ? new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(date) : key;

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    private void addPersonSection(LinearLayout box, String who, HistoryRepository.DayRecord r) {
        TextView name = text(who, 15, 0xFF4527A0, true);
        name.setPadding(0, dp(12), 0, dp(4));
        box.addView(name);

        if (r == null || r.doneCount() == 0 && !r.prayedForPartner) {
            box.addView(detailLine(0xFFBDBDBD, "Nothing recorded this day"));
            return;
        }

        String bible = r.bible
                ? "Read the Word" + (r.bibleNote != null && !r.bibleNote.isEmpty() ? ": “" + r.bibleNote + "”" : "")
                : "Didn't mark Bible reading";
        box.addView(detailLine(r.bible ? COLORS[0] : 0xFFBDBDBD, bible));

        String prayer;
        if (r.prayed && r.prayedForPartner) prayer = "Prayed, and prayed for partner";
        else if (r.prayed)                  prayer = "Prayed";
        else if (r.prayedForPartner)        prayer = "Prayed for partner";
        else                                prayer = "Didn't mark prayer";
        box.addView(detailLine(r.prayed || r.prayedForPartner ? COLORS[1] : 0xFFBDBDBD, prayer));

        String journal = r.journal
                ? "Journaled" + (r.journalTitles.isEmpty() ? "" : ": " + String.join(", ", r.journalTitles))
                : "No journal entry";
        box.addView(detailLine(r.journal ? COLORS[2] : 0xFFBDBDBD, journal));

        String memo = r.memorize
                ? "Practised verses" + (r.memorizeRefs.isEmpty() ? "" : ": " + String.join(", ", r.memorizeRefs))
                : "No verse practice";
        box.addView(detailLine(r.memorize ? COLORS[3] : 0xFFBDBDBD, memo));
    }

    private View detailLine(int color, String message) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        row.addView(dot(color, 8));
        TextView tv = text(message, 13, 0xFF424242, false);
        tv.setPadding(dp(10), 0, 0, 0);
        row.addView(tv);
        return row;
    }

    private void renderStreaks(TreeMap<String, HistoryRepository.DayRecord> days) {
        streakContainer.removeAllViews();
        for (int i = 0; i <= LABELS.length; i++) {
            boolean all = i == LABELS.length;
            int[] s = streaks(days, all ? -1 : i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            row.addView(dot(all ? 0xFF4527A0 : COLORS[i], 9));
            TextView label = text(all ? "All four" : LABELS[i], 14, 0xFF1A1A2E, all);
            label.setPadding(dp(10), 0, 0, 0);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            row.addView(text("Now " + s[0] + "d", 13, 0xFF1A1A2E, true));
            TextView best = text("  ·  Best " + s[1] + "d", 13, 0xFF9E9E9E, false);
            row.addView(best);
            streakContainer.addView(row);
        }
    }

    /** Returns {current, best}. Today only counts once done, so an unfinished today doesn't break the streak. */
    private int[] streaks(TreeMap<String, HistoryRepository.DayRecord> days, int discipline) {
        if (days.isEmpty()) return new int[]{0, 0};

        Calendar c = Calendar.getInstance();
        String todayKey = ActivityLog.dayKey(c.getTime());
        if (!done(days.get(todayKey), discipline)) c.add(Calendar.DAY_OF_YEAR, -1);
        int current = 0;
        while (done(days.get(ActivityLog.dayKey(c.getTime())), discipline)) {
            current++;
            c.add(Calendar.DAY_OF_YEAR, -1);
        }

        int best = 0, run = 0;
        Calendar walk = Calendar.getInstance();
        Date first = parseKey(days.firstKey());
        if (first == null) return new int[]{current, current};
        walk.setTime(first);
        while (ActivityLog.dayKey(walk.getTime()).compareTo(todayKey) <= 0) {
            if (done(days.get(ActivityLog.dayKey(walk.getTime())), discipline)) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
            walk.add(Calendar.DAY_OF_YEAR, 1);
        }
        return new int[]{current, Math.max(best, current)};
    }

    private boolean done(HistoryRepository.DayRecord r, int discipline) {
        if (r == null) return false;
        return discipline < 0 ? r.doneCount() == LABELS.length : isDone(r, discipline);
    }

    private void renderTrends(TreeMap<String, HistoryRepository.DayRecord> days) {
        trendContainer.removeAllViews();
        final int months = 6;
        Calendar[] cols = new Calendar[months];
        for (int m = 0; m < months; m++) {
            Calendar c = (Calendar) month.clone();
            c.add(Calendar.MONTH, m - (months - 1));
            cols[m] = c;
        }

        for (int i = 0; i < LABELS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.BOTTOM);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView label = text(LABELS[i], 12, 0xFF424242, true);
            label.setLayoutParams(new LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT));
            label.setPadding(0, 0, 0, dp(14));
            row.addView(label);

            for (Calendar c : cols) {
                int elapsed = elapsedDays(c);
                int pct = elapsed == 0 ? -1 : Math.round(countInMonth(days, c, i) * 100f / elapsed);
                row.addView(trendBar(pct, COLORS[i]));
            }
            trendContainer.addView(row);
        }

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(68), 1));
        labels.addView(spacer);
        SimpleDateFormat mf = new SimpleDateFormat("MMM", Locale.getDefault());
        for (Calendar c : cols) {
            TextView tv = text(mf.format(c.getTime()), 11, 0xFF9E9E9E, false);
            tv.setGravity(Gravity.CENTER);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            labels.addView(tv);
        }
        trendContainer.addView(labels);
    }

    private View trendBar(int pct, int color) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        int maxHeight = dp(36);
        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(pct <= 0 ? 0xFFEDE7F6 : color);
        bar.setBackground(bg);
        int h = pct <= 0 ? dp(3) : Math.max(dp(3), maxHeight * pct / 100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(16), h);
        lp.topMargin = maxHeight - h;
        bar.setLayoutParams(lp);
        col.addView(bar);

        TextView tv = text(pct < 0 ? "–" : pct + "%", 10, 0xFF757575, false);
        tv.setPadding(0, dp(2), 0, 0);
        col.addView(tv);
        return col;
    }

    // ─── Calculations ────────────────────────────────────────────────────────

    private boolean isDone(HistoryRepository.DayRecord r, int discipline) {
        switch (discipline) {
            case 0:  return r.bible;
            case 1:  return r.prayed;
            case 2:  return r.journal;
            default: return r.memorize;
        }
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
