package com.vibesync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Iterator;

/** Posts the new-episode notification; after a reboot, re-arms every saved reminder. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "episodes";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction() == null ? "" : intent.getAction();
        SharedPreferences p = ctx.getSharedPreferences(Bridge.PREFS, Context.MODE_PRIVATE);
        if (action.equals(Intent.ACTION_BOOT_COMPLETED) || action.equals("android.intent.action.MY_PACKAGE_REPLACED")) {
            try {
                JSONObject all = new JSONObject(p.getString("reminders", "{}"));
                for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String id = it.next(); JSONObject r = all.getJSONObject(id);
                    Bridge.arm(ctx, id, r.optString("title"), r.optString("body"), r.optLong("at"));
                }
            } catch (Exception ignored) { }
            try { PickWidget.rearm(ctx); } catch (Exception ignored) { }      // the widget's midnight "Did you watch it?"
            return;
        }
        String id = intent.getStringExtra("id"), title = intent.getStringExtra("title"), body = intent.getStringExtra("body");
        if (id == null) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "New episodes", NotificationManager.IMPORTANCE_DEFAULT));
        Intent show = new Intent(ctx, MainActivity.class).setAction("com.vibesync.app.OPEN." + id).putExtra(MainActivity.EXTRA_OPEN, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(ctx, id.hashCode(), show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat_vs).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(open);
        try { nm.notify(id.hashCode(), b.build()); } catch (SecurityException ignored) { }   // permission declined
        try {
            JSONObject all = new JSONObject(p.getString("reminders", "{}"));
            all.remove(id);
            p.edit().putString("reminders", all.toString()).apply();
        } catch (Exception ignored) { }
    }
}
