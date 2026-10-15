package com.calllogvault

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sniffer. No polling, no foreground service, no wakelock.
 *
 * A JobScheduler content trigger asks Android itself to wake this
 * service when the call log changes. Trigger jobs are one shot, so
 * the service re-arms the trigger after every run, but only AFTER
 * jobFinished: scheduling a job id that is currently running makes
 * Android stop that job, so the pre v1.0.6 habit of calling
 * scheduleAll inside the run (and again from onStopJob) kept
 * stopping the service mid work and re-firing it, and every cycle
 * ran a real CallLog sync inside this process. A separate daily
 * periodic job is the safety net; it is scheduled only when not
 * already pending, never re-scheduled over itself.
 */
object SnifferScheduler {
    const val JOB_TRIGGER = 1001
    const val JOB_PERIODIC = 1002

    /**
     * Process level arming guard for activity callers. onCreate,
     * onResume and permission grants all want the sniffer armed, but
     * arming is a schedule call on both job ids, so before v1.0.6
     * every app open and every resume re-scheduled a possibly
     * running trigger job (which stops it). The activity now arms at
     * most once per process through this flag; the boot receiver
     * calls scheduleAll unguarded, because after a boot nothing is
     * running and the process is fresh anyway.
     */
    val sessionArmed = AtomicBoolean(false)

    private fun scheduler(context: Context): JobScheduler =
        context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    /** Arm only the one shot content trigger job (id 1001). */
    fun scheduleTrigger(context: Context) {
        try {
            val component = ComponentName(context, VaultJobService::class.java)
            val trigger = JobInfo.Builder(JOB_TRIGGER, component)
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CallLog.Calls.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(1000L)
                .setTriggerContentMaxDelay(5000L)
                .build()
            scheduler(context).schedule(trigger)
        } catch (e: Exception) {
            // Scheduling must never crash a caller. Sync on app open still works.
        }
    }

    /**
     * Arm the 24h periodic job (id 1002), but only when it is not
     * already pending. Re-scheduling a pending periodic job restarts
     * its clock for nothing, and scheduling a running one stops it,
     * so a pending periodic job is left completely alone.
     */
    fun schedulePeriodic(context: Context) {
        try {
            val js = scheduler(context)
            if (js.getPendingJob(JOB_PERIODIC) != null) return
            val component = ComponentName(context, VaultJobService::class.java)
            val periodic = JobInfo.Builder(JOB_PERIODIC, component)
                .setPeriodic(TimeUnit.HOURS.toMillis(24))
                .setRequiresCharging(false)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .setPersisted(true)
                .build()
            js.schedule(periodic)
        } catch (e: Exception) {
            // Scheduling must never crash a caller. Sync on app open still works.
        }
    }

    /** Arm both jobs. For boot and first arming callers. */
    fun scheduleAll(context: Context) {
        scheduleTrigger(context)
        schedulePeriodic(context)
    }

    fun isTriggerScheduled(context: Context): Boolean {
        return try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.allPendingJobs.any { it.id == JOB_TRIGGER }
        } catch (e: Exception) {
            false
        }
    }

    fun isPeriodicScheduled(context: Context): Boolean {
        return try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.allPendingJobs.any { it.id == JOB_PERIODIC }
        } catch (e: Exception) {
            false
        }
    }
}

class VaultJobService : JobService() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val appContext = applicationContext
        VaultExecutor.service.execute {
            try {
                if (PermUtil.hasCallLogRead(appContext)) {
                    val result = SyncEngine.sync(appContext, windowed = true)
                    // New calls feed the auto backup counter and its
                    // separate job. The sync itself stays pure.
                    if (VaultLogic.shouldScheduleAutoBackup(result.added)) {
                        AutoBackup.addNewSince(appContext, result.added)
                        AutoBackupScheduler.schedule(appContext)
                    }
                }
            } catch (ignored: Exception) {
            }
            // Finish FIRST, re-arm SECOND, in that order, always.
            // Scheduling a job id that is still running stops that
            // job; before v1.0.6 the finally block scheduled both
            // jobs before jobFinished, Android stopped this very run
            // for re-scheduling its own id, onStopJob scheduled both
            // jobs again and asked for a retry, and the cycle kept
            // re-running real syncs inside the app process forever.
            // Now nothing is scheduled until this run is fully
            // finished, and only the one shot trigger is re-armed
            // (the periodic job is never re-armed from inside a run:
            // the system keeps periodic jobs across runs by itself).
            try {
                jobFinished(params, false)
            } catch (ignored: Exception) {
            }
            if (params.jobId == SnifferScheduler.JOB_TRIGGER) {
                SnifferScheduler.scheduleTrigger(appContext)
            }
        }
        return true // work continues on the vault worker
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // No scheduling from here, ever (see onStartJob). For the one
        // shot trigger, returning true lets the system's own
        // reschedule run the job again later; the periodic job keeps
        // its own schedule and does not need a retry from us.
        return params.jobId == SnifferScheduler.JOB_TRIGGER
    }
}

/**
 * Auto backup scheduling. The write is its own job, fully separate
 * from sync: a finished sync only records that new calls exist and
 * schedules this job, so a slow or hanging storage provider can never
 * stall a sync or the UI again. In v1.0.3 the write ran at the end of
 * SyncEngine.sync, on the caller's thread, which is why tapping Sync
 * now after setting a folder could spin forever.
 *
 * Scheduling is cheap and idempotent: scheduling again replaces the
 * pending job with the same id.
 */
object AutoBackupScheduler {
    const val JOB_AUTO_BACKUP_ID = 1003

    fun schedule(context: Context) {
        try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, AutoBackupJobService::class.java)
            val info = JobInfo.Builder(JOB_AUTO_BACKUP_ID, component)
                .setMinimumLatency(0L)
                .build()
            js.schedule(info)
        } catch (e: Exception) {
            // Scheduling must never crash a caller. The next sync or
            // app open schedules it again.
        }
    }
}

class AutoBackupJobService : JobService() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val appContext = applicationContext
        if (!AutoBackup.hasFolder(appContext) || AutoBackup.isWriteInProgress()) {
            // Nothing to do, or a write is already running: finish at
            // once instead of queueing a second overlapping write.
            jobFinished(params, false)
            return false
        }
        VaultExecutor.service.execute {
            try {
                runWriteWithTimeout(appContext)
            } catch (ignored: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true // work continues on the vault worker
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    /**
     * Run the write on a throwaway worker and wait at most
     * [WRITE_TIMEOUT_MS]. The write callable runs off the vault
     * worker on purpose: the vault worker waits on the future, so the
     * write itself needs its own thread or the timeout could never be
     * enforced. On timeout the future is cancelled (without
     * interrupting the provider call), the attempt is recorded as an
     * error, and the job ends: a hanging provider wedges at most this
     * one background run, never a sync and never the UI. The write's
     * own in-progress guard stays set until the hung call actually
     * returns, so no later run can overlap it.
     */
    private fun runWriteWithTimeout(context: Context) {
        val exec = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "vault-autobackup").apply { isDaemon = true }
        }
        try {
            val future = exec.submit(Callable { AutoBackup.runScheduledWrite(context) })
            try {
                future.get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(false)
                AutoBackup.noteError(context)
            } catch (ignored: Exception) {
            }
        } finally {
            exec.shutdown()
        }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 60_000L
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SnifferScheduler.scheduleAll(context)
        }
    }
}
