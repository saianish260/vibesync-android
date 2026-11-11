package com.vibesync.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Random;

/**
 * Home-screen widget: "Tonight's pick". VibeSync sends a short queue of picks each time it opens.
 * Tapping the widget opens that title in VibeSync; "Watched it" marks it watched (VibeSync syncs it
 * the next time it opens) and moves on to the next pick in the queue; "Skip" moves on.
 *
 * Lock: one tap on the lock keeps tonight's pick on the widget until midnight (opening VibeSync doesn't
 * change it, and Skip is hidden). Three quick taps on the lock unlock it early. At midnight the widget asks
 * "Did you watch it?": Yes marks it watched; No keeps it off the widget for a few random days, after which
 * it can come back. Either answer brings the normal widget back. "Watched it" while locked counts as Yes.
 * After unlocking, that title stays on the widget until Watched it / Skip / Lock (opening VibeSync doesn't change it).
 */
public class PickWidget extends AppWidgetProvider {
    static final String ACTION_WATCHED = "com.vibesync.app.PICK_WATCHED";
    static final String ACTION_SKIP = "com.vibesync.app.PICK_SKIP";
    static final String ACTION_LOCK = "com.vibesync.app.PICK_LOCK";
    static final String ACTION_DAYEND = "com.vibesync.app.PICK_DAYEND";
    private static final long TAP_WINDOW = 2500;              // each of the three taps within this of the one before
    /** Widget taps are handled strictly one after another (in tap order), so quick taps are all counted. */
    private static final java.util.concurrent.ExecutorService ONE = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, views(ctx));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String a = intent.getAction() == null ? "" : intent.getAction();
        if (a.equals(ACTION_WATCHED) || a.equals(ACTION_SKIP) || a.equals(ACTION_LOCK) || a.equals(ACTION_DAYEND)) {
            final PendingResult done = goAsync();
            final Context app = ctx.getApplicationContext();
            ONE.execute(() -> {
                try {
                    switch (a) {
                        case ACTION_WATCHED: onWatched(app); break;
                        case ACTION_SKIP: onSkip(app); break;
                        case ACTION_LOCK: onLock(app); break;
                        default: dayEnded(app);
                    }
                } catch (Exception ignored) { }
                refresh(app);                                  // show the tap count right away
                done.finish();
                if (!a.equals(ACTION_LOCK)) { ensurePoster(app); refresh(app); }
                else new Thread(() -> {                       // the "2 more taps" hint fades if the taps stop
                    try { Thread.sleep(TAP_WINDOW + 150); } catch (InterruptedException ignored) { }
                    ONE.execute(() -> { ensurePoster(app); refresh(app); });
                }).start();
            });
            return;
        }
        super.onReceive(ctx, intent);
    }

    static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE); }

    // ───────── what the widget is showing ─────────

    /** The locked pick, while it's locked (before midnight). */
    static JSONObject locked(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getLong("lockUntil", 0) <= System.currentTimeMillis()) return null;
        try { return new JSONObject(p.getString("lock", "")); } catch (Exception e) { return null; }
    }

    /** Yesterday's locked pick, waiting for "Did you watch it?". */
    static JSONObject asking(Context ctx) {
        dayEnded(ctx);                                         // in case the midnight alarm didn't run (phone off, etc.)
        try { return new JSONObject(prefs(ctx).getString("ask", "")); } catch (Exception e) { return null; }
    }

    /** Titles answered "No": kept off the widget until a random day. */
    private static boolean cooling(SharedPreferences p, String id) {
        try {
            JSONObject c = new JSONObject(p.getString("cool", "{}"));
            return c.optLong(id, 0) > System.currentTimeMillis();
        } catch (Exception e) { return false; }
    }

    /** The first pick in the queue from position i that isn't resting after a "No". */
    private static int firstFrom(SharedPreferences p, JSONArray q, int i) {
        while (i < q.length() && cooling(p, q.optJSONObject(i) == null ? "" : q.optJSONObject(i).optString("id"))) i++;
        return i;
    }

    /** A title just unlocked: it stays on the widget until Watched it / Skip / Lock, whatever the app sends meanwhile. */
    static JSONObject held(Context ctx) {
        try { return new JSONObject(prefs(ctx).getString("hold", "")); } catch (Exception e) { return null; }
    }

    /** The normal widget's pick: an unlocked title it's holding on to, else the queue's current pick. */
    static JSONObject queued(Context ctx) {
        JSONObject h = held(ctx);
        if (h != null) return h;
        try {
            SharedPreferences p = prefs(ctx);
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            int i = firstFrom(p, q, p.getInt("qi", 0));
            return i < q.length() ? q.getJSONObject(i) : null;
        } catch (Exception e) { return null; }
    }

    /** What the widget shows right now: the question, else the locked pick, else the queue's pick. */
    static JSONObject current(Context ctx) {
        JSONObject j = asking(ctx);
        if (j == null) j = locked(ctx);
        if (j == null) j = queued(ctx);
        return j;
    }

    // ───────── buttons ─────────

    private static void onWatched(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        JSONObject ask = asking(ctx), lock = locked(ctx);
        if (ask != null || lock != null) {                     // "Yes" (or Watched it while locked)
            String id = (ask != null ? ask : lock).optString("id");
            addTo(p, "watched", id);
            p.edit().remove("ask").remove("lock").remove("lockUntil").remove("hold").apply();
            cancelDayEnd(ctx);
            stepPast(ctx, id);
            return;
        }
        next(ctx, "watched");
    }

    private static void onSkip(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        JSONObject ask = asking(ctx);
        if (ask != null) {                                     // "No": rest it for a few random days, then it can come back
            String id = ask.optString("id");
            JSONObject c = new JSONObject(p.getString("cool", "{}"));
            long now = System.currentTimeMillis(), day = 24L * 3600_000L;
            for (Iterator<String> it = c.keys(); it.hasNext(); ) if (c.optLong(it.next()) < now) it.remove();   // tidy old ones
            c.put(id, now + (3 + new Random().nextInt(8)) * day);       // 3 to 10 days
            p.edit().putString("cool", c.toString()).remove("ask").remove("hold").apply();
            stepPast(ctx, id);
            return;
        }
        if (locked(ctx) != null) return;                       // Skip is hidden while locked
        next(ctx, "skipped");
    }

    private static void onLock(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        if (asking(ctx) != null) return;
        long now = System.currentTimeMillis();
        if (locked(ctx) == null) {                             // lock tonight's pick until midnight
            JSONObject j = queued(ctx); if (j == null) return;
            long until = nextMidnight();
            copy(new File(ctx.getFilesDir(), "pick.png"), new File(ctx.getFilesDir(), "lock.png"));
            p.edit().putString("lock", j.toString()).putLong("lockUntil", until).putInt("lockTaps", 0).remove("hold")
                    .putString("lockImgFor", p.getString("pickFor", "").equals(j.optString("id")) ? j.optString("id") : "").apply();
            armDayEnd(ctx, until);
            return;
        }
        // locked: three quick taps unlock it
        int taps = now - p.getLong("lockTapAt", 0) < TAP_WINDOW ? p.getInt("lockTaps", 0) + 1 : 1;
        if (taps >= 3) {                                   // unlocked: the same title stays until you act on it
            String lock = p.getString("lock", "");
            String id = "";
            try { id = new JSONObject(lock).optString("id"); } catch (Exception ignored) { }
            copy(new File(ctx.getFilesDir(), "lock.png"), new File(ctx.getFilesDir(), "pick.png"));
            p.edit().putString("hold", lock).putString("pickFor", id.equals(p.getString("lockImgFor", "")) ? id : "")
                    .remove("lock").remove("lockUntil").putInt("lockTaps", 0).putLong("lockTapAt", 0).commit();
            cancelDayEnd(ctx);
        } else {
            p.edit().putInt("lockTaps", taps).putLong("lockTapAt", now).commit();
        }
    }

    /** Midnight: a locked pick becomes the question. */
    static void dayEnded(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String lock = p.getString("lock", "");
        long until = p.getLong("lockUntil", 0);
        if (lock.isEmpty() || until == 0 || until > System.currentTimeMillis()) return;
        p.edit().putString("ask", lock).remove("lock").remove("lockUntil").putInt("lockTaps", 0).apply();
    }

    /** After the question / an early "Watched it": if the queue is still on that title, move past it. */
    private static void stepPast(Context ctx, String id) throws Exception {
        SharedPreferences p = prefs(ctx);
        JSONArray q = new JSONArray(p.getString("queue", "[]"));
        int i = firstFrom(p, q, p.getInt("qi", 0));
        if (i < q.length() && id.equals(q.getJSONObject(i).optString("id"))) i++;
        p.edit().putInt("qi", Math.min(i, q.length())).apply();
    }

    private static void addTo(SharedPreferences p, String list, String id) throws Exception {
        JSONArray w = new JSONArray(p.getString(list, "[]"));
        w.put(id);
        p.edit().putString(list, w.toString()).apply();
    }

    /** Mark the current pick watched (or skipped), step to the next one and fetch its poster. */
    static void next(Context ctx, String list) throws Exception {
        SharedPreferences p = prefs(ctx);
        JSONObject h = held(ctx);
        if (h != null) {                                       // the held (unlocked) title: act on it, then back to the queue
            String id = h.optString("id");
            addTo(p, list, id);
            p.edit().remove("hold").putString("pickFor", "").apply();
            stepPast(ctx, id);
            return;
        }
        JSONArray q = new JSONArray(p.getString("queue", "[]"));
        int i = firstFrom(p, q, p.getInt("qi", 0));
        if (i < q.length()) addTo(p, list, q.getJSONObject(i).optString("id"));
        i = firstFrom(p, q, i + 1);
        if (i >= q.length()) i = q.length();                 // ran out: the widget asks to open VibeSync for more
        p.edit().putInt("qi", i).apply();
        if (i >= q.length()) new File(ctx.getFilesDir(), "pick.png").delete();
    }

    // ───────── midnight alarm ─────────

    static long nextMidnight() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 5); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static PendingIntent dayEndIntent(Context ctx) {
        Intent i = new Intent(ctx, PickWidget.class).setAction(ACTION_DAYEND);
        return PendingIntent.getBroadcast(ctx, 9, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void armDayEnd(Context ctx, long at) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC, at, dayEndIntent(ctx));
    }

    private static void cancelDayEnd(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(dayEndIntent(ctx));
    }

    /** After a reboot / app update: re-arm the midnight alarm, or ask right away if midnight has passed. */
    static void rearm(Context ctx) {
        SharedPreferences p = prefs(ctx);
        long until = p.getLong("lockUntil", 0);
        if (until > System.currentTimeMillis()) armDayEnd(ctx, until);
        else dayEnded(ctx);
        refresh(ctx);
    }

    // ───────── posters ─────────

    static void savePoster(Context ctx, String img) { savePoster(ctx, img, ""); }

    static void savePoster(Context ctx, String img, String id) {
        File f = new File(ctx.getFilesDir(), "pick.png");
        if (img == null || !img.startsWith("https://")) { f.delete(); prefs(ctx).edit().putString("pickFor", "").apply(); return; }
        if (download(img, f)) prefs(ctx).edit().putString("pickFor", id).apply();
    }

    private static boolean download(String img, File f) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(img).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b == null) return false;
                int w = 220, h = Math.round(w * (float) b.getHeight() / b.getWidth());
                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                try (FileOutputStream out = new FileOutputStream(f)) { s.compress(Bitmap.CompressFormat.PNG, 92, out); }
                return true;
            }
        } catch (Exception ignored) { return false; }
    }

    private static void copy(File from, File to) {
        if (!from.exists()) { to.delete(); return; }
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) { }
    }

    /** Make sure the saved posters match what the widget shows (call off the main thread). */
    static void ensurePoster(Context ctx) {
        SharedPreferences p = prefs(ctx);
        JSONObject fixed = asking(ctx); if (fixed == null) fixed = locked(ctx);
        if (fixed != null && !fixed.optString("id").equals(p.getString("lockImgFor", ""))) {
            if (download(fixed.optString("img"), new File(ctx.getFilesDir(), "lock.png")))
                p.edit().putString("lockImgFor", fixed.optString("id")).apply();
        }
        JSONObject q = queued(ctx);
        if (q != null && !q.optString("id").equals(p.getString("pickFor", ""))) savePoster(ctx, q.optString("img"), q.optString("id"));
    }

    // ───────── drawing ─────────

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, PickWidget.class));
        if (ids.length > 0) mgr.updateAppWidget(ids, views(ctx));
    }

    static RemoteViews views(Context ctx) {
        SharedPreferences p = prefs(ctx);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_pick);
        JSONObject ask = asking(ctx), lock = ask == null ? locked(ctx) : null;
        JSONObject j = ask != null ? ask : lock != null ? lock : queued(ctx);
        String id = j == null ? "" : j.optString("id");

        v.setTextViewText(R.id.w_head, ask != null ? "🌙 YESTERDAY'S PICK" : lock != null ? "🔒 TONIGHT'S PICK · LOCKED" : "🍿 TONIGHT'S PICK");
        v.setTextViewText(R.id.w_title, j == null ? "Open VibeSync" : j.optString("title"));
        v.setTextViewText(R.id.w_sub, j == null ? "Tap for tonight's next pick" : ask != null ? "Did you watch it?" : j.optString("sub"));
        v.setTextViewText(R.id.w_line, j == null ? "" : ask != null ? "Yes marks it watched · No brings it back another day" : j.optString("line"));
        v.setViewVisibility(R.id.w_btns, j == null ? View.GONE : View.VISIBLE);

        // buttons: normal  ✓ Watched it · Skip ⏭ · 🔓   |   locked  ✓ Watched it · 🔒   |   question  ✓ Yes · ✗ No
        v.setTextViewText(R.id.w_watched, ask != null ? "✓ Yes" : "✓ Watched it");
        v.setTextViewText(R.id.w_skip, ask != null ? "✗ No" : "Skip ⏭");
        v.setViewVisibility(R.id.w_skip, lock != null ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.w_lock, ask != null ? View.GONE : View.VISIBLE);
        int taps = System.currentTimeMillis() - p.getLong("lockTapAt", 0) < TAP_WINDOW ? p.getInt("lockTaps", 0) : 0;
        v.setTextViewText(R.id.w_lock, lock == null ? "🔓 Lock" : taps == 1 ? "🔒 2 more taps" : taps == 2 ? "🔒 1 more tap" : "🔒 Locked");

        File f = new File(ctx.getFilesDir(), ask != null || lock != null ? "lock.png" : "pick.png");
        Bitmap b = j != null && f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (b != null) v.setImageViewBitmap(R.id.w_poster, b); else v.setImageViewResource(R.id.w_poster, R.mipmap.ic_launcher);

        // tap: open this exact title in VibeSync
        Intent open = new Intent(ctx, MainActivity.class).setAction("com.vibesync.app.OPEN." + id)
                .putExtra(MainActivity.EXTRA_OPEN, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent watched = new Intent(ctx, PickWidget.class).setAction(ACTION_WATCHED);
        v.setOnClickPendingIntent(R.id.w_watched, PendingIntent.getBroadcast(ctx, 2, watched, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent skip = new Intent(ctx, PickWidget.class).setAction(ACTION_SKIP);
        v.setOnClickPendingIntent(R.id.w_skip, PendingIntent.getBroadcast(ctx, 3, skip, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent lk = new Intent(ctx, PickWidget.class).setAction(ACTION_LOCK);
        v.setOnClickPendingIntent(R.id.w_lock, PendingIntent.getBroadcast(ctx, 4, lk, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
