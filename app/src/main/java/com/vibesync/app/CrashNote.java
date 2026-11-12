package com.vibesync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.widget.Toast;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * If VibeSync ever closes unexpectedly, the error details are kept on the phone, and the next time it opens it
 * offers to copy them (to send to the developer). Nothing is sent anywhere by itself.
 */
final class CrashNote {
    private static final String PREFS = "vibesync_crash";

    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        if (before instanceof Saver) return;
        Thread.setDefaultUncaughtExceptionHandler(new Saver(app, before));
    }

    private static final class Saver implements Thread.UncaughtExceptionHandler {
        final Context app; final Thread.UncaughtExceptionHandler before;
        Saver(Context app, Thread.UncaughtExceptionHandler before) { this.app = app; this.before = before; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try {
                StringWriter w = new StringWriter();
                e.printStackTrace(new PrintWriter(w));
                String text = "VibeSync " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                        + Build.MANUFACTURER + " " + Build.MODEL + "\n" + w;
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString("last", text.length() > 6000 ? text.substring(0, 6000) : text).commit();
            } catch (Throwable ignored) { }
            if (before != null) before.uncaughtException(t, e);
        }
    }

    static void offer(final Activity act) {
        final SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String last = p.getString("last", "");
        if (last.isEmpty()) return;
        p.edit().remove("last").apply();
        try {
            new AlertDialog.Builder(act)
                    .setTitle("VibeSync closed unexpectedly")
                    .setMessage("Sorry about that. Copy the details and send them to the person who shared VibeSync with you, so it can be fixed.")
                    .setPositiveButton("Copy details", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("VibeSync crash", last));
                        Toast.makeText(act, "Copied. Paste it in a message.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Not now", null)
                    .show();
        } catch (Throwable ignored) { }
    }

    private CrashNote() { }
}
