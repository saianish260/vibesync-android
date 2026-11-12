package com.vibesync.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-app updates. At launch (at most every few hours) the app reads the version of the newest build that
 * GitHub Actions published, and if it's newer, offers it in a popup. "Update" downloads the APK inside the app
 * and hands it to Android's package installer, which asks the person to confirm. The phone app (com.vibesync.app)
 * follows the 'apk' branch, VibeSync TV (com.vibesync.tv) the 'apk-tv' branch.
 */
final class Updater {
    private static final String REPO = "saianish260/vibesync-android";
    private static final String PREFS = "vibesync_update";
    private static final long CHECK_EVERY = 4L * 3600_000L;     // background checks
    private static final long SNOOZE = 24L * 3600_000L;         // "Later" hides that version for a day
    static final String ACTION_STATUS = "com.vibesync.app.UPDATE_STATUS";
    private static boolean busy = false;

    private Updater() {}

    private static boolean isTv(Context c) { return c.getPackageName().endsWith(".tv"); }
    private static String branch(Context c) { return isTv(c) ? "apk-tv" : "apk"; }
    private static String apkName(Context c) { return isTv(c) ? "VibeSync-TV.apk" : "VibeSync.apk"; }
    private static String codeBranch(Context c) { return isTv(c) ? "tv" : "main"; }
    private static String appLabel(Context c) { return isTv(c) ? "VibeSync TV" : "VibeSync"; }

    /** Called when the app comes to the front. */
    static void maybeCheck(Activity a) { check(a, false); }

    /** "Check for updates" in the app's menu: right now, ignoring the timer and any "Later", and says what it found. */
    static void checkNow(Activity a) { check(a, true); }

    private static void say(Activity a, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> { if (!a.isFinishing()) Toast.makeText(a, msg, Toast.LENGTH_LONG).show(); });
    }

    private static void check(Activity a, boolean manual) {
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (busy) { if (manual) say(a, "Already checking for updates…"); return; }
        if (!manual && now - p.getLong("checked", 0) < CHECK_EVERY) return;
        busy = true;
        if (manual) say(a, "Checking for updates…");
        p.edit().putLong("checked", now).apply();
        new Thread(() -> {
            try {
                String badging = fetchFresh(a, "badging.txt", now);
                Matcher m = Pattern.compile("versionCode='(\\d+)'\\s+versionName='([^']*)'").matcher(badging);
                if (!m.find()) { if (manual) say(a, "Couldn't check for updates. Try again in a bit."); return; }
                int code = Integer.parseInt(m.group(1));
                String name = m.group(2);
                if (code <= BuildConfig.VERSION_CODE) { if (manual) say(a, "You're on the latest version (" + BuildConfig.VERSION_NAME + ")."); return; }
                if (!manual && p.getInt("snoozeCode", -1) == code && now - p.getLong("snoozeAt", 0) < SNOOZE) return;
                String notes = whatsNew(a);
                new Handler(Looper.getMainLooper()).post(() -> offer(a, code, name, notes));
            } catch (Exception e) {
                // offline or GitHub unreachable: try again next time
                if (manual) say(a, "Couldn't check for updates. Check the internet and try again.");
            } finally {
                busy = false;
            }
        }).start();
    }

    /** The newest change's description, from the build's commit (best effort). */
    private static String whatsNew(Context c) {
        try {
            String j = fetchText("https://api.github.com/repos/" + REPO + "/commits/" + codeBranch(c));
            String msg = new org.json.JSONObject(j).getJSONObject("commit").getString("message");
            String first = msg.split("\n")[0].trim();
            return first.replaceFirst("^(VibeSync TV )?v?\\d+(\\.\\d+)*:\\s*", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static void offer(Activity a, int code, String name, String notes) {
        if (a.isFinishing()) return;
        String msg = "Version " + name + " is ready (you have " + BuildConfig.VERSION_NAME + ")."
                + (notes.isEmpty() ? "" : "\n\nWhat's new: " + notes)
                + "\n\nIt downloads here and installs over this one. Your saved titles and settings stay.";
        new AlertDialog.Builder(a)
                .setTitle(appLabel(a) + " " + name + " is available")
                .setMessage(msg)
                .setPositiveButton("Update", (d, w) -> download(a, name))
                .setNegativeButton("Later", (d, w) -> a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt("snoozeCode", code).putLong("snoozeAt", System.currentTimeMillis()).apply())
                .show();
    }

    /** Downloads the APK straight into an Android install session, showing progress, then asks to install. */
    private static void download(Activity a, String name) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView label = new TextView(a);
        label.setText("Downloading " + appLabel(a) + " " + name + "…");
        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(label);
        box.addView(bar);
        AlertDialog dlg = new AlertDialog.Builder(a).setTitle("Updating").setView(box).setCancelable(false).show();
        Handler ui = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                HttpURLConnection c = openFresh(a, apkName(a));
                long total = c.getContentLengthLong();
                PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(a.getPackageName());
                int id = pi.createSession(params);
                session = pi.openSession(id);
                try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("vibesync.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[65536];
                    long done = 0;
                    int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            ui.post(() -> { if (p >= 0) bar.setProgress(p); else bar.setIndeterminate(true); });
                        }
                    }
                    session.fsync(out);
                }
                Intent status = new Intent(a, UpdateReceiver.class).setAction(ACTION_STATUS);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent sender = PendingIntent.getBroadcast(a, id, status, flags);
                session.commit(sender.getIntentSender());
                session.close();
                session = null;
                ui.post(() -> { label.setText("Installing… confirm on the next screen."); bar.setProgress(100); });
                ui.postDelayed(dlg::dismiss, 1500);
            } catch (Exception e) {
                if (session != null) session.abandon();
                ui.post(() -> {
                    dlg.dismiss();
                    Toast.makeText(a, "Couldn't download the update. Check the internet and try again.", Toast.LENGTH_LONG).show();
                });
                a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("checked", 0).apply();
            }
        }).start();
    }

    /**
     * A published file, straight from GitHub (the API, so it's never a cached older copy); if the API says no
     * (it allows about 60 checks an hour per network), the regular file link instead.
     */
    private static HttpURLConnection openFresh(Context ctx, String file) throws Exception {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/contents/" + file + "?ref=" + branch(ctx)).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/vnd.github.raw");
            c.setRequestProperty("User-Agent", "VibeSync-Updater");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() == 200) return c;
            c.disconnect();
        } catch (Exception ignored) { }
        HttpURLConnection c = (HttpURLConnection) new URL("https://raw.githubusercontent.com/" + REPO + "/" + branch(ctx) + "/" + file + "?t=" + System.currentTimeMillis()).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "VibeSync-Updater");
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    private static String fetchFresh(Context ctx, String file, long now) throws Exception {
        HttpURLConnection c = openFresh(ctx, file);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 400_000) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static String fetchText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "VibeSync-Updater");
        c.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 400_000) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }
}
