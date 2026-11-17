package com.blutrixx.plugins.nativephp_telephony_gateway

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONArray
import org.json.JSONObject

object TelephonyGatewayFunctions {

    private const val TAG = "TelephonyGateway.Functions"
    private const val USSD_TIMEOUT_MILLIS = 120_000L

    /** Starts the persistent foreground service hosting the CallLog observer + poll loop. */
    class StartService(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            // Read by BootReceiver: restart on boot/app update only while set.
            GatewayPrefs.setShouldRun(context, true)
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
            GatewayPrefs.setShouldRun(context, false)
            context.stopService(Intent(context, TelephonyGatewayService::class.java))
            return BridgeResponse.success(mapOf("running" to false))
        }
    }

    /**
     * Replaces the set of watched SIMs. Inbound SMS and missed calls on any
     * other SIM (or on a SIM that cannot be determined) are dropped in Kotlin
     * before reaching PHP, logs or storage. An empty list watches nothing.
     * Params: {subscription_ids: int[]}. Returns {subscription_ids: int[]}.
     */
    class SetWatchedSims(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val raw = parameters["subscription_ids"]
                ?: return BridgeResponse.error("missing_subscription_ids", "subscription_ids is required")
            val items: List<Any?> = when (raw) {
                is JSONArray -> (0 until raw.length()).map { raw.opt(it) }
                is List<*> -> raw
                is Array<*> -> raw.toList()
                else -> return BridgeResponse.error("invalid_subscription_ids", "subscription_ids must be an array of integers")
            }
            val ids = mutableSetOf<Int>()
            for (item in items) {
                val id = when (item) {
                    is Int -> item
                    is Long -> if (item in 0..Int.MAX_VALUE.toLong()) item.toInt() else null
                    is Number -> item.toDouble().let { d -> if (d == Math.floor(d) && d >= 0 && d <= Int.MAX_VALUE) d.toInt() else null }
                    is String -> item.trim().toIntOrNull()
                    else -> null
                }
                if (id == null || id < 0) {
                    return BridgeResponse.error("invalid_subscription_ids", "subscription_ids must be non-negative integers")
                }
                ids.add(id)
            }
            GatewayPrefs.setWatchedSims(context, ids)
            return BridgeResponse.success(mapOf("subscription_ids" to GatewayPrefs.watchedSims(context).sorted()))
        }
    }

    /** Returns {subscription_ids: int[]} -- empty when nothing is watched. */
    class GetWatchedSims(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return BridgeResponse.success(mapOf("subscription_ids" to GatewayPrefs.watchedSims(context).sorted()))
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
     * 11.91 -- whether Android's battery optimization (Doze/App Standby) is
     * currently allowed to restrict this app. A killed foreground service has
     * no recovery path except a manual reopen or a gateway_wake push landing
     * while the OS hasn't gone as far as fully stopping the app (see
     * nativephp-fcm's own onMessageReceived) -- exemption is the only way to
     * stop the OEM's own battery manager from killing the service at all.
     */
    class CheckBatteryOptimizationExemption(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val exempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            return BridgeResponse.success(mapOf("exempt" to exempt))
        }
    }

    /**
     * Launches the system's own exemption dialog -- there is no way to grant
     * this silently, Android requires an explicit user tap. Uses the
     * activity (not the bare application context) because
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS starts a system Activity,
     * which needs FLAG_ACTIVITY_NEW_TASK from a non-Activity context --
     * avoided entirely by starting it from the activity that's already on
     * screen. Requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS manifest
     * permission (normal, not runtime).
     */
    class RequestBatteryOptimizationExemption(private val activity: androidx.fragment.app.FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                BridgeResponse.success(mapOf("launched" to true))
            } catch (e: Exception) {
                Log.e(TAG, "RequestBatteryOptimizationExemption failed", e)
                BridgeResponse.error("LAUNCH_FAILED", e.message ?: "Failed to launch exemption prompt")
            }
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
    fun sendSmsDirect(context: Context, to: String, body: String, clientRef: String, subscriptionId: Int? = null, slot: Int? = null) {
        Log.i(TAG, "sendSmsDirect: clientRef=$clientRef bodyLen=${body.length} subId=$subscriptionId slot=$slot")

        // Unique per call, so callbacks of a retried message (same clientRef)
        // never merge into the previous attempt's aggregate.
        val attempt = java.util.UUID.randomUUID().toString()
        var partCount = 1
        try {
            @Suppress("DEPRECATION")
            val smsManager = if (subscriptionId != null) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(body)
            partCount = maxOf(parts.size, 1)

            val sentIntents = ArrayList<PendingIntent>()
            val deliveredIntents = ArrayList<PendingIntent>()
            for (part in 0 until partCount) {
                sentIntents.add(resultIntent(context, SmsResultReceiver.ACTION_SMS_SENT, clientRef, attempt, part, partCount, to, slot, mutable = false))
                // Mutable: the system fills the status-report PDU ("pdu"/"format")
                // into the delivered intent -- an IMMUTABLE PendingIntent drops
                // that fill-in, leaving no way to tell a failed delivery apart.
                deliveredIntents.add(resultIntent(context, SmsResultReceiver.ACTION_SMS_DELIVERED, clientRef, attempt, part, partCount, to, slot, mutable = true))
            }

            if (partCount > 1) {
                smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, deliveredIntents)
            } else {
                smsManager.sendTextMessage(to, null, body, sentIntents[0], deliveredIntents[0])
            }
        } catch (e: Throwable) {
            // Never handed to the radio: report the send as failed now instead
            // of leaving the job claimed until its lease expires.
            Log.e(TAG, "sendSmsDirect FAILED for clientRef=$clientRef", e)
            SmsResultReceiver.postReport(
                context,
                SmsResultReceiver.payload(
                    clientRef, "sent", false, SmsResultReceiver.RESULT_ERROR_GENERIC_FAILURE, to, slot,
                    partCount, null, null, e.message ?: e.javaClass.simpleName
                )
            )
        }
    }

    private fun resultIntent(
        context: Context,
        action: String,
        clientRef: String,
        attempt: String,
        part: Int,
        partCount: Int,
        to: String,
        slot: Int?,
        mutable: Boolean,
    ): PendingIntent {
        val intent = Intent(action)
            .setClass(context, SmsResultReceiver::class.java)
            .setPackage(context.packageName)
            // The data URI makes every (message attempt, event, part) a distinct
            // PendingIntent -- otherwise FLAG_UPDATE_CURRENT would let parts (or
            // two messages whose clientRef hashes collide) overwrite each other's extras.
            .setData(
                Uri.Builder().scheme("telephony-gateway").authority("sms-result")
                    .appendPath(clientRef).appendPath(attempt).appendPath(action).appendPath(part.toString())
                    .build()
            )
            .putExtra(SmsResultReceiver.EXTRA_CLIENT_REF, clientRef)
            .putExtra(SmsResultReceiver.EXTRA_TO, to)
            .putExtra(SmsResultReceiver.EXTRA_SLOT, slot ?: -1)
            .putExtra(SmsResultReceiver.EXTRA_ATTEMPT, attempt)
            .putExtra(SmsResultReceiver.EXTRA_PART_INDEX, part)
            .putExtra(SmsResultReceiver.EXTRA_PART_COUNT, partCount)
        val mutability = if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    /**
     * The actual sendUssdRequest call, callable directly from the poll loop,
     * SendUssd, or a check_balance device command. Every outcome -- response,
     * callback failure, or an exception before/while issuing the request --
     * ends in exactly one `telephony:ussd-result` run. [onComplete] (main
     * thread) fires after that outcome is known, so callers can serialize
     * sessions (a modem runs one USSD session at a time).
     */
    fun sendUssdDirect(
        context: Context,
        code: String,
        requestId: String,
        subscriptionId: Int? = null,
        slot: Int? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        Log.i(TAG, "sendUssdDirect: requestId=$requestId subId=$subscriptionId slot=$slot")
        val handler = Handler(Looper.getMainLooper())
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(responseText: String?, failureCode: String?, error: String?) {
            if (!finished.compareAndSet(false, true)) return
            dispatchUssdResult(context, requestId, responseText, failureCode, slot, error)
            onComplete?.let { handler.post(it) }
        }

        try {
            val base = context.getSystemService(TelephonyManager::class.java)
                ?: throw IllegalStateException("TelephonyManager unavailable")
            val telephonyManager = if (subscriptionId != null) base.createForSubscriptionId(subscriptionId) else base

            telephonyManager.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(tm: TelephonyManager, request: String, response: CharSequence) {
                    finish(response.toString(), null, null)
                }

                override fun onReceiveUssdResponseFailed(tm: TelephonyManager, request: String, failureCode: Int) {
                    val reason = when (failureCode) {
                        TelephonyManager.USSD_RETURN_FAILURE -> "USSD request failed (network or carrier returned failure)"
                        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "USSD service unavailable"
                        else -> "USSD failed with code $failureCode"
                    }
                    finish(null, "USSD_FAILURE_$failureCode", reason)
                }
            }, handler)
            // Some OEM stacks never call back; without this a claimed job (or a
            // serialized check_balance chain) would wait forever.
            handler.postDelayed({
                finish(null, "USSD_TIMEOUT", "no USSD response within ${USSD_TIMEOUT_MILLIS / 1000} s")
            }, USSD_TIMEOUT_MILLIS)
        } catch (e: Throwable) {
            Log.e(TAG, "sendUssdDirect FAILED for requestId=$requestId", e)
            val failureCode = if (e is SecurityException) "USSD_PERMISSION_DENIED" else "USSD_EXCEPTION"
            finish(null, failureCode, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Payload keeps request_id/response_text/failure_code/slot; `error` is additive. */
    fun dispatchUssdResult(context: Context, requestId: String, responseText: String?, failureCode: String?, slot: Int? = null, error: String? = null) {
        Log.i(TAG, "USSD result: requestId=$requestId failureCode=$failureCode slot=$slot")
        val payload = JSONObject().apply {
            put("request_id", requestId)
            put("response_text", responseText ?: JSONObject.NULL)
            put("failure_code", failureCode ?: JSONObject.NULL)
            put("slot", slot ?: JSONObject.NULL)
            put("error", error ?: JSONObject.NULL)
        }
        EphemeralArtisan.post(context, "telephony:ussd-result", payload) { result ->
            Log.i(TAG, "telephony:ussd-result requestId=$requestId reported=${result.ok}")
        }
    }
}
