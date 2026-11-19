package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Received SMS from GatewayPrefs' inbox into the app's journal (`telephony:sms-received`). Runs on the EphemeralDispatch
 * thread only. An SMS stays held until the command succeeds, so one the process died on, or one whose command failed,
 * is tried again by drain() on the next heartbeat.
 */
object SmsInbox {
    private const val TAG = "TelephonyGateway.SmsInbox"

    fun journal(context: Context, payload: JSONObject) {
        val uid = payload.optString("device_message_uid")
        if (uid.isEmpty() || !GatewayPrefs.isHeld(context, uid)) return // already journaled by an earlier pass
        val result = EphemeralArtisan.runOnDispatchThread(context, "telephony:sms-received", payload)
        Log.i(TAG, "telephony:sms-received ok=${result.ok}")
        if (result.ok) GatewayPrefs.releaseInbound(context, uid)
    }

    fun drain(context: Context) {
        val held = GatewayPrefs.heldInbound(context)
        if (held.isNotEmpty()) Log.i(TAG, "drain: ${held.size} received SMS still to journal")
        held.forEach { journal(context, it) }
    }
}
