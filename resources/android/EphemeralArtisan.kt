package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import com.nativephp.mobile.bridge.PHPBridge
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * The one way this plugin runs a host-app artisan command: boot NativePHP's
 * ephemeral runtime and call nativeEphemeralArtisan(), with the payload as
 * base64 JSON in `--payload=` (Runtime::artisan() splits on spaces with
 * str_getcsv, so raw JSON does not survive; base64 has no spaces/quotes).
 * A payload too large for NativePHP's 4096-byte eval buffer is written to
 * storage/app/gateway_payloads/<uuid>.json instead and passed as
 * `--payload-file=<uuid>.json` (plain JSON, not base64); the host command
 * reads and deletes it.
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
            val payloadJson = payload?.toString()
            var full = if (payloadJson == null) {
                command
            } else {
                val encoded = Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                "$command --payload=$encoded"
            }
            if (payloadJson != null && full.toByteArray(Charsets.UTF_8).size > GatewayRules.MAX_EPHEMERAL_COMMAND_BYTES) {
                // Too big for the eval buffer: hand the payload over as a file.
                val fileName = writePayloadFile(context, command, payloadJson)
                full = "$command --payload-file=$fileName"
                Log.i(TAG, "$command: payload is ${payloadJson.length} chars -- passed as --payload-file")
            }
            if (full.toByteArray(Charsets.UTF_8).size > GatewayRules.MAX_EPHEMERAL_COMMAND_BYTES) {
                Log.e(TAG, "$command: command is ${full.length} bytes, over the ephemeral eval buffer -- not sent")
                return Result(false, null, "command exceeds the ephemeral runtime's eval buffer")
            }

            val bridge = PHPBridge(context.applicationContext)
            val bootstrapPath = bootstrapScript(bridge.getLaravelPath())
                ?: return Result(false, null, "no PHP bootstrap found (bootstrap/android/ephemeral.php or NativePHP's persistent.php)")
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

    private const val PAYLOAD_DIR = "gateway_payloads"
    private const val PAYLOAD_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /**
     * Writes [payloadJson] to storage/app/gateway_payloads/<uuid>.json
     * atomically (.tmp, then rename) and returns the file name. The uuid is
     * name-based on (command, payload), so a retry of the same report reuses
     * -- and atomically rewrites -- the same file. The host command deletes
     * the file after reading it; Kotlin never deletes it, so a call detected
     * as failed leaves it in place. Files older than 7 days are pruned here.
     */
    private fun writePayloadFile(context: Context, command: String, payloadJson: String): String {
        val dir = File(gatewayAppStorageDir(context.applicationContext), PAYLOAD_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")

        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && now - f.lastModified() > PAYLOAD_MAX_AGE_MILLIS) f.delete()
        }

        val uuid = UUID.nameUUIDFromBytes("$command\n$payloadJson".toByteArray(Charsets.UTF_8))
        val name = "$uuid.json"
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(payloadJson.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot rename payload file into place")
        }
        return name
    }

    /**
     * The file that boots the ephemeral runtime: the app's own bootstrap/android/ephemeral.php when it ships one, else
     * NativePHP's bootstrap/android/persistent.php (present in v3 and v4; it loads the autoloader, boots Laravel and calls
     * Runtime::boot, which is all the commands need). Never boots from a missing file: the runtime would be marked
     * initialized with no autoloader, and every command would fail until the process dies.
     */
    private fun bootstrapScript(laravelPath: String): String? {
        val candidates = listOf(
            "$laravelPath/bootstrap/android/ephemeral.php",
            "$laravelPath/vendor/nativephp/mobile/bootstrap/android/persistent.php",
        )
        return candidates.firstOrNull { java.io.File(it).isFile }
    }
}
