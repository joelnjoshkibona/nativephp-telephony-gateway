package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject

/**
 * Headless receiver -- no Activity, no WebView, possibly not even the app's
 * own UI process foregrounded -- reaches PHP via NativePHP's ephemeral
 * runtime (nativeEphemeralBoot + nativeEphemeralArtisan), a SEPARATE
 * interpreter from the one serving the WebView (see PHPBridge.kt /
 * php_bridge.c's g_ephemeral_mutex). See docs/specs/mobile/
 * telephony-gateway-plugin.md for the spike that proved this mechanism.
 *
 * The SMS is written to GatewayPrefs' inbox and onReceive returns at once. It does not wait for PHP: the one worker
 * thread may be in a network call (claim, upload, heartbeat: up to 15 s each), and an SMS broadcast held past ~10 s
 * is an ANR ("Njiwa isn't responding", seen on a Galaxy A04 in the field). The worker journals it and releases it;
 * one left behind by a dead process is journaled on the next heartbeat (SmsInbox.drain).
 */
class SmsReceivedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TelephonyGateway.SmsReceived"
        private const val EXTRA_SUBSCRIPTION_LEGACY = "subscription"
        private const val EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        // Privacy gate, FIRST: an SMS on a SIM that is not explicitly watched
        // (or whose SIM cannot be determined) is dropped here -- before the
        // PDUs are even parsed, and without logging anything about it.
        val subscriptionId = GatewayRules.resolveSmsSubscription(
            intExtraOrNull(intent, EXTRA_SUBSCRIPTION_LEGACY),
            intExtraOrNull(intent, EXTRA_SUBSCRIPTION_INDEX),
        )
        if (subscriptionId == null || !GatewayRules.isWatched(subscriptionId, GatewayPrefs.watchedSims(context))) {
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
        // The OS never assigns this a provider row id before this receiver runs
        // (that only happens once inserted into the Sms ContentProvider, not
        // guaranteed here for a non-default-SMS-app) -- a sha256 of the
        // message's own identifying fields stands in (see GatewayRules.inboundSmsUid).
        val deviceMessageUid = GatewayRules.inboundSmsUid(subscriptionId, from, body, receivedAt)
        val slot = SimSlots.slotForSubscriptionId(context, subscriptionId) ?: 0

        Log.i(TAG, "onReceive: SMS subId=$subscriptionId slot=$slot bodyLen=${body.length} -- dispatching to ephemeral PHP")

        val payload = JSONObject().apply {
            put("device_message_uid", deviceMessageUid)
            put("from", from)
            put("body", body)
            put("slot", slot)
            put("received_at", receivedAt)
            put("subscription_id", subscriptionId)
        }

        // --payload=, not a positional argument -- Runtime::artisan()'s
        // parser only correctly binds `--key=value` tokens; see
        // TelephonySmsReceivedCommand's own docblock in the host app.
        GatewayPrefs.holdInbound(context, deviceMessageUid, payload)
        EphemeralDispatch.post { SmsInbox.journal(context.applicationContext, payload) }
    }

    /** Null when absent; tolerates the extra being stored as Int or Long. */
    private fun intExtraOrNull(intent: Intent, key: String): Int? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(key)) return null
        @Suppress("DEPRECATION")
        return (extras.get(key) as? Number)?.toInt()
    }
}
