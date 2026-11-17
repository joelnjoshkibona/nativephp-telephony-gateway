package com.blutrixx.plugins.nativephp_telephony_gateway

/**
 * Folds per-part SmsManager callbacks (one sentIntent / deliveredIntent per
 * part of a multipart SMS) into ONE outcome per message per event. Pure --
 * no android.* imports -- so it can be checked on a plain JVM; SmsPartStore
 * persists the encoded State between callbacks (delivery reports can arrive
 * long after the process that sent the message died).
 *
 * Rules:
 *  - the outcome is emitted exactly once, when every part [0, total) has
 *    reported; duplicate or out-of-range part reports are ignored;
 *  - it is a failure if any part failed, carrying the FIRST failing part's
 *    index, result code and (delivery) status code;
 *  - on success it carries the last reported part's codes.
 */
object PartAggregator {

    data class State(
        val total: Int,
        val reported: Set<Int>,
        val failedPart: Int?,
        val failedResultCode: Int?,
        val failedStatusCode: Int?,
        val lastResultCode: Int?,
        val lastStatusCode: Int?,
        val createdAtMillis: Long,
    )

    data class Outcome(
        val ok: Boolean,
        val parts: Int,
        val failedPart: Int?,
        val resultCode: Int?,
        val statusCode: Int?,
    )

    /** [state] is what to persist next (null = forget it); [outcome] is non-null once complete. */
    data class Step(val state: State?, val outcome: Outcome?)

    fun record(
        previous: State?,
        total: Int,
        part: Int,
        ok: Boolean,
        resultCode: Int?,
        statusCode: Int?,
        nowMillis: Long,
    ): Step {
        val safeTotal = if (total < 1) 1 else total
        val state = previous ?: State(safeTotal, emptySet(), null, null, null, null, null, nowMillis)

        if (part < 0 || part >= state.total || part in state.reported) {
            return Step(state, null)
        }

        val firstFailure = !ok && state.failedPart == null
        val next = state.copy(
            reported = state.reported + part,
            failedPart = if (firstFailure) part else state.failedPart,
            failedResultCode = if (firstFailure) resultCode else state.failedResultCode,
            failedStatusCode = if (firstFailure) statusCode else state.failedStatusCode,
            lastResultCode = resultCode,
            lastStatusCode = statusCode,
        )

        if (next.reported.size < next.total) {
            return Step(next, null)
        }

        val failed = next.failedPart != null
        return Step(
            null,
            Outcome(
                ok = !failed,
                parts = next.total,
                failedPart = next.failedPart,
                resultCode = if (failed) next.failedResultCode else next.lastResultCode,
                statusCode = if (failed) next.failedStatusCode else next.lastStatusCode,
            )
        )
    }

    private const val VERSION = "v1"

    fun encode(s: State): String = listOf(
        VERSION,
        s.total.toString(),
        s.createdAtMillis.toString(),
        s.reported.sorted().joinToString(","),
        s.failedPart?.toString() ?: "",
        s.failedResultCode?.toString() ?: "",
        s.failedStatusCode?.toString() ?: "",
        s.lastResultCode?.toString() ?: "",
        s.lastStatusCode?.toString() ?: "",
    ).joinToString("|")

    /** Null for anything malformed -- a corrupt entry restarts aggregation rather than crashing. */
    fun decode(raw: String?): State? {
        if (raw == null) return null
        val f = raw.split("|")
        if (f.size != 9 || f[0] != VERSION) return null
        val total = f[1].toIntOrNull() ?: return null
        val createdAt = f[2].toLongOrNull() ?: return null
        val reported = if (f[3].isEmpty()) emptySet() else f[3].split(",").map { it.toIntOrNull() ?: return null }.toSet()
        return State(
            total = total,
            reported = reported,
            failedPart = f[4].toIntOrNull(),
            failedResultCode = f[5].toIntOrNull(),
            failedStatusCode = f[6].toIntOrNull(),
            lastResultCode = f[7].toIntOrNull(),
            lastStatusCode = f[8].toIntOrNull(),
            createdAtMillis = createdAt,
        )
    }
}
