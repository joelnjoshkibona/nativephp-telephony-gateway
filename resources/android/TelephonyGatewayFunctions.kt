package com.blutrixx.plugins.nativephp_telephony_gateway

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.bridge.PHPBridge
import org.json.JSONObject

object TelephonyGatewayFunctions {

    private const val TAG = "TelephonyGateway.Functions"

    /** Starts the persistent foreground service hosting the CallLog observer + poll loop. */
    class StartService(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val intent = Intent(context, TelephonyGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return BridgeResponse.success(mapOf("running" to true))
        }
    }

    class StopService(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            context.stopService(Intent(context, TelephonyGatewayService::class.java))
            return BridgeResponse.success(mapOf("running" to false))
        }
    }

    /**
     * Real per-slot SIM enumeration for enrollment (11.10's `sims[]`) and the
     * poll loop's own claim payload (14.20's `slots[]`) -- see SimSlots.kt.
     */
    class ListSims(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val sims = SimSlots.listActive(context).map {
                mapOf(
                    "slot" to it.slot,
                    "subscription_id" to it.subscriptionId,
                    "phone_number" to it.phoneNumber,
                    "operator" to it.operator,
                    "is_present" to true,
                )
            }
            return BridgeResponse.success(mapOf("sims" to sims))
        }
    }

    /**
     * Send an SMS. Synchronous only in the sense of "handed to SmsManager" --
     * the real outcome (sent/failed, later delivered) arrives asynchronously via
     * SmsResultReceiver, which reuses the same ephemeral-bridge dispatch already
     * proven for SMS_RECEIVED and missed calls.
     *
     * The real work lives in the top-level sendSmsDirect() below -- called both
     * from this bridge class (JS-triggered, foreground) and directly from
     * TelephonyGatewayService's poll loop (headless, no bridge round trip: the
     * ephemeral runtime that loop dispatches through cannot call nativephp_call()
     * at all -- confirmed via this project's own spike, see
     * docs/specs/mobile/telephony-gateway-plugin.md).
     */
    class SendSms(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val to = parameters["to"] as? String
                ?: return BridgeResponse.error("missing_to", "to is required")
            val body = parameters["body"] as? String
                ?: return BridgeResponse.error("missing_body", "body is required")
            val clientRef = parameters["client_ref"] as? String ?: java.util.UUID.randomUUID().toString()
            val subscriptionId = (parameters["subscription_id"] as? Number)?.toInt()

            sendSmsDirect(context, to, body, clientRef, subscriptionId)

            return BridgeResponse.success(mapOf("client_ref" to clientRef, "queued" to true))
        }
    }

    /**
     * Dial a USSD code. `TelephonyManager.sendUssdRequest`'s callback fires
     * directly (no broadcast/PendingIntent involved) -- this is a THIRD distinct
     * shape of headless-to-PHP dispatch, alongside the proven receiver and
     * ContentObserver shapes.
     *
     * On an emulator there is no real carrier to answer a USSD session; on real
     * hardware (this session's own live test, Tigo Tanzania) a real balance
     * response came back, proving the mechanism end to end.
     */
    class SendUssd(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val code = parameters["code"] as? String
                ?: return BridgeResponse.error("missing_code", "code is required")
            val requestId = parameters["request_id"] as? String ?: java.util.UUID.randomUUID().toString()
            val subscriptionId = (parameters["subscription_id"] as? Number)?.toInt()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return BridgeResponse.error("unsupported_sdk", "sendUssdRequest requires API 26+")
            }

            sendUssdDirect(context, code, requestId, subscriptionId)

            return BridgeResponse.success(mapOf("request_id" to requestId, "queued" to true))
        }
    }

    /**
     * The actual SmsManager call, callable directly (no bridge, no JSON round
     * trip) from TelephonyGatewayService's poll loop as well as from SendSms
     * above. `subscriptionId` targets a specific SIM when known (a claimed
     * job's own slot, resolved via ListSims/SimSlots) -- null falls back to
     * SmsManager.getDefault(), matching every pre-poll-loop caller's behavior.
     */
    fun sendSmsDirect(context: Context, to: String, body: String, clientRef: String, subscriptionId: Int? = null) {
        Log.i(TAG, "sendSmsDirect: to=$to clientRef=$clientRef bodyLen=${body.length} subId=$subscriptionId")

        val sentIntent = PendingIntent.getBroadcast(
            context, clientRef.hashCode(),
            Intent(SmsResultReceiver.ACTION_SMS_SENT)
                .setPackage(context.packageName)
                .putExtra("client_ref", clientRef),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deliveredIntent = PendingIntent.getBroadcast(
            context, clientRef.hashCode(),
            Intent(SmsResultReceiver.ACTION_SMS_DELIVERED)
                .setPackage(context.packageName)
                .putExtra("client_ref", clientRef),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        @Suppress("DEPRECATION")
        val smsManager = if (subscriptionId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getDefault()
        }

        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            val sentIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sentIntent) } }
            val deliveredIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(deliveredIntent) } }
            smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, deliveredIntents)
        } else {
            smsManager.sendTextMessage(to, null, body, sentIntent, deliveredIntent)
        }
    }

    /** The actual sendUssdRequest call, callable directly from the poll loop or SendUssd. */
    fun sendUssdDirect(context: Context, code: String, requestId: String, subscriptionId: Int? = null) {
        Log.i(TAG, "sendUssdDirect: code=$code requestId=$requestId subId=$subscriptionId")

        val telephonyManager = context.getSystemService(TelephonyManager::class.java).let {
            if (subscriptionId != null) it.createForSubscriptionId(subscriptionId) else it
        }
        val handler = Handler(Looper.getMainLooper())

        telephonyManager.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(tm: TelephonyManager, request: String, response: CharSequence) {
                dispatchUssdResult(context, requestId, responseText = response.toString(), failureCode = null)
            }

            override fun onReceiveUssdResponseFailed(tm: TelephonyManager, request: String, failureCode: Int) {
                dispatchUssdResult(context, requestId, responseText = null, failureCode = "USSD_FAILURE_$failureCode")
            }
        }, handler)
    }

    private fun dispatchUssdResult(context: Context, requestId: String, responseText: String?, failureCode: String?) {
        Log.i(TAG, "USSD result: requestId=$requestId failureCode=$failureCode")
        EphemeralDispatch.post {
            try {
                val bridge = PHPBridge(context.applicationContext)
                val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"
                bridge.nativeEphemeralBoot(bootstrapPath)

                val payload = JSONObject().apply {
                    put("request_id", requestId)
                    put("response_text", responseText)
                    put("failure_code", failureCode)
                }.toString()
                val encoded = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val command = "telephony:ussd-result --payload=$encoded"

                Log.i(TAG, "calling nativeEphemeralArtisan: $command")
                val result = bridge.nativeEphemeralArtisan(command)
                Log.i(TAG, "ephemeral artisan result: $result")
            } catch (e: Throwable) {
                Log.e(TAG, "dispatchUssdResult FAILED", e)
            }
        }
    }
}
