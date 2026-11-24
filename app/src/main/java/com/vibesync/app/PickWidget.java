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
import java.io.FileOutputStream;
import java.io.InputStream;
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
 * "♡ Save" (on the poster) adds the pick to your Saved list (VibeSync takes it the next time it opens) and moves on.
 * Saved titles are never picked again: not from the queue, not by the refills.
 */
public class PickWidget extends AppWidgetProvider {
    static final String ACTION_WATCHED = "com.vibesync.app.PICK_WATCHED";
    static final String ACTION_SKIP = "com.vibesync.app.PICK_SKIP";
    static final String ACTION_LOCK = "com.vibesync.app.PICK_LOCK";
    static final String ACTION_DAYEND = "com.vibesync.app.PICK_DAYEND";
    static final String ACTION_SAVE = "com.vibesync.app.PICK_SAVE";
    private static final long TAP_WINDOW = 2500;              // each of the three taps within this of the one before
    /** Widget taps are handled strictly one after another (in tap order), so quick taps are all counted.
     *  This thread never touches the internet: every tap is answered at once from what's already on the phone. */
    static final java.util.concurrent.ExecutorService ONE = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** Everything that needs the internet (more titles, posters) runs here, in the background, and only when online. */
    private static final java.util.concurrent.ExecutorService NET = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean SYNCING = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean AGAIN = new java.util.concurrent.atomic.AtomicBoolean();
    static final int JOB_ID = 4242;
    /** VibeSync just sent new picks: redraw once the new first pick's poster is here (no title-only flash). */
    static volatile boolean DIRTY = false;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, views(ctx, id));
        syncSoon(ctx.getApplicationContext(), null);            // e.g. after an app update: fetch what's missing (when online)
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final String a = intent.getAction() == null ? "" : intent.getAction();
        if (a.equals(ACTION_WATCHED) || a.equals(ACTION_SKIP) || a.equals(ACTION_LOCK) || a.equals(ACTION_DAYEND) || a.equals(ACTION_SAVE)) {
            final PendingResult done = goAsync();
            final Context app = ctx.getApplicationContext();
            ONE.execute(() -> {
                try {
                    switch (a) {
                        case ACTION_WATCHED: onWatched(app); break;
                        case ACTION_SKIP: onSkip(app); break;
                        case ACTION_LOCK: onLock(app); break;
                        case ACTION_SAVE: onSave(app); break;
                        default: dayEnded(app);
                    }
                } catch (Exception ignored) { }
                refresh(app);                                  // the next title, its poster already on the phone: instant
                done.finish();
                if (!a.equals(ACTION_LOCK)) syncSoon(app, null);   // top up titles / posters in the background (online only)
                else new Thread(() -> {                        // the "2 more taps" hint fades if the taps stop
                    try { Thread.sleep(TAP_WINDOW + 150); } catch (InterruptedException ignored) { }
                    ONE.execute(() -> refresh(app));
                }).start();
            });
            return;
        }
        super.onReceive(ctx, intent);
    }

    /** Resized: redraw the poster cut to the new shape, so it stays sharp at every size. */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, android.os.Bundle options) {
        mgr.updateAppWidget(id, views(ctx, id));
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
        java.util.Set<String> saved = savedSet(p);
        while (i < q.length()) {
            String id = q.optJSONObject(i) == null ? "" : q.optJSONObject(i).optString("id");
            if (!cooling(p, id) && !saved.contains(id)) break;
            i++;
        }
        return i;
    }

    /** Titles saved on the widget (kept, newest 1500): never picked again. Saved in VibeSync arrive in "avoid". */
    private static java.util.Set<String> savedSet(SharedPreferences p) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String list : new String[]{"savedKeep", "saved"}) {
            try { JSONArray a = new JSONArray(p.getString(list, "[]")); for (int k = 0; k < a.length(); k++) s.add(a.optString(k)); } catch (Exception ignored) { }
        }
        return s;
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

    /** ♡ Save: the normal widget's pick goes to Saved and the next pick comes up (hidden while locked / asking). */
    private static void onSave(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        if (asking(ctx) != null || locked(ctx) != null) return;
        JSONObject j = queued(ctx); if (j == null) return;
        String id = j.optString("id"); if (id.isEmpty()) return;
        JSONArray keep = new JSONArray(p.getString("savedKeep", "[]"));
        keep.put(id);
        JSONArray k2 = new JSONArray();
        for (int k = Math.max(0, keep.length() - 1500); k < keep.length(); k++) k2.put(keep.get(k));
        p.edit().putString("savedKeep", k2.toString()).apply();
        next(ctx, "saved");                                    // into "saved" for VibeSync, and on to the next pick
    }

    private static void onLock(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        if (asking(ctx) != null) return;
        long now = System.currentTimeMillis();
        if (locked(ctx) == null) {                             // lock tonight's pick until midnight
            JSONObject j = queued(ctx); if (j == null) return;
            long until = nextMidnight();
            p.edit().putString("lock", j.toString()).putLong("lockUntil", until).putInt("lockTaps", 0).remove("hold").apply();
            armDayEnd(ctx, until);
            return;
        }
        // locked: three quick taps unlock it
        int taps = now - p.getLong("lockTapAt", 0) < TAP_WINDOW ? p.getInt("lockTaps", 0) + 1 : 1;
        if (taps >= 3) {                                   // unlocked: the same title stays until you act on it
            String lock = p.getString("lock", "");
            String id = "";
            try { id = new JSONObject(lock).optString("id"); } catch (Exception ignored) { }
            p.edit().putString("hold", lock)
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
            p.edit().remove("hold").apply();
            stepPast(ctx, id);
            return;
        }
        JSONArray q = new JSONArray(p.getString("queue", "[]"));
        int i = firstFrom(p, q, p.getInt("qi", 0));
        if (i < q.length()) { addTo(p, list, q.getJSONObject(i).optString("id")); noteShown(ctx, q.getJSONObject(i).optString("id")); }
        i = firstFrom(p, q, i + 1);
        if (i >= q.length()) i = q.length();                 // ran out (no internet for a refill): it asks to open VibeSync
        p.edit().putInt("qi", i).apply();
    }

    // ───────── an endless queue ─────────
    // VibeSync sends its own picks when it opens. When fewer than LOW are left, the widget fetches more by itself
    // from TMDB: titles streaming in India, a random page of popular movies or series (sometimes in the genres
    // you like), skipping anything it already showed, you watched or skipped, or VibeSync said to avoid.
    static final int READY = 500;                              // keep ~500 titles (with posters) ready ahead: feels endless, offline too
    private static final int LOW = READY - 20;                 // top up once fewer than this are left

    // anime: animation made in Japanese
    static boolean isAnime(JSONObject t) {
        JSONArray g = t.optJSONArray("genre_ids");
        if (g == null || !"ja".equals(t.optString("original_language"))) return false;
        boolean anim = false;
        for (int i = 0; i < g.length(); i++) { if (g.optInt(i) == 10762) return false; if (g.optInt(i) == 16) anim = true; }   // Kids = a cartoon, keep it
        if (!anim) return false;
        String n = t.optString("title", "") + " " + t.optString("name", "") + " " + t.optString("original_title", "") + " " + t.optString("original_name", "");
        return !CARTOON.matcher(n).find();
    }
    // Kids' cartoons are not anime: these stay for everyone.
    static final java.util.regex.Pattern CARTOON = java.util.regex.Pattern.compile(
        "doraemon|shin ?-?chan|crayon shin|hattori|perman|kiteretsu|pok[eé]mon|beyblade|hamtaro|chibi maruko|anpanman|obocchama|kochikame|kitty|oggy|bakugan|yo-kai|duel masters|chhota|chota bheem",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Fetch more titles when fewer than LOW are left (internet thread). False if it couldn't reach TMDB. */
    static boolean topUp(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String key = p.getString("tmdbKey", "");
        if (key.isEmpty()) return true;
        try {
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            int i = firstFrom(p, q, p.getInt("qi", 0));
            if (q.length() - i >= LOW) return true;
            java.util.Set<String> skip = new java.util.HashSet<>();
            for (int k = 0; k < q.length(); k++) skip.add(q.getJSONObject(k).optString("id"));
            for (String list : new String[]{"shown", "avoid", "watched", "skipped", "saved", "savedKeep"}) {
                JSONArray a = new JSONArray(p.getString(list, "[]"));
                for (int k = 0; k < a.length(); k++) skip.add(a.optString(k));
            }
            JSONArray likes = new JSONArray(p.getString("likeGenres", "[]"));
            boolean noAnime = p.getBoolean("noAnime", false);
            Random r = new Random();
            JSONArray add = new JSONArray();
            JSONObject meta = new JSONObject(p.getString("wmeta", "{}"));
            int want = READY - (q.length() - i);
            for (int tries = 0; tries < 80 && add.length() < want; tries++) {
                String type = r.nextInt(3) == 0 ? "tv" : "movie";
                StringBuilder u = new StringBuilder("https://api.themoviedb.org/3/discover/").append(type)
                        .append("?api_key=").append(java.net.URLEncoder.encode(key, "UTF-8"))
                        .append("&watch_region=IN&with_watch_monetization_types=flatrate&include_adult=false")
                        .append("&sort_by=popularity.desc&vote_count.gte=").append(type.equals("tv") ? 40 : 80)
                        .append("&page=").append(1 + r.nextInt(Math.min(200, 25 + tries * 4)));
                if (type.equals("movie") && likes.length() > 0 && r.nextBoolean()) u.append("&with_genres=").append(likes.optInt(r.nextInt(likes.length())));
                JSONArray res = new JSONObject(get(u.toString())).optJSONArray("results");
                if (res == null) continue;
                for (int k = 0; k < res.length() && add.length() < want; k++) {
                    JSONObject t = res.getJSONObject(k);
                    String poster = t.optString("poster_path", "");
                    if (poster.isEmpty() || "null".equals(poster)) continue;
                    String id = "tmdb:" + type + ":" + t.optLong("id");
                    if (skip.contains(id)) continue;
                    if (type.equals("tv") && isSerial(key, t)) { skip.add(id); continue; }   // no regional TV serials
                    if (noAnime && isAnime(t)) { skip.add(id); continue; }                   // the developer account: no anime
                    skip.add(id);
                    String title = t.optString(type.equals("tv") ? "name" : "title", "");
                    String year = t.optString(type.equals("tv") ? "first_air_date" : "release_date", "");
                    year = year.length() >= 4 ? year.substring(0, 4) : "";
                    String over = t.optString("overview", "");
                    add.put(new JSONObject().put("id", id).put("title", title)
                            .put("img", "https://image.tmdb.org/t/p/w342" + poster));
                    double score = t.optDouble("vote_average", 0);
                    meta.put(id, new JSONObject().put("id", id).put("tmdb", new JSONObject().put("type", type).put("id", t.optLong("id")))
                            .put("title", title).put("kind", type.equals("tv") ? "series" : "movie").put("year", year)
                            .put("score", score > 0 ? Math.round(score * 10) / 10.0 : JSONObject.NULL)
                            .put("tmdbPoster", poster).put("synopsis", over).put("telugu", "te".equals(t.optString("original_language")))
                            .put("streams", true));
                }
            }
            if (add.length() == 0) return true;
            JSONObject trimmed = new JSONObject();                     // titles VibeSync may still ask about, newest 800
            JSONArray names = meta.names();
            int n = names == null ? 0 : names.length();
            for (int k = Math.max(0, n - 800); k < n; k++) trimmed.put(names.getString(k), meta.get(names.getString(k)));
            // add them on the tap thread, against the queue as it is NOW (taps may have moved on meanwhile)
            ONE.submit(() -> {
                try {
                    JSONArray q2 = new JSONArray(p.getString("queue", "[]"));
                    int i2 = firstFrom(p, q2, p.getInt("qi", 0));
                    java.util.Set<String> have = new java.util.HashSet<>();
                    for (int k = 0; k < q2.length(); k++) have.add(q2.getJSONObject(k).optString("id"));
                    JSONArray nq = new JSONArray();
                    int from = Math.max(0, i2 - 5);                    // keep the queue from growing forever: drop titles already passed
                    for (int k = from; k < q2.length(); k++) nq.put(q2.get(k));
                    for (int k = 0; k < add.length(); k++) if (!have.contains(add.getJSONObject(k).optString("id"))) nq.put(add.get(k));
                    p.edit().putString("queue", nq.toString()).putInt("qi", p.getInt("qi", 0) - from).putString("wmeta", trimmed.toString()).commit();
                } catch (Exception ignored) { }
            }).get();
            return true;
        } catch (Exception e) { return false; }
    }

    private static final java.util.Set<String> IND = new java.util.HashSet<>(java.util.Arrays.asList(
            "hi", "te", "ta", "ml", "kn", "bn", "mr", "pa", "gu", "or", "as", "ur", "bho"));
    private static final java.util.regex.Pattern SERIAL_NET = java.util.regex.Pattern.compile(
            "\\b(star (maa|plus|bharat|vijay|jalsha|pravah|suvarna|utsav)|maa tv|zee (tv|telugu|tamil|kannada|marathi|bangla|keralam|anmol|punjabi|odia|sarthak|yuva)"
            + "|colors( (tv|kannada|bangla|marathi|tamil|gujarati|rishtey|odia))?|sony (entertainment television|sab|tv|marathi)|sab tv|&tv|and tv"
            + "|sun (tv|bangla|marathi)|gemini tv|etv( telugu| plus| bal bharat)?|surya tv|udaya tv|kalaignar|asianet|mazhavil manorama|flowers tv"
            + "|vijay tv|dangal|dd national|doordarshan|shemaroo|ishara|nazara|raj tv|polimer|jaya tv|sun neo)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** An Indian TV serial (daily soap): TMDB's soap genre, 100+ episodes, or a TV channel. Same rule as the page. */
    private static boolean isSerial(String key, JSONObject t) {
        JSONArray g = t.optJSONArray("genre_ids");
        boolean indian = IND.contains(t.optString("original_language"));
        JSONArray oc = t.optJSONArray("origin_country");
        for (int k = 0; oc != null && k < oc.length(); k++) if ("IN".equals(oc.optString(k))) indian = true;
        if (!indian) return false;
        for (int k = 0; g != null && k < g.length(); k++) {
            int x = g.optInt(k);
            if (x == 10764 || x == 10767 || x == 99 || x == 10763) return false;   // reality, talk, documentary, news
            if (x == 10766) return true;                                             // soap
        }
        try {
            JSONObject d = new JSONObject(get("https://api.themoviedb.org/3/tv/" + t.optLong("id") + "?api_key=" + java.net.URLEncoder.encode(key, "UTF-8")));
            if (d.optInt("number_of_episodes", 0) >= 100) return true;
            JSONArray n = d.optJSONArray("networks");
            for (int k = 0; n != null && k < n.length(); k++) if (SERIAL_NET.matcher(n.getJSONObject(k).optString("name")).find()) return true;
        } catch (Exception e) { return true; }                                       // couldn't check: leave it out
        return false;
    }

    /** Remember each title the widget showed, so the refills don't bring it back (newest 3000). */
    static void noteShown(Context ctx, String id) {
        if (id == null || id.isEmpty()) return;
        SharedPreferences p = prefs(ctx);
        try {
            JSONArray a = new JSONArray(p.getString("shown", "[]"));
            a.put(id);
            JSONArray b = new JSONArray();
            for (int k = Math.max(0, a.length() - 3000); k < a.length(); k++) b.put(a.get(k));
            p.edit().putString("shown", b.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(6000);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
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

    /** Each title keeps its own poster file, so the next titles' posters can be fetched ahead of time. */
    static File posterFile(Context ctx, String id) {
        File dir = new File(ctx.getFilesDir(), "wposters");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, (id == null ? "" : id).replaceAll("[^A-Za-z0-9_.-]", "_") + ".jpg");
    }

    /** Download one title's poster unless it's already here: 1 downloaded, 0 nothing to do, -1 failed (offline?).
     *  Written to a temp file first, so a half-done download never shows. */
    private static int have(Context ctx, JSONObject t) {
        if (t == null) return 0;
        File f = posterFile(ctx, t.optString("id"));
        if (f.length() > 0) return 0;
        String img = t.optString("img");
        if (!img.startsWith("https://")) return 0;
        File tmp = new File(f.getPath() + ".part");
        if (download(img, tmp) && tmp.renameTo(f)) return 1;
        tmp.delete();
        return -1;
    }

    /** The next few titles the widget will show after the current one (in order). */
    static java.util.List<JSONObject> upcoming(Context ctx, int n) {
        java.util.List<JSONObject> out = new java.util.ArrayList<>();
        try {
            SharedPreferences p = prefs(ctx);
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            int i = firstFrom(p, q, p.getInt("qi", 0));
            if (held(ctx) == null && asking(ctx) == null && locked(ctx) == null) i = firstFrom(p, q, i + 1);   // the current one is q[i]
            while (i < q.length() && out.size() < n) { out.add(q.getJSONObject(i)); i = firstFrom(p, q, i + 1); }
        } catch (Exception ignored) { }
        return out;
    }

    private static boolean download(String img, File f) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(img.replace("/t/p/w342/", "/t/p/w500/")).openConnection();   // sharp on the widget, ~60 KB each
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                if (b == null) return false;
                int w = Math.min(500, b.getWidth()), h = Math.round(w * (float) b.getHeight() / b.getWidth());
                Bitmap s = w == b.getWidth() ? b : Bitmap.createScaledBitmap(b, w, h, true);
                try (FileOutputStream out = new FileOutputStream(f)) { s.compress(Bitmap.CompressFormat.JPEG, 94, out); }
                return true;
            }
        } catch (Exception ignored) { return false; }
    }

    static final int AHEAD = READY;                            // posters kept ready for all the titles ahead (works offline)

    // ───────── background sync: titles + posters, only with internet ─────────

    /** Is there a working internet connection right now? */
    static boolean online(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm == null ? null : cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) { return true; }                    // can't tell: just try
    }

    /** Offline: Android runs WidgetSyncJob as soon as there's internet again (even if VibeSync isn't open). */
    static void syncWhenOnline(Context ctx) {
        try {
            android.app.job.JobScheduler js = (android.app.job.JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new android.app.job.JobInfo.Builder(JOB_ID, new ComponentName(ctx, WidgetSyncJob.class))
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).build());
        } catch (Exception ignored) { }
    }

    /** Top up titles and posters in the background. Taps never wait for it; several requests at once become one run.
     *  Offline: nothing is fetched, and it runs by itself once the phone is back online. */
    static void syncSoon(Context ctx, Runnable then) {
        final Context app = ctx.getApplicationContext();
        if (!SYNCING.compareAndSet(false, true)) { AGAIN.set(true); if (then != null) then.run(); return; }
        NET.execute(() -> {
            try {
                do { AGAIN.set(false); syncOnce(app); } while (AGAIN.get());
            } finally {
                SYNCING.set(false);
                if (then != null) then.run();
            }
        });
    }

    private static void syncOnce(Context ctx) {
        if (!online(ctx)) { if (DIRTY) { DIRTY = false; ONE.execute(() -> refresh(ctx)); } syncWhenOnline(ctx); return; }
        boolean failed = false;
        String before = idOf(current(ctx));
        int r = ensurePoster(ctx);
        if (r == 1 || DIRTY) { DIRTY = false; ONE.execute(() -> refresh(ctx)); }   // its poster just arrived / new picks
        failed |= r < 0;
        if (!topUp(ctx)) failed = true;
        if (!before.equals(idOf(current(ctx)))) ONE.execute(() -> refresh(ctx));  // e.g. the widget had run out
        r = ensurePoster(ctx);
        if (r == 1) ONE.execute(() -> refresh(ctx));
        failed |= r < 0;
        for (JSONObject t : upcoming(ctx, AHEAD)) {                           // the next titles' posters, for instant taps (and offline)
            if (!online(ctx)) { failed = true; break; }
            int got = have(ctx, t);
            if (got < 0) failed = true;
            else if (got == 1 && t.optString("id").equals(idOf(queued(ctx)))) ONE.execute(() -> refresh(ctx));    // you've tapped ahead to it
        }
        prune(ctx);
        if (failed) syncWhenOnline(ctx);                                      // try the rest again when the internet is back
    }

    private static String idOf(JSONObject j) { return j == null ? "" : j.optString("id"); }

    /** The poster for what the widget shows right now: 1 downloaded, 0 nothing to do, -1 failed. */
    static int ensurePoster(Context ctx) {
        int a = have(ctx, asking(ctx) != null ? asking(ctx) : locked(ctx));
        int b = have(ctx, queued(ctx));
        return a < 0 || b < 0 ? -1 : Math.max(a, b);
    }

    /** Clear out posters of titles long gone (keeps the newest 130 and everything coming up). */
    private static void prune(Context ctx) {
        try {
            File[] all = new File(ctx.getFilesDir(), "wposters").listFiles();
            if (all != null && all.length > READY + 30) {
                java.util.Arrays.sort(all, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                java.util.Set<String> keep = new java.util.HashSet<>();
                for (JSONObject t : upcoming(ctx, AHEAD + 5)) keep.add(posterFile(ctx, t.optString("id")).getName());
                for (JSONObject t : new JSONObject[]{current(ctx), locked(ctx), held(ctx)}) if (t != null) keep.add(posterFile(ctx, t.optString("id")).getName());
                for (int k = READY + 30; k < all.length; k++) if (!keep.contains(all[k].getName())) all[k].delete();
            }
            new File(ctx.getFilesDir(), "pick.png").delete(); new File(ctx.getFilesDir(), "lock.png").delete();   // the old single files
        } catch (Exception ignored) { }
    }

    // ───────── drawing ─────────

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, PickWidget.class));
        for (int id : ids) mgr.updateAppWidget(id, views(ctx, id));
    }

    /** The widget's size in dp, {width, height}; {0, 0} if the home screen hasn't said. */
    private static float[] sizeDp(Context ctx, int widgetId) {
        try {
            android.os.Bundle opt = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId);
            boolean land = ctx.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            return new float[]{opt.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
                               opt.getInt(land ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)};
        } catch (Exception e) { return new float[]{0, 0}; }
    }

    static final float MIN_POSTER_DP = 110;                    // smaller than this, a whole poster isn't worth showing

    /** How tall (dp) the whole, uncropped poster can be with the title and buttons, or 0 if it can't fit:
     *  then the widget shows just the title. Matches widget_pick.xml: 8dp padding, title ~40dp + 6dp gap,
     *  buttons 8dp gap + one row 40dp (+ 6dp + 40dp for the second row). */
    static float posterRoom(Context ctx, int widgetId, boolean twoRows) {
        float[] wh = sizeDp(ctx, widgetId);
        if (wh[0] <= 0 || wh[1] <= 0) return 200;              // size unknown: assume a normal widget
        float w = wh[0] - 16, h = wh[1] - 16 - 8 - (twoRows ? 86 : 40) - 6 - 40;
        float ph = Math.min(h, w * 1.5f);                      // posters are 2:3
        return ph >= MIN_POSTER_DP ? ph : 0;
    }

    /** The whole poster, scaled to the space it will fill (sharp, never cropped). */
    private static Bitmap wholePoster(Context ctx, File f, float heightDp) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (src == null) return null;
        float d = ctx.getResources().getDisplayMetrics().density;
        int th = Math.round(heightDp * d), tw = Math.round(th * (float) src.getWidth() / src.getHeight());
        float k = (float) Math.sqrt(450_000f / ((float) tw * th));            // light enough to send to the home screen
        if (k < 1f) { tw = Math.round(tw * k); th = Math.round(th * k); }
        if (tw >= src.getWidth()) return src;                  // never upscale
        return Bitmap.createScaledBitmap(src, Math.max(1, tw), Math.max(1, th), true);
    }

    private static String lastKey = "";
    private static Bitmap lastBmp;

    static RemoteViews views(Context ctx) {
        int[] ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, PickWidget.class));
        return views(ctx, ids.length > 0 ? ids[0] : 0);
    }

    static RemoteViews views(Context ctx, int widgetId) {
        SharedPreferences p = prefs(ctx);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_pick);
        JSONObject ask = asking(ctx), lock = ask == null ? locked(ctx) : null;
        JSONObject j = ask != null ? ask : lock != null ? lock : queued(ctx);
        String id = j == null ? "" : j.optString("id");

        // the whole poster with the title under it when it fits; otherwise just the title. Buttons below, 2 by 2.
        v.setViewVisibility(R.id.w_btns, j == null ? View.GONE : View.VISIBLE);
        float room = j == null ? 0 : posterRoom(ctx, widgetId, ask == null);
        v.setTextViewText(R.id.w_title, j == null ? "🍿 Open VibeSync for tonight's pick" : j.optString("title"));
        v.setTextViewTextSize(R.id.w_title, android.util.TypedValue.COMPLEX_UNIT_SP, room > 0 ? 15 : 19);
        v.setInt(R.id.w_title, "setMaxLines", room > 0 ? 2 : 3);
        if (j != null) v.setContentDescription(R.id.w_poster, (ask != null ? "Yesterday's pick: " : "Tonight's pick: ") + j.optString("title"));
        v.setViewVisibility(R.id.w_save, j == null || ask != null || lock != null ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.w_save, "♡ Save");

        // buttons: normal  ✓ Watched · Skip ⏭ · 🔓 Lock · ♡ Save  |  locked  ✓ Watched · 🔒  |  yesterday's pick  ✓ Watched it · ✗ Not yet
        v.setTextViewText(R.id.w_watched, ask != null ? "✓ Watched it" : "✓ Watched");
        v.setTextViewText(R.id.w_skip, ask != null ? "✗ Not yet" : "Skip ⏭");
        v.setViewVisibility(R.id.w_skip, lock != null ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.w_lock, ask != null ? View.GONE : View.VISIBLE);
        v.setViewVisibility(R.id.w_row2, ask != null ? View.GONE : View.VISIBLE);     // yesterday's pick: just the two answers
        int taps = System.currentTimeMillis() - p.getLong("lockTapAt", 0) < TAP_WINDOW ? p.getInt("lockTaps", 0) : 0;
        v.setTextViewText(R.id.w_lock, lock == null ? "🔓 Lock" : taps == 1 ? "🔒 2 more taps" : taps == 2 ? "🔒 1 more tap" : "🔒 Locked");

        File f = posterFile(ctx, id);
        Bitmap b = null;
        try {
            if (room > 0 && f.length() > 0) {
                String k = f.getPath() + "|" + f.lastModified() + "|" + Math.round(room);
                synchronized (PickWidget.class) {
                    if (k.equals(lastKey) && lastBmp != null) b = lastBmp;          // same poster, same size: reuse, don't decode again
                    else { b = wholePoster(ctx, f, room); lastKey = b == null ? "" : k; lastBmp = b; }
                }
            }
        } catch (Throwable ignored) { }
        v.setTextViewTextSize(R.id.w_title, android.util.TypedValue.COMPLEX_UNIT_SP, b != null ? 15 : 19);   // no poster: a bigger title
        v.setInt(R.id.w_title, "setMaxLines", b != null ? 2 : 3);
        v.setViewVisibility(R.id.w_poster, b != null ? View.VISIBLE : View.GONE);      // no poster yet / too short: the title alone
        if (b != null) v.setImageViewBitmap(R.id.w_poster, b);

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
        Intent sv = new Intent(ctx, PickWidget.class).setAction(ACTION_SAVE);
        v.setOnClickPendingIntent(R.id.w_save, PendingIntent.getBroadcast(ctx, 5, sv, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
