package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device commands that need native code. Contract with the host app:
 *
 *  - After each `telephony:heartbeat` run, the host may have written
 *    storage/app/gateway_native_commands.json (next to gateway_poll_result.json):
 *    `[{"id": "<uuid>", "command": "restart_service"|"ring"|"check_balance", "params": {...}}]`.
 *  - The file is renamed to `.taken` BEFORE it is read, and deleted before any
 *    command runs; a `.taken` left by a crash is discarded unrun, so a command
 *    runs at most once.
 *  - Every command with an id is reported once via
 *    `telephony:command-result --payload=<base64 {"id","ok","error"}>`.
 *
 * Runs on the EphemeralDispatch thread (called from the heartbeat tick).
 */
object NativeCommands {
    private const val TAG = "TelephonyGateway.Commands"
    private const val FILE_NAME = "gateway_native_commands.json"
    private const val RING_MILLIS = 10_000L

    fun takeAndRun(service: TelephonyGatewayService) {
        val dir = gatewayAppStorageDir(service.applicationContext)
        val file = File(dir, FILE_NAME)
        val taken = File(dir, "$FILE_NAME.taken")

        if (taken.exists()) {
            Log.w(TAG, "discarding a .taken command file left by an interrupted run (not re-running it)")
            taken.delete()
        }
        if (!file.exists()) return
        if (!file.renameTo(taken)) {
            Log.e(TAG, "could not claim $FILE_NAME -- leaving it for the next tick")
            return
        }

        val text = try {
            taken.readText()
        } catch (e: Throwable) {
            Log.e(TAG, "could not read claimed command file", e)
            null
        } finally {
            taken.delete()
        }
        if (text.isNullOrBlank()) return

        val commands = try {
            JSONArray(text)
        } catch (e: Throwable) {
            Log.e(TAG, "command file is not a JSON array -- dropped", e)
            return
        }

        for (i in 0 until commands.length()) {
            val item = commands.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) {
                Log.w(TAG, "command without id at index $i -- skipped (cannot be reported)")
                continue
            }
            val outcome = try {
                execute(service, id, item.optString("command"), item.optJSONObject("params") ?: JSONObject())
            } catch (e: Throwable) {
                Log.e(TAG, "command $id threw", e)
                Outcome(false, e.message ?: e.javaClass.simpleName)
            }
            report(service.applicationContext, id, outcome)
        }
    }

    private data class Outcome(val ok: Boolean, val error: String?)

    private fun execute(service: TelephonyGatewayService, id: String, command: String, params: JSONObject): Outcome {
        Log.i(TAG, "executing command $id: $command")
        return when (command) {
            "restart_service" -> restartService(service)
            "ring" -> ring(service.applicationContext)
            "check_balance" -> checkBalance(service.applicationContext, id, params)
            else -> Outcome(false, "unsupported")
        }
    }

    private fun restartService(service: TelephonyGatewayService): Outcome {
        val latch = CountDownLatch(1)
        var ok = false
        service.softRestart { result ->
            ok = result
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) return Outcome(false, "restart did not complete within 5 s")
        return if (ok) Outcome(true, null) else Outcome(false, "service is not running")
    }

    /** Default alarm sound (falling back to ringtone, then notification) on the alarm stream for ~10 s. */
    private fun ring(context: Context): Outcome {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return Outcome(false, "AudioManager unavailable")
        if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            return Outcome(false, "alarm volume is muted")
        }

        val uris = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
            .mapNotNull { RingtoneManager.getDefaultUri(it) }
        var lastError: String? = "no default alarm, ringtone or notification sound is set"
        for (uri in uris) {
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(context, uri)
                player.isLooping = true
                player.prepare()
                player.start()
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        player.stop()
                    } catch (_: Throwable) {
                    }
                    player.release()
                }, RING_MILLIS)
                return Outcome(true, null)
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                player.release()
            }
        }
        return Outcome(false, "could not play a sound: $lastError")
    }

    /**
     * For each WATCHED SIM with a code in params.codes (keyed by subscription
     * id as a string), run that USSD code on that SIM; each response/failure is
     * reported through the normal telephony:ussd-result path with request_id
     * "balance-<command id>-<subscription_id>". Sessions run one after another
     * (one USSD session per modem at a time). ok = at least one was started.
     */
    private fun checkBalance(context: Context, id: String, params: JSONObject): Outcome {
        val codes = params.optJSONObject("codes") ?: return Outcome(false, "params.codes is missing")
        val targets = GatewayPrefs.watchedSims(context).sorted().mapNotNull { subId ->
            val code = codes.optString(subId.toString(), "").trim()
            if (code.isEmpty()) null else subId to code
        }
        if (targets.isEmpty()) return Outcome(false, "no watched SIM has a balance code")

        fun runFrom(index: Int) {
            if (index >= targets.size) return
            val (subId, code) = targets[index]
            TelephonyGatewayFunctions.sendUssdDirect(
                context, code, "balance-$id-$subId", subId,
                SimSlots.slotForSubscriptionId(context, subId),
                onComplete = { runFrom(index + 1) }
            )
        }
        Handler(Looper.getMainLooper()).post { runFrom(0) }
        return Outcome(true, null)
    }

    private fun report(context: Context, id: String, outcome: Outcome) {
        val payload = JSONObject().apply {
            put("id", id)
            put("ok", outcome.ok)
            put("error", outcome.error ?: JSONObject.NULL)
        }
        val result = EphemeralArtisan.runOnDispatchThread(context, "telephony:command-result", payload)
        Log.i(TAG, "command $id ok=${outcome.ok} reported=${result.ok}")
    }
}
