package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts TelephonyGatewayService after a reboot or an app update -- but
 * only while the "should run" flag is set (StartService sets it, StopService
 * clears it), so a gateway the user stopped stays stopped.
 *
 * Android 12+ forbids starting a foreground service from the background
 * except for listed exemptions; receiving BOOT_COMPLETED or
 * MY_PACKAGE_REPLACED is one of them. Android 15 removed that exemption for
 * some types (dataSync, camera, mediaPlayback, ...) but not remoteMessaging,
 * this service's type -- so this starts the service on every version. A
 * start that is nevertheless refused is caught (here and in the service's
 * onCreate()) and a "paused" notification that opens the app is shown.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TelephonyGateway.Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (!GatewayPrefs.shouldRun(context)) {
            Log.i(TAG, "$action: gateway not enabled -- not starting")
            return
        }

        Log.i(TAG, "$action: starting TelephonyGatewayService")
        try {
            val serviceIntent = Intent(context, TelephonyGatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "$action: foreground service start refused", e)
            TelephonyGatewayService.postPausedNotification(
                context,
                "Android did not allow the gateway to start automatically. Tap to open the app."
            )
        }
    }
}
