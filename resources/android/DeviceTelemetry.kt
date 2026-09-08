package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log

/**
 * Battery + network telemetry for the heartbeat loop -- plain functions
 * called directly from TelephonyGatewayService.runHeartbeatTick(), same
 * shape as SimSlots.listActive(), not a JS-callable bridge function (nothing
 * outside this plugin's own background loop needs these reads).
 */
object DeviceTelemetry {
    private const val TAG = "TelephonyGateway.DeviceTelemetry"

    data class Battery(val percent: Int?, val isCharging: Boolean?)

    /**
     * Reads the last-known battery state via the sticky ACTION_BATTERY_CHANGED
     * broadcast -- registering a null receiver for this specific action
     * returns the system's cached sticky Intent immediately with no listener
     * left registered afterward, the standard one-shot read (no permission
     * required, works from a background service same as a foreground one).
     * Best-effort: nulls (not a thrown exception) if the sticky broadcast is
     * ever unavailable, since telemetry must never be able to break the
     * heartbeat tick around it.
     */
    fun readBattery(context: Context): Battery {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return Battery(null, null)

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else null

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = if (status >= 0) {
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            } else null

            Battery(percent, isCharging)
        } catch (e: Throwable) {
            Log.w(TAG, "readBattery failed", e)
            Battery(null, null)
        }
    }

    /**
     * "wifi" | "mobile" | "none" -- matches the consuming app's own
     * devices.network_type column convention. Deliberately coarse: finer
     * cellular generation detail (3G/4G/5G) needs READ_PHONE_STATE's
     * TelephonyDisplayInfo path and isn't part of that column's documented
     * value set -- add it there (a separate, additive column) rather than
     * overloading this one if it's ever needed.
     */
    fun readNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "none"
            val network = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(network) ?: return "none"

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "none"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "readNetworkType failed", e)
            "none"
        }
    }
}
