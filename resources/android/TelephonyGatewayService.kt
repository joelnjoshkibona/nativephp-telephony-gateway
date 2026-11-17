package com.blutrixx.plugins.nativephp_telephony_gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent foreground service -- the "app is alive" anchor for missed-call
 * detection. A `ContentObserver` alone doesn't wake a killed process; a
 * foreground service (required since Android 8's background-execution
 * limits) is what keeps this process resident so the observer can fire at
 * all. See docs/specs/mobile/telephony-gateway-plugin.md.
 *
 * Unlike SmsReceivedReceiver (a stateless, per-broadcast receiver), this
 * component must survive backgrounding for an extended, open-ended period,
 * not just complete one onReceive() call.
 *
 * Every call into PHP goes through EphemeralArtisan on the single
 * EphemeralDispatch thread.
 */
class TelephonyGatewayService : Service() {

    companion object {
        private const val TAG = "TelephonyGateway.Service"
        private const val CHANNEL_ID = "telephony_gateway"
        private const val NOTIFICATION_ID = 1001
        private const val PAUSED_NOTIFICATION_ID = 1002
        private const val PREF_LAST_CALL_LOG_ID = "last_call_log_id"
        private const val FALLBACK_POLL_SECONDS = 20L

        /**
         * Sent by nativephp-fcm's NativePHPFirebaseMessagingService when a
         * "gateway_wake" push arrives -- reuses this service's own existing,
         * already single-threaded poll mechanism instead of adding a second
         * dispatch path.
         */
        const val ACTION_POLL_NOW = "com.blutrixx.plugins.nativephp_telephony_gateway.ACTION_POLL_NOW"

        /** Slower than the claim loop on purpose -- telemetry/resync, not the message path. */
        private const val HEARTBEAT_INTERVAL_SECONDS = 180L

        // Read, never written, from here -- nativephp-fcm's own onNewToken()/
        // RequestPermission bridge function own this SharedPreferences entry.
        // A plain SharedPreferences read has no compile-time dependency on
        // Firebase classes, so this plugin keeps working (heartbeat just has
        // no token to report) in an app that never installed nativephp-fcm.
        private const val FCM_PREFS_NAME = "nativephp_push"
        private const val FCM_PREFS_KEY = "fcm_token"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Njiwa Gateway", NotificationManager.IMPORTANCE_LOW)
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }

