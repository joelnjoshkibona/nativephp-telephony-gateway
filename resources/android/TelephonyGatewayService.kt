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
    }

    private var observer: ContentObserver? = null

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
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer!!)
        Log.i(TAG, "onCreate: foreground service started, CallLog observer registered")

        // Catch anything that landed before the observer was registered.
        EphemeralDispatch.post { checkForNewMissedCalls() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
