package com.vibesync.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.io.File;

/** Home-screen widget: "Tonight's pick", refreshed each time VibeSync opens. */
public class PickWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) mgr.updateAppWidget(id, views(ctx));
    }

    static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, PickWidget.class));
        if (ids.length > 0) mgr.updateAppWidget(ids, views(ctx));
    }

    static RemoteViews views(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_pick);
        String title = "Open VibeSync", sub = "Your pick for tonight appears here", line = "";
        try {
            JSONObject j = new JSONObject(ctx.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE).getString("pick", "{}"));
            if (j.has("title")) { title = j.optString("title"); sub = j.optString("sub"); line = j.optString("line"); }
        } catch (Exception ignored) { }
        v.setTextViewText(R.id.w_title, title);
        v.setTextViewText(R.id.w_sub, sub);
        v.setTextViewText(R.id.w_line, line);
        File f = new File(ctx.getFilesDir(), "pick.png");
        Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (b != null) v.setImageViewBitmap(R.id.w_poster, b); else v.setImageViewResource(R.id.w_poster, R.mipmap.ic_launcher);
        PendingIntent open = PendingIntent.getActivity(ctx, 1, new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_root, open);
        return v;
    }
}
