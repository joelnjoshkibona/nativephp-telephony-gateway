package com.blutrixx.plugins.nativephp_telephony_gateway

import java.security.MessageDigest

/**
 * Pure decision logic -- NO android.* imports, on purpose. Everything here is
 * plain Kotlin/JVM so it can be exercised without a device or an Android
 * runtime; the Android-facing classes only gather inputs and act on these
 * answers.
 */
object GatewayRules {

    /** android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID */
    const val INVALID_SUBSCRIPTION_ID = -1

    // ---------------------------------------------------------------------
    // Missed calls: only calls that happened while the gateway was on duty
    // ---------------------------------------------------------------------

    /** A call this long before the gateway started reporting is still reported (it rang just before). */
    const val MISSED_CALL_GRACE_MS = 10 * 60 * 1000L

    /**
     * True when a missed call is part of the phone's old history rather than a call to report: it rang more than
     * [MISSED_CALL_GRACE_MS] before `reportingSince` (epoch ms, when this install started reporting calls). Such a
     * call is skipped on the phone, never uploaded. `reportingSince` <= 0 means not known yet: report.
     */
    fun isOldCallHistory(callDateMs: Long, reportingSince: Long): Boolean =
        reportingSince > 0 && callDateMs < reportingSince - MISSED_CALL_GRACE_MS

    // ---------------------------------------------------------------------
    // Watched SIMs (privacy filter)
    // ---------------------------------------------------------------------

    /**
     * True only for a known, valid subscription id that is explicitly in the
     * watched set. Unknown SIM (null) and an empty/unset watched set both
     * mean "not watched" -- the safe default.
     */
    fun isWatched(subscriptionId: Int?, watched: Set<Int>): Boolean =
        subscriptionId != null && subscriptionId >= 0 && subscriptionId in watched

    /**
     * Receiving subscription of an SMS_RECEIVED intent, from its two extras:
     * the legacy `subscription` key and `android.telephony.extra.SUBSCRIPTION_INDEX`.
     * Returns null (= drop) when neither carries a valid id, or when both do
     * and they disagree -- never guess between them.
     */
    fun resolveSmsSubscription(legacyExtra: Int?, subscriptionIndexExtra: Int?): Int? {
        val candidates = listOfNotNull(legacyExtra, subscriptionIndexExtra).filter { it >= 0 }.toSet()
        return candidates.singleOrNull()
    }

    /**
     * Subscription a CallLog row belongs to, from its PHONE_ACCOUNT_ID.
     *
     * @param telecomMapped subscription ids that Telecom itself maps this
     *   exact PhoneAccountHandle id to (TelephonyManager.getSubscriptionId(handle), API 30+).
     * @param active (subscriptionId, iccId) of every active SIM. PHONE_ACCOUNT_ID
     *   is the subscription id on current AOSP and the ICCID on older releases.
     *
     * Exactly one distinct match wins; zero or several distinct matches return
     * null (= drop the call, do not guess).
     */
    fun matchPhoneAccount(
        phoneAccountId: String?,
        telecomMapped: Collection<Int>,
        active: List<Pair<Int, String?>>,
    ): Int? {
        val id = phoneAccountId?.trim()
        if (id.isNullOrEmpty()) return null

        val matches = mutableSetOf<Int>()
        telecomMapped.filter { it >= 0 }.forEach { matches.add(it) }
        for ((subId, iccId) in active) {
            if (subId < 0) continue
            if (id == subId.toString()) matches.add(subId)
            if (!iccId.isNullOrBlank() && id.equals(iccId.trim(), ignoreCase = true)) matches.add(subId)
        }
        return matches.singleOrNull()
    }

    // ---------------------------------------------------------------------
    // Inbound SMS uid
    // ---------------------------------------------------------------------

    /**
     * Lowercase sha256 hex of UTF-8("<subscription_id>\u001F<from>\u001F<body>\u001F<timestamp_ms>").
     * U+001F (unit separator) cannot be confused with content boundaries in
     * practice the way plain concatenation could ("12"+"3" == "1"+"23").
     */
    fun inboundSmsUid(subscriptionId: Int, from: String, body: String, timestampMillis: Long): String {
        val input = listOf(subscriptionId.toString(), from, body, timestampMillis.toString()).joinToString("\u001F")
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ---------------------------------------------------------------------
    // Ephemeral artisan outcome
    // ---------------------------------------------------------------------

    /**
     * NativePHP's eval wrapper (php_bridge.c, native_ephemeral_artisan) is a
     * fixed `char eval_code[4096]` filled via snprintf -- a longer command is
     * silently truncated into broken PHP. ~130 bytes go to the wrapper itself.
     */
    const val MAX_EPHEMERAL_COMMAND_BYTES = 3900

    /**
     * Runtime::artisan() (nativephp/mobile 3.x and 4.x alike) discards
     * Artisan::call()'s exit code and returns only the buffered output; the C
     * wrapper turns an escaping Throwable into the text below. These two
     * strings are therefore the ONLY failure signals that call can carry.
     */
    fun artisanOutputSignalsFailure(output: String?): Boolean {
        if (output == null) return false
        return output.contains("Ephemeral artisan error:") ||
            output.trim() == "Ephemeral runtime not initialized."
    }

    // ---------------------------------------------------------------------
    // SMS delivery-report status
    // ---------------------------------------------------------------------

    enum class DeliveryKind { SUCCESS, PENDING, FAILED }

    /**
     * Classifies SmsMessage.getStatus() of a delivery report.
     *
     * 3GPP (GSM/UMTS/LTE), TS 23.040 TP-Status: 0x00-0x1F completed,
     * 0x20-0x3F temporary error with the SC still trying (a further report
     * follows), 0x40-0x5F permanent error, 0x60-0x7F temporary error with the
     * SC no longer trying -- both of the last two are final failures.
     *
     * 3GPP2 (CDMA): the framework returns (errorClass << 8 | messageStatus) << 16,
     * so errorClass is bits 24-25: 0 none, 2 temporary, 3 permanent.
     */
    fun classifyDeliveryStatus(format: String?, status: Int): DeliveryKind {
        if (format == "3gpp2") {
            return when ((status ushr 24) and 0x03) {
                0 -> DeliveryKind.SUCCESS
                2 -> DeliveryKind.PENDING
                else -> DeliveryKind.FAILED
            }
        }
        val tpStatus = status and 0xFF
        return when {
            tpStatus < 0x20 -> DeliveryKind.SUCCESS
            tpStatus < 0x40 -> DeliveryKind.PENDING
            else -> DeliveryKind.FAILED
        }
    }
}
