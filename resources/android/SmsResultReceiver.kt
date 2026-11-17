package com.blutrixx.plugins.nativephp_telephony_gateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import org.json.JSONObject

/**
 * Receives SmsManager's sentIntent/deliveredIntent callbacks -- one per PART
 * of a message -- and turns them into ONE `telephony:sms-result` per message
 * per event via PartAggregator (sent: once every part reported; delivered:
 * once every part's delivery report arrived). Internal-only (exported=false
 * in nativephp.json): only sendSmsDirect() creates these PendingIntents, and
 * they are explicit (component-targeted).
 */
class SmsResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SMS_SENT = "com.blutrixx.plugins.nativephp_telephony_gateway.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.blutrixx.plugins.nativephp_telephony_gateway.SMS_DELIVERED"

        const val EXTRA_CLIENT_REF = "client_ref"
        const val EXTRA_TO = "to"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_ATTEMPT = "attempt"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"

        private const val TAG = "TelephonyGateway.SmsResult"

        /** SmsManager.RESULT_ERROR_GENERIC_FAILURE -- used when sending failed before SmsManager took the message. */
        const val RESULT_ERROR_GENERIC_FAILURE = 1

        fun aggregateKey(event: String, clientRef: String, attempt: String) = "$event|$clientRef|$attempt"

        fun payload(
            clientRef: String,
            event: String,
            ok: Boolean,
            resultCode: Int?,
            to: String?,
            slot: Int?,
            parts: Int,
            failedPart: Int?,
            statusCode: Int?,
            error: String?,
        ): JSONObject = JSONObject().apply {
            // Every field the host command has always read is kept;
            // parts / failed_part / status_code / error are additive.
            put("client_ref", clientRef)
            put("event", event)
            put("ok", ok)
            put("result_code", resultCode ?: JSONObject.NULL)
            put("to", to ?: JSONObject.NULL)
            put("slot", slot ?: JSONObject.NULL)
            put("parts", parts)
            put("failed_part", failedPart ?: JSONObject.NULL)
            put("status_code", statusCode ?: JSONObject.NULL)
            put("error", error ?: JSONObject.NULL)
        }

        /** Enqueue a `telephony:sms-result` run on the EphemeralDispatch thread. */
        fun postReport(context: Context, payload: JSONObject) {
            EphemeralArtisan.post(context, "telephony:sms-result", payload) { result ->
                Log.i(TAG, "telephony:sms-result ${payload.optString("event")} ok=${payload.optBoolean("ok")} reported=${result.ok}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            ACTION_SMS_SENT -> "sent"
            ACTION_SMS_DELIVERED -> "delivered"
            else -> return
        }
        val clientRef = intent.getStringExtra(EXTRA_CLIENT_REF) ?: "unknown"
        val to = intent.getStringExtra(EXTRA_TO)
        val slotExtra = intent.getIntExtra(EXTRA_SLOT, -1)
        val slot: Int? = if (slotExtra >= 0) slotExtra else null
        // Intents created before this version carry no attempt/part extras:
        // treat them as a single-part message, as they were reported before.
        val attempt = intent.getStringExtra(EXTRA_ATTEMPT) ?: "legacy"
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, 0)
        val partCount = intent.getIntExtra(EXTRA_PART_COUNT, 1)
        val code = resultCode

        var statusCode: Int? = null
        val partOk: Boolean
        if (event == "delivered") {
            // The real outcome of a delivery report is the TP-Status inside the
            // status-report PDU the system fills into this (mutable) intent --
            // resultCode is RESULT_OK for any report that was received at all.
            val pdu = intent.getByteArrayExtra("pdu")
            val format = intent.getStringExtra("format")
            val message = pdu?.let {
                try {
                    SmsMessage.createFromPdu(it, format)
                } catch (e: Throwable) {
                    Log.w(TAG, "could not parse status-report PDU", e)
                    null
                }
            }
            if (message != null) {
                statusCode = message.status
                when (GatewayRules.classifyDeliveryStatus(format, message.status)) {
                    GatewayRules.DeliveryKind.PENDING -> {
                        // SC is still trying -- a final report for this part follows.
                        Log.i(TAG, "delivery report pending: clientRef=$clientRef part=$partIndex status=$statusCode")
                        return
                    }
                    GatewayRules.DeliveryKind.SUCCESS -> partOk = code == Activity.RESULT_OK
                    GatewayRules.DeliveryKind.FAILED -> partOk = false
                }
            } else {
                partOk = code == Activity.RESULT_OK
            }
        } else {
            partOk = code == Activity.RESULT_OK
        }

        Log.i(TAG, "onReceive: event=$event clientRef=$clientRef part=${partIndex + 1}/$partCount ok=$partOk resultCode=$code status=$statusCode")

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        EphemeralDispatch.post {
            try {
                val outcome = GatewayPrefs.recordSmsPart(
                    appContext, aggregateKey(event, clientRef, attempt),
                    partCount, partIndex, partOk, code, statusCode
                )
                if (outcome != null) {
                    if (event == "sent" && !outcome.ok) {
                        // Delivery reports never come for parts that were not sent.
                        GatewayPrefs.forgetSmsParts(appContext, aggregateKey("delivered", clientRef, attempt))
                    }
                    val payload = payload(
                        clientRef, event, outcome.ok, outcome.resultCode, to, slot,
                        outcome.parts, outcome.failedPart, outcome.statusCode, null
                    )
                    // Already on the dispatch thread: run inline so it completes
                    // inside this receiver's goAsync() window.
                    val result = EphemeralArtisan.runOnDispatchThread(appContext, "telephony:sms-result", payload)
                    Log.i(TAG, "telephony:sms-result $event clientRef=$clientRef ok=${outcome.ok} reported=${result.ok}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "SmsResultReceiver aggregation failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
