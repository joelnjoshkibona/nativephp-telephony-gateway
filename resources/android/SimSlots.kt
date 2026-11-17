package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Shared SubscriptionManager access -- used both by ListSims (full slot
 * enumeration for enrollment) and SmsReceivedReceiver (resolving which slot
 * an inbound SMS arrived on from its subscription id extra). One place for
 * the READ_PHONE_STATE-gated try/catch, not two copies of it.
 */
object SimSlots {
    private const val TAG = "TelephonyGateway.SimSlots"

    data class Info(
        val slot: Int,
        val subscriptionId: Int,
        val phoneNumber: String?,
        val operator: String?,
    )

    /**
     * SubscriptionManager.from(context) is deprecated since API 26 in favor
     * of the typed getSystemService() overload -- behavior-identical, no
     * permission change, safe at this plugin's own minSdk 26 (the typed
     * overload has existed since API 23).
     */
    private fun subscriptionManager(context: Context): SubscriptionManager? =
        context.getSystemService(SubscriptionManager::class.java)

    /**
     * SubscriptionInfo.number is deprecated since API 33 in favor of
     * SubscriptionManager.getPhoneNumber(subId) -- but that replacement needs
     * API 33+ (this plugin's minSdk is 26) AND a new READ_PHONE_NUMBERS
     * permission neither this plugin nor any consuming app currently
     * requests. The field is already a best-effort, nullable, display-only
     * value (never used for routing/business logic -- see enrollment's own
     * handling), so adding a new privacy-sensitive permission just to
     * silence this warning isn't worth it. Suppression scoped to this one
     * property access, not the whole file, so any other deprecation here
     * still surfaces normally.
     */
    @Suppress("DEPRECATION")
    private fun phoneNumberOf(info: android.telephony.SubscriptionInfo): String? =
        info.number?.takeIf { it.isNotBlank() }

    /** Every entry this returns is, by definition, a currently-present SIM. */
    fun listActive(context: Context): List<Info> {
        return try {
            val manager = subscriptionManager(context) ?: return emptyList()
            val infos = manager.activeSubscriptionInfoList ?: emptyList()
            infos.map {
                Info(
                    slot = it.simSlotIndex,
                    subscriptionId = it.subscriptionId,
                    phoneNumber = phoneNumberOf(it),
                    operator = it.carrierName?.toString(),
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "listActive: READ_PHONE_STATE not granted", e)
            emptyList()
        } catch (e: Throwable) {
            Log.e(TAG, "listActive FAILED", e)
            emptyList()
        }
    }

    fun slotForSubscriptionId(context: Context, subscriptionId: Int): Int? {
        if (subscriptionId < 0) return null

        return try {
            subscriptionManager(context)
                ?.getActiveSubscriptionInfo(subscriptionId)
                ?.simSlotIndex
        } catch (e: Throwable) {
            Log.w(TAG, "slotForSubscriptionId FAILED for subId=$subscriptionId", e)
            null
        }
    }

    /**
     * Snapshot for mapping CallLog's PHONE_ACCOUNT_ID to a subscription id --
     * built once per CallLog scan, then [GatewayRules.matchPhoneAccount]
     * decides (exactly one match, or null = drop).
     */
    class PhoneAccountResolver internal constructor(
        private val telecomMap: Map<String, Set<Int>>,
        private val active: List<Pair<Int, String?>>,
    ) {
        fun subscriptionFor(phoneAccountId: String?): Int? =
            GatewayRules.matchPhoneAccount(
                phoneAccountId,
                phoneAccountId?.trim()?.let { telecomMap[it] } ?: emptySet(),
                active,
            )
    }

    fun phoneAccountResolver(context: Context): PhoneAccountResolver {
        val telecomMap = mutableMapOf<String, MutableSet<Int>>()
        // TelephonyManager.getSubscriptionId(PhoneAccountHandle) is API 30+;
        // below that only the direct subscription-id / ICCID comparison applies.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                val telephony = context.getSystemService(TelephonyManager::class.java)
                if (telecom != null && telephony != null) {
                    for (handle in telecom.callCapablePhoneAccounts) {
                        val subId = telephony.getSubscriptionId(handle)
                        if (subId >= 0) telecomMap.getOrPut(handle.id) { mutableSetOf() }.add(subId)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "phoneAccountResolver: telecom mapping unavailable", e)
            }
        }

        val active = try {
            subscriptionManager(context)?.activeSubscriptionInfoList.orEmpty().map {
                it.subscriptionId to iccIdOf(it)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "phoneAccountResolver: active subscriptions unavailable", e)
            emptyList()
        }
        return PhoneAccountResolver(telecomMap, active)
    }

    /** Empty for non-privileged apps on API 29+; only useful on older releases where PHONE_ACCOUNT_ID was the ICCID. */
    private fun iccIdOf(info: android.telephony.SubscriptionInfo): String? =
        try {
            info.iccId?.takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            null
        }
}
