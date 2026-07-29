package com.tmc.appalive;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Third resurrection path, independent of both the foreground service and
 * AlarmManager. Persisted, so it survives a reboot on its own.
 */
public final class AliveJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        if (Config.get(this).enabled()) {
            if (!AliveService.isRunning()) {
                EventLog.i("backstop job: service was down, restarting");
            }
            Starter.start(this, AliveService.class, true);
            Heartbeat.schedule(this);
        }
        return false; // nothing async; the job is finished
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule
    }
}
