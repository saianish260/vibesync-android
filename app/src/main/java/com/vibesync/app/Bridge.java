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
            SharedPreferences.Editor e = p.edit().putString("queue", q.toString()).putInt("qi", 0);
            // for the widget's own refills (PickWidget.topUp): the TMDB key, genres you like, titles to leave out
            if (o.has("key")) e.putString("tmdbKey", o.optString("key"));
            if (o.has("likeGenres")) e.putString("likeGenres", o.getJSONArray("likeGenres").toString());
            if (o.has("avoid")) e.putString("avoid", o.getJSONArray("avoid").toString());
            e.apply();
            // a locked pick (or yesterday's question) stays on the widget; the new queue waits behind it
            new Thread(() -> {
                PickWidget.topUp(act);                         // a short list: the widget adds more of its own
                PickWidget.ensurePoster(act);
                PickWidget.refresh(act);
            }).start();
        } catch (Exception ignored) { }
    }

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

    /**
     * Google sign-in with Android's own account picker (Google blocks its web sign-in inside apps).
     * clientId is the Web client ID; the Google ID token comes back through authTake(key).
     */
    private static final java.util.Map<String, String> AUTH = new java.util.concurrent.ConcurrentHashMap<>();

    @JavascriptInterface
    public void googleSignIn(String clientId, String key) {
        if (!allowed.ok() || clientId == null || key == null) return;
        act.runOnUiThread(() -> {
            try {
                androidx.credentials.CredentialManager cm = androidx.credentials.CredentialManager.create(act);
                com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption opt =
                        new com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption.Builder(clientId).build();
                androidx.credentials.GetCredentialRequest req = new androidx.credentials.GetCredentialRequest.Builder().addCredentialOption(opt).build();
                cm.getCredentialAsync(act, req, new android.os.CancellationSignal(), java.util.concurrent.Executors.newSingleThreadExecutor(),
                    new androidx.credentials.CredentialManagerCallback<androidx.credentials.GetCredentialResponse, androidx.credentials.exceptions.GetCredentialException>() {
                        @Override public void onResult(androidx.credentials.GetCredentialResponse r) {
                            androidx.credentials.Credential c = r.getCredential();
                            if (c instanceof androidx.credentials.CustomCredential
                                    && com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(c.getType())) {
                                try {
                                    String token = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
                                            .createFrom(((androidx.credentials.CustomCredential) c).getData()).getIdToken();
                                    AUTH.put(key, token);
                                    LAST_TOKEN = token; LAST_AT = System.currentTimeMillis();
                                } catch (Throwable e) { AUTH.put(key, "error:parse"); }
                            } else AUTH.put(key, "error:unexpected");
                        }
                        @Override public void onError(androidx.credentials.exceptions.GetCredentialException e) {
                            AUTH.put(key, "error:" + e.getClass().getSimpleName() + " " + e.getMessage());
                        }
                    });
            } catch (Throwable e) { AUTH.put(key, "error:" + e.getClass().getSimpleName()); }   // never let sign-in close the app
        });
    }

    @JavascriptInterface
    public String authTake(String key) {
        String r = key == null ? null : AUTH.remove(key);
        if (r != null && !r.startsWith("error:")) LAST_TOKEN = null;     // the page has it
        return r == null ? "" : r;
    }

    /** A Google sign-in that finished while the page was being restarted (within the last 5 minutes), once. */
    private static volatile String LAST_TOKEN;
    private static volatile long LAST_AT;

    @JavascriptInterface
    public String authLast() {
        if (!allowed.ok()) return "";
        String t = LAST_TOKEN;
        LAST_TOKEN = null;
        return t != null && System.currentTimeMillis() - LAST_AT < 5 * 60_000 ? t : "";
    }

    @JavascriptInterface
    public void googleSignOut() {
        if (!allowed.ok()) return;
        act.runOnUiThread(() -> {
            try {
                androidx.credentials.CredentialManager.create(act).clearCredentialStateAsync(new androidx.credentials.ClearCredentialStateRequest(),
                    new android.os.CancellationSignal(), java.util.concurrent.Executors.newSingleThreadExecutor(),
                    new androidx.credentials.CredentialManagerCallback<Void, androidx.credentials.exceptions.ClearCredentialException>() {
                        @Override public void onResult(Void v) { }
                        @Override public void onError(androidx.credentials.exceptions.ClearCredentialException e) { }
                    });
            } catch (Exception ignored) { }
        });
    }

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

    /** Details of the titles the widget fetched by itself ({id: {...}}), so VibeSync can show and remember them. */
    @JavascriptInterface
    public String widgetMeta() {
        if (!allowed.ok()) return "{}";
        return act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("wmeta", "{}");
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