        /**
         * Shown when the gateway had to stop and Android does not let it
         * restart itself (a refused background start, or the onTimeout safety net).
         * Tapping it opens the app -- the foreground transition is what lets
         * the app start the service again. Best effort: a missing
         * POST_NOTIFICATIONS grant just means it isn't shown.
         */
        fun postPausedNotification(context: Context, text: String) {
            try {
                ensureChannel(context)
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                val contentIntent = launch?.let {
                    PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                }
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle("Njiwa Gateway paused")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                    .apply { contentIntent?.let { setContentIntent(it) } }
                    .build()
                context.getSystemService(NotificationManager::class.java)?.notify(PAUSED_NOTIFICATION_ID, notification)
            } catch (e: Throwable) {
                Log.w(TAG, "postPausedNotification failed", e)
            }
        }
    }

    @Volatile private var running = false
    private var observer: ContentObserver? = null
    private val pollHandler = Handler(Looper.getMainLooper())
    @Volatile private var pollRunnable: Runnable? = null
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    @Volatile private var heartbeatRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // remoteMessaging: this service relays text messages between the
            // phone and the backend. Unlike dataSync it has no Android 15 daily
            // time limit and may be started from BOOT_COMPLETED. The typed
            // overload/constant only exist on API 34+; below that the manifest
            // type is not enforced and 0 is what ServiceCompat expects.
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: Throwable) {
            // Android 12+ refuses a background FGS start outside the exemptions
            // (ForegroundServiceStartNotAllowedException). Stop cleanly instead
            // of crashing the process.
            Log.e(TAG, "onCreate: startForeground refused -- stopping", e)
            postPausedNotification(this, "Android did not allow the gateway to start in the background. Tap to open the app.")
            stopSelf()
            return
        }
        running = true
        (getSystemService(NotificationManager::class.java))?.cancel(PAUSED_NOTIFICATION_ID)
        startWork(heartbeatDelayMillis = 0)
    }

    private fun startWork(heartbeatDelayMillis: Long) {
        registerCallLogObserver()
        schedulePollTick(0)
        scheduleHeartbeatTick(heartbeatDelayMillis)
    }

    private fun stopWork() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
        observer?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Throwable) {
                Log.w(TAG, "unregisterContentObserver failed", e)
            }
        }
        observer = null
    }

    /**
     * The `restart_service` device command. Done in place (tear down the
     * observer and both timers, then set them up again) rather than
     * stopService()+startForegroundService(): from the background, Android 12+
     * may refuse the second start, which would turn a restart into a stop.
     */
    fun softRestart(onDone: (Boolean) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            if (!running) {
                onDone(false)
                return@post
            }
            stopWork()
            startWork(heartbeatDelayMillis = HEARTBEAT_INTERVAL_SECONDS * 1000)
            Log.i(TAG, "softRestart: observer and timers re-initialized")
            onDone(true)
        }
    }

    private fun registerCallLogObserver() {
        val newObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                EphemeralDispatch.post { checkForNewMissedCalls() }
            }
        }
        // The Vue layer requests READ_CALL_LOG before ever starting this
        // service, but a user can still revoke it afterward from Settings --
        // registerContentObserver() then throws SecurityException, and an
        // unguarded throw here once crashed the app right after enrolling.
        // Missed-call detection is degraded, not fatal, without it.
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, newObserver)
            observer = newObserver
            Log.i(TAG, "CallLog observer registered")
            // Catch anything that landed before the observer was registered.
            EphemeralDispatch.post { checkForNewMissedCalls() }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CALL_LOG not granted -- missed-call detection disabled", e)
        }
    }

    /**
     * Self-rescheduling claim loop -- the device's primary work loop. Each
     * tick's PHP half (telephony:poll-claim) does the HTTP call and business
     * logic only; this loop does the sending, since the ephemeral runtime
     * cannot call nativephp_call() itself. Every claimed job is sent via
     * TelephonyGatewayFunctions' direct functions.
     */
    private fun schedulePollTick(delayMillis: Long) {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        if (!running) return
        val runnable = Runnable { runPollTick() }
        pollRunnable = runnable
        pollHandler.postDelayed(runnable, delayMillis)
    }

    private fun runPollTick() {
        EphemeralDispatch.post {
            var nextPollSeconds = FALLBACK_POLL_SECONDS
            try {
                val sims = SimSlots.listActive(applicationContext)
                val slotToSubscription = sims.associate { it.slot to it.subscriptionId }
                val simsJson = JSONArray().apply {
                    sims.forEach {
                        put(JSONObject().apply {
                            put("slot", it.slot)
                            put("subscription_id", it.subscriptionId)
                            put("is_present", true)
                        })
                    }
                }
                val encodedSims = android.util.Base64.encodeToString(
                    simsJson.toString().toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )

                // nativeEphemeralArtisan()'s captured output comes back empty for
                // this command (confirmed live) -- the command writes its result to
                // a file instead. NOTE: PHPBridge.getLaravelPath() is the read-only
                // BUNDLE copy; storage writes land under the persistent
                // ".../storage/persisted_data/storage" tree (LaravelEnvironment.kt's
                // DIR_APP). Delete the previous tick's file first so a run that
                // fails before writing can never re-dispatch already-sent jobs.
                val resultFile = java.io.File(gatewayAppStorageDir(applicationContext), "gateway_poll_result.json")
                if (resultFile.exists() && !resultFile.delete()) {
                    Log.w(TAG, "could not delete stale poll result file")
                }

                val run = EphemeralArtisan.runOnDispatchThread(applicationContext, "telephony:poll-claim --sims=$encodedSims", null)
                val result = if (resultFile.exists()) resultFile.readText().trim() else run.output?.trim()
                Log.i(TAG, "poll-claim ok=${run.ok} hasResult=${!result.isNullOrEmpty()}")

                if (!result.isNullOrEmpty()) {
                    val parsed = JSONObject(result)
                    nextPollSeconds = parsed.optLong("next_poll_seconds", FALLBACK_POLL_SECONDS)
                    val jobs = parsed.optJSONArray("jobs") ?: JSONArray()
                    for (i in 0 until jobs.length()) {
                        // One job's failure must not abort the rest of the batch --
                        // and is reported, so it doesn't sit "claimed" until its lease expires.
                        val job = jobs.optJSONObject(i) ?: continue
                        try {
                            dispatchClaimedJob(job, slotToSubscription)
                        } catch (e: Throwable) {
                            Log.e(TAG, "dispatchClaimedJob FAILED for job index $i", e)
                            reportJobFailure(job, e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "runPollTick FAILED", e)
            } finally {
                schedulePollTick(nextPollSeconds * 1000)
            }
        }
    }

    /**
     * A second, slower self-rescheduling timer on the same EphemeralDispatch
     * thread as the claim loop -- telemetry/resync, device commands, and the
     * retry path for missed calls whose report failed.
     */
    private fun scheduleHeartbeatTick(delayMillis: Long) {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        if (!running) return
        val runnable = Runnable { runHeartbeatTick() }
        heartbeatRunnable = runnable
        heartbeatHandler.postDelayed(runnable, delayMillis)
    }

    private fun runHeartbeatTick() {
        EphemeralDispatch.post {
            try {
                val fcmToken = getSharedPreferences(FCM_PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(FCM_PREFS_KEY, null)
                val battery = DeviceTelemetry.readBattery(applicationContext)
                val networkType = DeviceTelemetry.readNetworkType(applicationContext)

                val commandParts = mutableListOf("telephony:heartbeat")
                if (!fcmToken.isNullOrEmpty()) {
                    val encodedToken = android.util.Base64.encodeToString(
                        fcmToken.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    commandParts.add("--fcm-token=$encodedToken")
                }
                battery.percent?.let { commandParts.add("--battery-pct=$it") }
                battery.isCharging?.let { commandParts.add("--is-charging=${if (it) 1 else 0}") }
                commandParts.add("--network-type=$networkType")

                EphemeralArtisan.runOnDispatchThread(applicationContext, commandParts.joinToString(" "), null)
            } catch (e: Throwable) {
                Log.e(TAG, "runHeartbeatTick FAILED", e)
            }

            try {
                NativeCommands.takeAndRun(this)
            } catch (e: Throwable) {
                Log.e(TAG, "native commands FAILED", e)
            }

            try {
                checkForNewMissedCalls()
            } catch (e: Throwable) {
                Log.e(TAG, "missed-call retry FAILED", e)
            }

            scheduleHeartbeatTick(HEARTBEAT_INTERVAL_SECONDS * 1000)
        }
    }

    private fun dispatchClaimedJob(job: JSONObject, slotToSubscription: Map<Int, Int>) {
        val id = job.optString("id")
        if (id.isEmpty()) return
        val slot = job.optInt("slot", 0)
        val subscriptionId = slotToSubscription[slot]

        when (job.optString("type")) {
            "sms" -> {
                val to = job.optString("to")
                if (to.isEmpty()) {
                    reportJobFailure(job, "claimed SMS job has no recipient")
                    return
                }
                val body = job.optString("body", "")
                TelephonyGatewayFunctions.sendSmsDirect(applicationContext, to, body, id, subscriptionId, slot)
            }
            "ussd" -> {
                val code = job.optString("code")
                if (code.isEmpty()) {
                    reportJobFailure(job, "claimed USSD job has no code")
                    return
                }
                TelephonyGatewayFunctions.sendUssdDirect(applicationContext, code, id, subscriptionId, slot)
            }
            else -> Log.w(TAG, "dispatchClaimedJob: unknown job type ${job.optString("type")}")
        }
    }

    /** Reports a job that could not even be handed to the radio. */
    private fun reportJobFailure(job: JSONObject, error: String) {
        val id = job.optString("id")
        if (id.isEmpty()) return
        val slot = if (job.has("slot")) job.optInt("slot") else null
        when (job.optString("type")) {
            "ussd" -> TelephonyGatewayFunctions.dispatchUssdResult(applicationContext, id, null, "USSD_EXCEPTION", slot, error)
            "sms" -> SmsResultReceiver.postReport(
                applicationContext,
                SmsResultReceiver.payload(
                    id, "sent", false, SmsResultReceiver.RESULT_ERROR_GENERIC_FAILURE,
                    job.optString("to").ifEmpty { null }, slot, 1, null, null, error
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            // startForeground() was refused in onCreate(); don't ask to be recreated.
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_POLL_NOW) {
            Log.i(TAG, "onStartCommand: ACTION_POLL_NOW -- polling immediately")
            schedulePollTick(0)
        }
        return START_STICKY
    }

    /**
     * Safety net only. Android calls this when a foreground service type with
     * a time limit (dataSync/mediaProcessing on Android 15+) reaches it; the
     * remoteMessaging type this service declares has no such limit, so this is
     * not expected to fire. If it ever does, stop within the required few
     * seconds, keep the "should run" flag, and post the paused notification
     * that opens the app (which can start the service again).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "onTimeout: foreground service time limit reached (type=$fgsType) -- stopping")
        running = false
        stopWork()
        postPausedNotification(this, "Android's background time limit was reached. Tap to open the app and resume.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        stopWork()
        Log.i(TAG, "onDestroy: work stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        ensureChannel(this)
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(this, NOTIFICATION_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Njiwa Gateway active")
            .setContentText("Sending and receiving SMS, USSD and missed calls for Njiwa")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    /**
     * Queries CallLog for missed-call rows newer than the high-water mark,
     * using the row's own `_ID` both as the mark and as the backend's
     * idempotency key (`device_call_uid`). Runs on the EphemeralDispatch thread.
     *
     * - A call on an unwatched SIM, or whose SIM can't be determined, is
     *   dropped (never read further, logged, or sent) and the mark moves past it.
     * - A watched call moves the mark only once `telephony:missed-call`
     *   completed without a failure signal (see EphemeralArtisan); on failure
     *   the scan stops, so that call and everything after it is retried on
     *   the next CallLog change or heartbeat tick.
     *
     * No ring-duration signal is available from CallLog for an unanswered
     * call (`DURATION` is 0) -- left null rather than invented.
     */
    private fun checkForNewMissedCalls() {
        val prefs = getSharedPreferences(GatewayPrefs.PREFS_NAME, MODE_PRIVATE)
        var mark = prefs.getLong(PREF_LAST_CALL_LOG_ID, 0L)
        val watched = GatewayPrefs.watchedSims(applicationContext)

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )
        val selection = "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.TYPE} = ?"
        val selectionArgs = arrayOf(mark.toString(), CallLog.Calls.MISSED_TYPE.toString())

        fun advanceTo(id: Long) {
            if (id > mark) {
                mark = id
                prefs.edit().putLong(PREF_LAST_CALL_LOG_ID, id).commit()
            }
        }

        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls._ID} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val phoneAccountIdCol = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val resolver = if (watched.isEmpty()) null else SimSlots.phoneAccountResolver(applicationContext)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val phoneAccountId = if (phoneAccountIdCol >= 0) cursor.getString(phoneAccountIdCol) else null
                    val subscriptionId = resolver?.subscriptionFor(phoneAccountId)

                    if (subscriptionId == null || !GatewayRules.isWatched(subscriptionId, watched)) {
                        advanceTo(id)
                        continue
                    }

                    val number = cursor.getString(numberCol) ?: "unknown"
                    val date = cursor.getLong(dateCol)
                    if (!dispatchMissedCall(id, number, date, subscriptionId)) {
                        Log.w(TAG, "missed call $id not reported -- high-water mark held at $mark, will retry")
                        break
                    }
                    advanceTo(id)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "checkForNewMissedCalls: READ_CALL_LOG not granted", e)
        }
    }

    /** True only when `telephony:missed-call` ran without a failure signal. */
    private fun dispatchMissedCall(callLogId: Long, number: String, occurredAt: Long, subscriptionId: Int): Boolean {
        val slot = SimSlots.slotForSubscriptionId(applicationContext, subscriptionId) ?: 0
        Log.i(TAG, "dispatchMissedCall: id=$callLogId subscriptionId=$subscriptionId slot=$slot")

        val payload = JSONObject().apply {
            put("device_call_uid", callLogId.toString())
            put("caller_number", number)
            put("occurred_at", occurredAt)
            put("slot", slot)
            put("ring_duration_seconds", JSONObject.NULL)
            put("subscription_id", subscriptionId)
        }
        return EphemeralArtisan.runOnDispatchThread(applicationContext, "telephony:missed-call", payload).ok
    }
}

/** The persistent Laravel storage/app dir the host app's commands write to. */
internal fun gatewayAppStorageDir(context: Context): java.io.File {
    val appStorageDir = context.getDir("storage", Context.MODE_PRIVATE)
    return java.io.File(appStorageDir, "persisted_data/storage/app")
}
