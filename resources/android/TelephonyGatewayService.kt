package com.blutrixx.plugins.nativephp_telephony_gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nativephp.mobile.bridge.PHPBridge
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
 * component is genuinely new relative to the proven SMS spike: it must
 * survive backgrounding for an extended, open-ended period, not just
 * complete one onReceive() call.
 */
class TelephonyGatewayService : Service() {

    companion object {
        private const val TAG = "TelephonyGateway.Service"
        private const val CHANNEL_ID = "telephony_gateway"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "telephony_gateway"
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
        // Firebase classes, unlike calling FirebaseMessaging.getInstance()
        // directly would -- so this plugin keeps working (heartbeat just has
        // no token to report) in an app that never installed nativephp-fcm.
        private const val FCM_PREFS_NAME = "nativephp_push"
        private const val FCM_PREFS_KEY = "fcm_token"
    }

    private var observer: ContentObserver? = null
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val handler = Handler(Looper.getMainLooper())
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                EphemeralDispatch.post { checkForNewMissedCalls() }
            }
        }
        // The Vue layer requests READ_CALL_LOG before ever starting this
        // service (see useGatewayEnrollment.ts), but a user can still revoke
        // it afterward from Settings -- registerContentObserver() throws
        // SecurityException immediately in that case, same as any other
        // CallLogProvider access, and an unguarded throw here previously
        // crashed the whole app right after a successful enroll (confirmed
        // live). Missed-call detection is degraded, not fatal, without it --
        // SMS/USSD claim-and-send has no dependency on CallLog at all.
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer!!)
            Log.i(TAG, "onCreate: foreground service started, CallLog observer registered")
            // Catch anything that landed before the observer was registered.
            EphemeralDispatch.post { checkForNewMissedCalls() }
        } catch (e: SecurityException) {
            Log.e(TAG, "onCreate: READ_CALL_LOG not granted -- missed-call detection disabled", e)
        }

        schedulePollTick(0)
        scheduleHeartbeatTick(0)
    }

    /**
     * Self-rescheduling claim loop -- the device's primary work loop. Each
     * tick's own PHP half (telephony:poll-claim) does the HTTP call and
     * business logic only; this loop does the sending, since the ephemeral
     * runtime it dispatches through cannot call nativephp_call() itself
     * (confirmed via this project's own spike -- see
     * docs/specs/mobile/telephony-gateway-plugin.md). Every claimed job is
     * sent via TelephonyGatewayFunctions' direct functions -- a plain
     * in-process call, not a bridge round trip.
     */
    private fun schedulePollTick(delayMillis: Long) {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
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

                val bridge = PHPBridge(applicationContext)
                val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"
                bridge.nativeEphemeralBoot(bootstrapPath)

                val command = "telephony:poll-claim --sims=$encodedSims"
                Log.i(TAG, "calling nativeEphemeralArtisan: $command")
                val returnedResult = bridge.nativeEphemeralArtisan(command)?.trim()
                Log.i(TAG, "poll-claim return value: $returnedResult")

                // nativeEphemeralArtisan()'s own return-value capture comes back
                // empty for this command specifically (confirmed live, repeatedly
                // -- a real backend claim succeeds server-side with no exception
                // thrown, yet the captured stdout is blank) -- read the result
                // file the command itself writes instead. NOTE: PHPBridge.
                // getLaravelPath() points at the read-only BUNDLE copy of the app
                // (".../storage/laravel", replaced on every redeploy) -- storage
                // writes actually land under the separate, persistent
                // ".../storage/persisted_data/storage" tree (confirmed by reading
                // LaravelEnvironment.kt's own DIR_APP constant), which is why the
                // first version of this fix always saw exists=false.
                val appStorageDir = applicationContext.getDir("storage", android.content.Context.MODE_PRIVATE)
                val resultFile = java.io.File(appStorageDir, "persisted_data/storage/app/gateway_poll_result.json")
                val result = if (resultFile.exists()) resultFile.readText().trim() else returnedResult
                Log.i(TAG, "poll-claim result (from file): $result")

                if (!result.isNullOrEmpty()) {
                    val parsed = JSONObject(result)
                    nextPollSeconds = parsed.optLong("next_poll_seconds", FALLBACK_POLL_SECONDS)
                    val jobs = parsed.optJSONArray("jobs") ?: JSONArray()
                    for (i in 0 until jobs.length()) {
                        dispatchClaimedJob(jobs.getJSONObject(i), slotToSubscription)
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
     * thread as the claim loop -- telemetry/resync, not the message path, so
     * it doesn't need the claim loop's own dynamic next-interval logic, just
     * a fixed cadence.
     */
    private fun scheduleHeartbeatTick(delayMillis: Long) {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        val runnable = Runnable { runHeartbeatTick() }
        heartbeatRunnable = runnable
        heartbeatHandler.postDelayed(runnable, delayMillis)
    }

    private fun runHeartbeatTick() {
        EphemeralDispatch.post {
            try {
                // nativephp-fcm's own onNewToken()/RequestPermission bridge
                // function own this key -- a plain SharedPreferences read, so
                // this plugin has no compile-time dependency on Firebase
                // classes and keeps working (just with no token to report)
                // in an app that never installed nativephp-fcm.
                val fcmToken = getSharedPreferences(FCM_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getString(FCM_PREFS_KEY, null)

                val bridge = PHPBridge(applicationContext)
                val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"
                bridge.nativeEphemeralBoot(bootstrapPath)

                val command = if (!fcmToken.isNullOrEmpty()) {
                    val encodedToken = android.util.Base64.encodeToString(
                        fcmToken.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    "telephony:heartbeat --fcm-token=$encodedToken"
                } else {
                    "telephony:heartbeat"
                }
                Log.i(TAG, "calling nativeEphemeralArtisan: $command")
                bridge.nativeEphemeralArtisan(command)
            } catch (e: Throwable) {
                Log.e(TAG, "runHeartbeatTick FAILED", e)
            } finally {
                scheduleHeartbeatTick(HEARTBEAT_INTERVAL_SECONDS * 1000)
            }
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
                if (to.isEmpty()) return
                val body = job.optString("body", "")
                TelephonyGatewayFunctions.sendSmsDirect(applicationContext, to, body, id, subscriptionId, slot)
            }
            "ussd" -> {
                val code = job.optString("code")
                if (code.isEmpty()) return
                TelephonyGatewayFunctions.sendUssdDirect(applicationContext, code, id, subscriptionId, slot)
            }
            else -> Log.w(TAG, "dispatchClaimedJob: unknown job type in $job")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_POLL_NOW) {
            Log.i(TAG, "onStartCommand: ACTION_POLL_NOW -- polling immediately")
            schedulePollTick(0)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        Log.i(TAG, "onDestroy: CallLog observer unregistered")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Njiwa Gateway",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Njiwa Gateway active")
            .setContentText("Watching for missed calls and messages")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    /**
     * Queries CallLog for missed-call rows newer than the last one we've
     * already dispatched, using the row's own `_ID` both as the high-water
     * mark and as the idempotency key the backend expects
     * (`device_call_uid` -- see 19.20's spec). No ring-duration signal is
     * available from CallLog for a call that was never answered
     * (`DURATION` is 0) -- left null rather than invented; a real ring-time
     * would need TelephonyCallback/PhoneStateListener state tracking, a
     * separate, more fragile mechanism deferred past this pass.
     */
    private fun checkForNewMissedCalls() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val lastId = prefs.getLong(PREF_LAST_CALL_LOG_ID, 0L)

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )
        val selection = "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.TYPE} = ?"
        val selectionArgs = arrayOf(lastId.toString(), CallLog.Calls.MISSED_TYPE.toString())

        var maxId = lastId
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

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val number = cursor.getString(numberCol) ?: "unknown"
                    val date = cursor.getLong(dateCol)

                    dispatchMissedCall(id, number, date)
                    if (id > maxId) maxId = id
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "checkForNewMissedCalls: READ_CALL_LOG not granted", e)
            return
        }

        if (maxId > lastId) {
            prefs.edit().putLong(PREF_LAST_CALL_LOG_ID, maxId).apply()
        }
    }

    private fun dispatchMissedCall(callLogId: Long, number: String, occurredAt: Long) {
        Log.i(TAG, "dispatchMissedCall: id=$callLogId from=$number — dispatching to ephemeral PHP")

        try {
            val bridge = PHPBridge(applicationContext)
            val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"
            bridge.nativeEphemeralBoot(bootstrapPath)

            val payload = JSONObject().apply {
                put("device_call_uid", callLogId.toString())
                put("caller_number", number)
                put("occurred_at", occurredAt)
                put("slot", 0) // TODO: resolve real slot via PHONE_ACCOUNT_ID -> SubscriptionManager
                put("ring_duration_seconds", JSONObject.NULL)
            }.toString()

            val encoded = android.util.Base64.encodeToString(
                payload.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val command = "telephony:missed-call --payload=$encoded"

            Log.i(TAG, "calling nativeEphemeralArtisan: $command")
            val result = bridge.nativeEphemeralArtisan(command)
            Log.i(TAG, "ephemeral artisan result: $result")
        } catch (e: Throwable) {
            Log.e(TAG, "dispatchMissedCall FAILED", e)
        }
    }
}
