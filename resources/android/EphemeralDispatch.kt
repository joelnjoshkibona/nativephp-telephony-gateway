package com.blutrixx.plugins.nativephp_telephony_gateway

import android.os.Handler
import android.os.HandlerThread

/**
 * Every nativeEphemeralBoot()/nativeEphemeralArtisan() call in this plugin must run on
 * the SAME OS thread for the life of the process. Confirmed by a real crash: a second
 * thread calling nativeEphemeralBoot() after a first thread already initialized it hits
 * the native side's "already initialized, skipping" short-circuit WITHOUT setting up
 * that thread's own TLS/TSRM binding -- native_ephemeral_artisan() then SIGSEGVs on
 * pthread_getspecific() for a key never set on that thread. This is a race, not a
 * deterministic failure: two ad-hoc threads dispatching missed-call events back to back
 * both survived, but the SMS sent+delivered pair (same pattern) crashed the process. So
 * per-callsite ad-hoc Thread{}.start() is not safe here even when it appears to work --
 * every dispatch must go through this single shared thread instead.
 */
object EphemeralDispatch {
    private val handlerThread = HandlerThread("telephony-gateway-ephemeral").apply { start() }
    private val handler = Handler(handlerThread.looper)

    fun post(block: () -> Unit) {
        handler.post(block)
    }
}
