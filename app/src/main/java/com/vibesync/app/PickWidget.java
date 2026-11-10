package com.vibesync.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Home-screen widget: "Tonight's pick". VibeSync sends a short queue of picks each time it opens.
 * Tapping the widget opens that title in VibeSync; "Watched it" marks it watched (VibeSync syncs it
 * the next time it opens) and moves on to the next pick in the queue.
 */
public class PickWidget extends AppWidgetProvider {
    static final String ACTION_WATCHED = "com.vibesync.app.PICK_WATCHED";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, views(ctx));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_WATCHED.equals(intent.getAction())) {
            final PendingResult done = goAsync();
            new Thread(() -> {
                try { next(ctx); } catch (Exception ignored) { }
                refresh(ctx);
                done.finish();
            }).start();
            return;
        }
        super.onReceive(ctx, intent);
    }

    /** Mark the current pick watched, step to the next one and fetch its poster. */
    static void next(Context ctx) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        JSONArray q = new JSONArray(p.getString("queue", "[]"));
        int i = p.getInt("qi", 0);
        if (i < q.length()) {
            JSONArray w = new JSONArray(p.getString("watched", "[]"));
            w.put(q.getJSONObject(i).optString("id"));
            p.edit().putString("watched", w.toString()).apply();
        }
        i++;
        if (i >= q.length()) i = q.length();                 // ran out: the widget asks to open VibeSync for more
        p.edit().putInt("qi", i).apply();
        if (i < q.length()) savePoster(ctx, q.getJSONObject(i).optString("img"));
        else new File(ctx.getFilesDir(), "pick.png").delete();
    }

    static void savePoster(Context ctx, String img) {
        File f = new File(ctx.getFilesDir(), "pick.png");
        if (img == null || !img.startsWith("https://")) { f.delete(); return; }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(img).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            try (InputStream in = c.getInputStream()) {
                Bitmap b = BitmapFactory.decodeStream(in);
                if (b == null) return;
                int w = 220, h = Math.round(w * (float) b.getHeight() / b.getWidth());
                Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
                try (FileOutputStream out = new FileOutputStream(f)) { s.compress(Bitmap.CompressFormat.PNG, 92, out); }
            }
        } catch (Exception ignored) { }
    }

    static JSONObject current(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            int i = p.getInt("qi", 0);
            return i < q.length() ? q.getJSONObject(i) : null;
        } catch (Exception e) { return null; }
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, PickWidget.class));
        if (ids.length > 0) mgr.updateAppWidget(ids, views(ctx));
    }

    static RemoteViews views(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_pick);
        JSONObject j = current(ctx);
        String id = j == null ? "" : j.optString("id");
        v.setTextViewText(R.id.w_title, j == null ? "Open VibeSync" : j.optString("title"));
        v.setTextViewText(R.id.w_sub, j == null ? "Tap for tonight's next pick" : j.optString("sub"));
        v.setTextViewText(R.id.w_line, j == null ? "" : j.optString("line"));
        v.setViewVisibility(R.id.w_watched, j == null ? android.view.View.GONE : android.view.View.VISIBLE);
        File f = new File(ctx.getFilesDir(), "pick.png");
        Bitmap b = j != null && f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (b != null) v.setImageViewBitmap(R.id.w_poster, b); else v.setImageViewResource(R.id.w_poster, R.mipmap.ic_launcher);
        // tap: open this exact title in VibeSync
        Intent open = new Intent(ctx, MainActivity.class).setAction("com.vibesync.app.OPEN." + id)
                .putExtra(MainActivity.EXTRA_OPEN, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent watched = new Intent(ctx, PickWidget.class).setAction(ACTION_WATCHED);
        v.setOnClickPendingIntent(R.id.w_watched, PendingIntent.getBroadcast(ctx, 2, watched, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
