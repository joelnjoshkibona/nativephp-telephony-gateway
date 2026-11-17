package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.content.SharedPreferences

/**
 * The plugin's own small persisted state, all in the same SharedPreferences
 * file TelephonyGatewayService already uses for the missed-call high-water
 * mark. Writes use commit() (synchronous) because a receiver in the same
 * process may read the value immediately afterwards.
 */
object GatewayPrefs {
    const val PREFS_NAME = "telephony_gateway"
    private const val KEY_WATCHED = "watched_subscription_ids"
    private const val KEY_SHOULD_RUN = "service_should_run"

    private const val PARTS_PREFS_NAME = "telephony_gateway_sms_parts"
    private const val PARTS_MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- watched SIMs ------------------------------------------------------

    /** Empty when unset -- which means NOTHING is watched. */
    fun watchedSims(context: Context): Set<Int> =
        (prefs(context).getString(KEY_WATCHED, null) ?: "")
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it >= 0 }
            .toSet()

    fun setWatchedSims(context: Context, subscriptionIds: Set<Int>) {
        prefs(context).edit()
            .putString(KEY_WATCHED, subscriptionIds.filter { it >= 0 }.sorted().joinToString(","))
            .commit()
    }

    // --- start-on-boot flag -------------------------------------------------

    fun shouldRun(context: Context): Boolean = prefs(context).getBoolean(KEY_SHOULD_RUN, false)

    fun setShouldRun(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOULD_RUN, value).commit()
    }

    // --- multipart SMS aggregation state -------------------------------------

    /**
     * Applies one part callback to the persisted aggregate for (event, message
     * attempt) and returns the completed outcome, if this was the last part.
     * Call ONLY from the EphemeralDispatch thread -- that single thread is what
     * makes this read-modify-write race-free.
     */
    fun recordSmsPart(
        context: Context,
        key: String,
        total: Int,
        part: Int,
        ok: Boolean,
        resultCode: Int?,
        statusCode: Int?,
    ): PartAggregator.Outcome? {
        val store = context.applicationContext.getSharedPreferences(PARTS_PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val step = PartAggregator.record(
            PartAggregator.decode(store.getString(key, null)),
            total, part, ok, resultCode, statusCode, now
        )

        val editor = store.edit()
        if (step.state == null) editor.remove(key) else editor.putString(key, PartAggregator.encode(step.state))

        // Delivery reports are optional on many carriers -- prune aggregates
        // that will never complete so this file cannot grow without bound.
        for ((otherKey, value) in store.all) {
            if (otherKey == key) continue
            val created = PartAggregator.decode(value as? String)?.createdAtMillis
            if (created == null || now - created > PARTS_MAX_AGE_MILLIS) editor.remove(otherKey)
        }
        editor.commit()
        return step.outcome
    }

    fun forgetSmsParts(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PARTS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(key).commit()
    }
}
