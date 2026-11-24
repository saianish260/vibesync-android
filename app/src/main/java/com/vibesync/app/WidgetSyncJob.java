package com.vibesync.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs when the phone is back online (scheduled by PickWidget while offline): fetches the widget's missing titles
 *  and posters, then the widget is fully ready again. Nothing happens while there's no internet. */
public class WidgetSyncJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        PickWidget.syncSoon(getApplicationContext(), () -> jobFinished(params, false));
        return true;                                           // working in the background
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;                                           // cut short (connection lost): try again later
    }
}
