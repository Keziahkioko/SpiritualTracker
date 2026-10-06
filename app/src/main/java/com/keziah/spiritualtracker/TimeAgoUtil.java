package com.keziah.spiritualtracker;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class TimeAgoUtil {

    public static String getTimeAgo(long timestamp) {
        if (timestamp <= 0) return "";

        long time = timestamp;
        long now = System.currentTimeMillis();
        long diff = now - time;

        if (diff < 60000) { // less than 1 minute
            return "Just now";
        } else if (diff < 3600000) { // less than 1 hour
            return (diff / 60000) + "m ago";
        }

        // Setup Calendars to check for "Today" or "Yesterday"
        Calendar msgCal = Calendar.getInstance();
        msgCal.setTimeInMillis(time);

        Calendar nowCal = Calendar.getInstance();

        SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.getDefault());
        String formattedTime = timeFormat.format(new Date(time));

        if (nowCal.get(Calendar.DATE) == msgCal.get(Calendar.DATE) &&
                nowCal.get(Calendar.MONTH) == msgCal.get(Calendar.MONTH) &&
                nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)) {
            return "Today at " + formattedTime;
        }

        nowCal.add(Calendar.DATE, -1); // Move back one day
        if (nowCal.get(Calendar.DATE) == msgCal.get(Calendar.DATE) &&
                nowCal.get(Calendar.MONTH) == msgCal.get(Calendar.MONTH) &&
                nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)) {
            return "Yesterday at " + formattedTime;
        }

        // If older than yesterday, show the date and time
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, h:mm a", Locale.getDefault());
        return dateFormat.format(new Date(time));
    }
}