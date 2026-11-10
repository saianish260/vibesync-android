package com.vibesync.app;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;


/**
 * window.VibeSyncApp inside the VibeSync page:
 *   schedule(id, title, body, atMillis)  new-episode notification at that time (survives reboots)
 *   cancel(id)
 *   requestNotify()                       asks for the notification permission (Android 13+)
 *   setPick(json)                         "tonight's pick" for the home-screen widget
 * Calls only work while the VibeSync site itself is open (not on outside pages loaded in the WebView).
 */
public class Bridge {
    static final String PREFS = "vibesync_bridge";
    private final Activity act;
    private final Allowed allowed;

    interface Allowed { boolean ok(); }

    Bridge(Activity act, Allowed allowed) { this.act = act; this.allowed = allowed; }

    @JavascriptInterface
    public void schedule(String id, String title, String body, double atMillis) {
        if (!allowed.ok() || id == null) return;
        long at = (long) atMillis;
        try {
            SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString("reminders", "{}"));
            all.put(id, new JSONObject().put("title", title).put("body", body).put("at", at));
            p.edit().putString("reminders", all.toString()).apply();
        } catch (Exception ignored) { }
        arm(act, id, title, body, at);
    }

    @JavascriptInterface
    public void cancel(String id) {
        if (!allowed.ok() || id == null) return;
        try {
            SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString("reminders", "{}"));
            all.remove(id);
            p.edit().putString("reminders", all.toString()).apply();
        } catch (Exception ignored) { }
        AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(act, id, "", ""));
    }

    @JavascriptInterface
    public void requestNotify() {
        if (!allowed.ok() || Build.VERSION.SDK_INT < 33) return;
        act.runOnUiThread(() -> {
            if (act.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
                act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        });
    }

    /** json: {"queue": [{id, title, sub, img, line}, ...]}, tonight's pick first. */
    @JavascriptInterface
    public void setPick(String json) {
        if (!allowed.ok() || json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            org.json.JSONArray q = o.has("queue") ? o.getJSONArray("queue") : new org.json.JSONArray().put(o);
            SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String first = q.length() > 0 ? q.getJSONObject(0).optString("id") : "";
            JSONObject cur = PickWidget.current(act);
            boolean same = cur != null && first.equals(cur.optString("id"));
            p.edit().putString("queue", q.toString()).putInt("qi", 0).apply();
            new Thread(() -> {
                if (!same) try { PickWidget.savePoster(act, q.getJSONObject(0).optString("img")); } catch (Exception ignored) { }
                PickWidget.refresh(act);
            }).start();
        } catch (Exception ignored) { }
    }

    /** Titles marked "Watched it" on the widget since VibeSync last asked (a JSON array of ids). */
    @JavascriptInterface
    public String takeWidgetWatched() {
        if (!allowed.ok()) return "[]";
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String w = p.getString("watched", "[]");
        p.edit().remove("watched").apply();
        return w;
    }

    /** The title a widget or notification tap asked to open, once. */
    @JavascriptInterface
    public String takeOpen() {
        if (!allowed.ok()) return "";
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString("open", "");
        if (!id.isEmpty()) p.edit().remove("open").apply();
        return id;
    }

    static PendingIntent pending(Context ctx, String id, String title, String body) {
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction("com.vibesync.app.REMIND")
                .putExtra("id", id).putExtra("title", title).putExtra("body", body);
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void arm(Context ctx, String id, String title, String body, long at) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null || at < System.currentTimeMillis() - 60_000) return;
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(at, System.currentTimeMillis() + 5_000), pending(ctx, id, title, body));
    }
}
