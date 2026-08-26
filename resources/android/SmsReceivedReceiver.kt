package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.nativephp.mobile.bridge.PHPBridge
import org.json.JSONObject

/**
 * SPIKE (docs/specs/mobile/telephony-gateway-plugin.md): proves a headless
 * receiver -- no Activity, no WebView, possibly not even the app's own UI
 * process foregrounded -- can reach PHP via NativePHP's ephemeral runtime
 * (nativeEphemeralBoot + nativeEphemeralArtisan), which is a SEPARATE
 * interpreter from the one serving the WebView (see PHPBridge.kt /
 * php_bridge.c's g_ephemeral_mutex).
 *
 * goAsync() extends this receiver's lifetime past onReceive()'s return so the
 * (potentially slow, first-boot) JNI call can run on a background thread
 * without tripping Android's ~10s broadcast-receiver ANR timeout.
 */
class SmsReceivedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TelephonyGateway.SmsReceived"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "SMS_RECEIVED with no messages in intent")
            return
        }

        val from = messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val receivedAt = messages[0].timestampMillis

        Log.i(TAG, "onReceive: SMS from=$from bodyLen=${body.length} — dispatching to ephemeral PHP")

        val pendingResult = goAsync()
        EphemeralDispatch.post {
            try {
                val appContext = context.applicationContext
                val bridge = PHPBridge(appContext)
                val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"

                Log.i(TAG, "booting ephemeral runtime: $bootstrapPath")
                bridge.nativeEphemeralBoot(bootstrapPath)

                val payload = JSONObject().apply {
                    put("from", from)
                    put("body", body)
                    put("slot", 0) // TODO: resolve real slot via SubscriptionManager once past the spike
                    put("received_at", receivedAt)
                }.toString()

                // Runtime::artisan() splits the command with str_getcsv($command, ' ', '"', '\\')
                // -- PHP's CSV escape handling is notoriously inconsistent with embedded
                // quotes/backslashes (confirmed live: a \"-escaped JSON string came back
                // "Not enough arguments", str_getcsv silently mis-split it). Base64 has no
                // spaces, quotes, or backslashes, so it can't collide with that parser at all.
                val encoded = android.util.Base64.encodeToString(
                    payload.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                // --payload=, not a positional argument -- Runtime::artisan()'s
                // parser only correctly binds `--key=value` tokens to
                // Artisan::call()'s expected shape; see TelephonySmsReceivedCommand's
                // own docblock for the confirmed vendor bug this works around.
                val command = "telephony:sms-received --payload=$encoded"

                Log.i(TAG, "calling nativeEphemeralArtisan: $command")
                val result = bridge.nativeEphemeralArtisan(command)
                Log.i(TAG, "ephemeral artisan result: $result")
            } catch (e: Throwable) {
                Log.e(TAG, "SPIKE FAILED: headless bridge threw", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
