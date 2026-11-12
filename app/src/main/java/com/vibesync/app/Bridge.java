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
 *   setPick(json)                         (phone widget only; ignored on TV)
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

    /** The page sends tonight's pick for the phone's home-screen widget; TVs have no widgets, so it's ignored. */
    @JavascriptInterface
    public void setPick(String json) { }

    /** Audio / subtitle languages per app: the page's JustWatch query, sent from the phone (fixed endpoint). */
    private static final java.util.Map<String, String> JW = new java.util.concurrent.ConcurrentHashMap<>();

    @JavascriptInterface
    public void jwStart(String key, String body) {
        if (!allowed.ok() || key == null || body == null || body.length() > 8000) return;
        new Thread(() -> {
            String out = "";
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://apis.justwatch.com/graphql").openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(10000); c.setDoOutput(true); c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Accept-Language", "en-IN,en;q=0.8");
                try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0 && b.size() < 2_000_000) b.write(buf, 0, n);
                    out = b.toString("UTF-8");
                }
            } catch (Exception e) { out = "{}"; }
            JW.put(key, out);
        }).start();
    }

    @JavascriptInterface
    public String jwTake(String key) {
        String r = key == null ? null : JW.remove(key);
        return r == null ? "" : r;
    }

    /**
     * OTT news check (apps only): the latest Indian news headlines for a search such as
     * "Ranabaali OTT release date", from Google News India's RSS feed (fixed endpoint, read-only).
     * The page reads the platform and date out of the headlines; this only fetches and lists them.
     */
    private static final java.util.Map<String, String> NEWS = new java.util.concurrent.ConcurrentHashMap<>();

    @JavascriptInterface
    public void newsStart(String key, String query) {
        if (!allowed.ok() || key == null || query == null || query.length() > 200) return;
        new Thread(() -> {
            org.json.JSONArray items = new org.json.JSONArray();
            try {
                String url = "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                        + "&hl=en-IN&gl=IN&ceid=IN:en";
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) VibeSync");
                String xml;
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0 && b.size() < 1_500_000) b.write(buf, 0, n);
                    xml = b.toString("UTF-8");
                }
                java.util.regex.Matcher it = java.util.regex.Pattern.compile("<item>(.*?)</item>", java.util.regex.Pattern.DOTALL).matcher(xml);
                while (it.find() && items.length() < 25) {
                    String x = it.group(1);
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("t", tag(x, "title"));
                    o.put("s", tag(x, "source"));
                    o.put("d", tag(x, "pubDate"));
                    o.put("u", tag(x, "link"));
                    items.put(o);
                }
            } catch (Exception ignored) { }
            NEWS.put(key, items.toString());
        }).start();
    }

    @JavascriptInterface
    public String newsTake(String key) {
        String r = key == null ? null : NEWS.remove(key);
        return r == null ? "" : r;
    }

    private static String tag(String x, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<" + name + "[^>]*>(.*?)</" + name + ">", java.util.regex.Pattern.DOTALL).matcher(x);
        if (!m.find()) return "";
        String v = m.group(1).replace("<![CDATA[", "").replace("]]>", "");
        return v.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").trim();
    }

    // VibeSync TV has no Google sign-in: everything is saved on the TV itself.

    /** "Check for updates" in the menu: checks right away and says what it found (a popup if there's a newer version). */
    @JavascriptInterface
    public void checkUpdate() {
        if (!allowed.ok()) return;
        act.runOnUiThread(() -> Updater.checkNow(act));
    }

    /** This app's version, shown next to "Check for updates". */
    @JavascriptInterface
    public String appVersion() { return BuildConfig.VERSION_NAME; }

    /** Titles marked "Watched it" on the widget since VibeSync last asked (a JSON array of ids). */
    @JavascriptInterface
    public String takeWidgetWatched() {
        if (!allowed.ok()) return "[]";
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String w = p.getString("watched", "[]");
        p.edit().remove("watched").apply();
        return w;
    }

    /** Titles skipped on the widget since VibeSync last asked (a JSON array of ids). */
    @JavascriptInterface
    public String takeWidgetSkipped() {
        if (!allowed.ok()) return "[]";
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String w = p.getString("skipped", "[]");
        p.edit().remove("skipped").apply();
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
