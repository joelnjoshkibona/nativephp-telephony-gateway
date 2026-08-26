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

    /** Starts the persistent foreground service hosting the CallLog observer. */
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
     * Send an SMS. Synchronous only in the sense of "handed to SmsManager" --
     * the real outcome (sent/failed, later delivered) arrives asynchronously via
     * SmsResultReceiver, which reuses the same ephemeral-bridge dispatch already
     * proven for SMS_RECEIVED and missed calls.
     *
     * No per-SIM slot targeting yet (TODO, same as the receive/missed-call
     * paths) -- SmsManager.getDefault() uses whatever the OS's default SMS
     * subscription is. Real slot targeting needs ListSims + subscription_id
     * resolution, not yet built.
     */
    class SendSms(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val to = parameters["to"] as? String
                ?: return BridgeResponse.error("missing_to", "to is required")
            val body = parameters["body"] as? String
                ?: return BridgeResponse.error("missing_body", "body is required")
            val clientRef = parameters["client_ref"] as? String ?: java.util.UUID.randomUUID().toString()

            Log.i(TAG, "SendSms: to=$to clientRef=$clientRef bodyLen=${body.length}")

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
            val smsManager = SmsManager.getDefault()

            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                val sentIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(sentIntent) } }
                val deliveredIntents = ArrayList<PendingIntent>().apply { repeat(parts.size) { add(deliveredIntent) } }
                smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, deliveredIntents)
            } else {
                smsManager.sendTextMessage(to, null, body, sentIntent, deliveredIntent)
            }

            return BridgeResponse.success(mapOf("client_ref" to clientRef, "queued" to true))
        }
    }

    /**
     * Dial a USSD code. `TelephonyManager.sendUssdRequest`'s callback fires
     * directly (no broadcast/PendingIntent involved) -- this is a THIRD distinct
     * shape of headless-to-PHP dispatch, alongside the proven receiver and
     * ContentObserver shapes.
     *
     * On an emulator there is no real carrier to answer a USSD session, so a
     * failure callback here is expected and uninformative about real per-operator
     * viability -- that question is unrelated to and unresolved by this call
     * working mechanically. See docs/specs/mobile/telephony-gateway-plugin.md.
     */
    class SendUssd(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val code = parameters["code"] as? String
                ?: return BridgeResponse.error("missing_code", "code is required")
            val requestId = parameters["request_id"] as? String ?: java.util.UUID.randomUUID().toString()

            Log.i(TAG, "SendUssd: code=$code requestId=$requestId")

            val telephonyManager = context.getSystemService(TelephonyManager::class.java)
            val handler = Handler(Looper.getMainLooper())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telephonyManager.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(tm: TelephonyManager, request: String, response: CharSequence) {
                        dispatch(requestId, responseText = response.toString(), failureCode = null)
                    }

                    override fun onReceiveUssdResponseFailed(tm: TelephonyManager, request: String, failureCode: Int) {
                        dispatch(requestId, responseText = null, failureCode = "USSD_FAILURE_$failureCode")
                    }
                }, handler)
            } else {
                return BridgeResponse.error("unsupported_sdk", "sendUssdRequest requires API 26+")
            }

            return BridgeResponse.success(mapOf("request_id" to requestId, "queued" to true))
        }

        private fun dispatch(requestId: String, responseText: String?, failureCode: String?) {
            Log.i(TAG, "SendUssd result: requestId=$requestId failureCode=$failureCode")
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
                    Log.e(TAG, "SendUssd dispatch FAILED", e)
                }
            }
        }
    }
}
