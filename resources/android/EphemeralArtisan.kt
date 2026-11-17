package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nativephp.mobile.bridge.PHPBridge
import org.json.JSONObject

/**
 * The one way this plugin runs a host-app artisan command: boot NativePHP's
 * ephemeral runtime and call nativeEphemeralArtisan(), with the payload as
 * base64 JSON in `--payload=` (Runtime::artisan() splits on spaces with
 * str_getcsv, so raw JSON does not survive; base64 has no spaces/quotes).
 *
 * Exit status: Runtime::artisan() (nativephp/mobile 3.3.x and 4.x) drops
 * Artisan::call()'s return value, so a command's own `return self::FAILURE`
 * is NOT observable here. What is observable: the bridge throwing, the boot
 * failing, the C wrapper's "Ephemeral artisan error: ..." text for a Throwable
 * escaping the command, and the "not initialized" text. [Result.ok] is false
 * for exactly those; a host command that wants Kotlin to treat its run as
 * failed must therefore let an exception escape handle().
 */
object EphemeralArtisan {
    private const val TAG = "TelephonyGateway.Artisan"

    data class Result(val ok: Boolean, val output: String?, val error: String?)

    /** Enqueue on the single EphemeralDispatch thread. */
    fun post(context: Context, command: String, payload: JSONObject?, onDone: ((Result) -> Unit)? = null) {
        val appContext = context.applicationContext
        EphemeralDispatch.post {
            val result = runOnDispatchThread(appContext, command, payload)
            try {
                onDone?.invoke(result)
            } catch (e: Throwable) {
                Log.e(TAG, "onDone for $command threw", e)
            }
        }
    }

    /**
     * Must be called ON the EphemeralDispatch thread (a second thread into the
     * ephemeral runtime segfaults -- see EphemeralDispatch). Never throws.
     */
    fun runOnDispatchThread(context: Context, command: String, payload: JSONObject?): Result {
        return try {
            val full = if (payload == null) {
                command
            } else {
                val encoded = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                "$command --payload=$encoded"
            }
            if (full.toByteArray(Charsets.UTF_8).size > GatewayRules.MAX_EPHEMERAL_COMMAND_BYTES) {
                Log.e(TAG, "$command: command is ${full.length} bytes, over the ephemeral eval buffer -- not sent")
                return Result(false, null, "command exceeds the ephemeral runtime's eval buffer")
            }

            val bridge = PHPBridge(context.applicationContext)
            val bootstrapPath = "${bridge.getLaravelPath()}/bootstrap/android/ephemeral.php"
            val boot = bridge.nativeEphemeralBoot(bootstrapPath)
            if (boot != 0) {
                Log.e(TAG, "$command: nativeEphemeralBoot returned $boot")
                return Result(false, null, "ephemeral boot returned $boot")
            }

            Log.i(TAG, "calling nativeEphemeralArtisan: $command")
            val output = bridge.nativeEphemeralArtisan(full)
            if (GatewayRules.artisanOutputSignalsFailure(output)) {
                Log.e(TAG, "$command failed: $output")
                Result(false, output, output.trim())
            } else {
                Result(true, output, null)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "$command threw", e)
            Result(false, null, e.message ?: e.javaClass.simpleName)
        }
    }
}
