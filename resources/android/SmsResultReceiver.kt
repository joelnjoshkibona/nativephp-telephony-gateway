package com.blutrixx.plugins.nativephp_telephony_gateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.nativephp.mobile.bridge.PHPBridge
import org.json.JSONObject

/**
 * Receives SmsManager's sentIntent/deliveredIntent callbacks -- fired by the
 * system calling PendingIntent.send() with a resultCode, same headless-receiver
 * shape already proven for SMS_RECEIVED (goAsync() + ephemeral bridge dispatch).
 * Internal-only (exported=false in nativephp.json): only this app's own
 * SendSms bridge function ever creates these PendingIntents.
 */
class SmsResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "com.blutrixx.plugins.nativephp_telephony_gateway.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.blutrixx.plugins.nativephp_telephony_gateway.SMS_DELIVERED"
        private const val TAG = "TelephonyGateway.SmsResult"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            ACTION_SMS_SENT -> "sent"
            ACTION_SMS_DELIVERED -> "delivered"
            else -> return
        }
        val clientRef = intent.getStringExtra("client_ref") ?: "unknown"
        val ok = resultCode == Activity.RESULT_OK

        Log.i(TAG, "onReceive: event=$event clientRef=$clientRef ok=$ok resultCode=$resultCode")

        val pendingResult = goAsync()
        EphemeralDispatch.post {
            try {
                val appContext = context.applicationContext
                val bridge = PHPBridge(appContext)
                val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"
                bridge.nativeEphemeralBoot(bootstrapPath)

                val payload = JSONObject().apply {
                    put("client_ref", clientRef)
                    put("event", event)
                    put("ok", ok)
                    put("result_code", resultCode)
                }.toString()

                val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val command = "telephony:sms-result --payload=$encoded"

                Log.i(TAG, "calling nativeEphemeralArtisan: $command")
                val result = bridge.nativeEphemeralArtisan(command)
                Log.i(TAG, "ephemeral artisan result: $result")
            } catch (e: Throwable) {
                Log.e(TAG, "SPIKE FAILED: SmsResultReceiver dispatch", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
