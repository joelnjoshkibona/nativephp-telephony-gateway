package com.blutrixx.plugins.nativephp_telephony_gateway

import android.content.Context
import android.telephony.SubscriptionManager
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
}
