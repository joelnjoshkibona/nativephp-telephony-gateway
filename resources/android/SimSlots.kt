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

    /** Every entry this returns is, by definition, a currently-present SIM. */
    fun listActive(context: Context): List<Info> {
        return try {
            val manager = SubscriptionManager.from(context)
            val infos = manager.activeSubscriptionInfoList ?: emptyList()
            infos.map {
                Info(
                    slot = it.simSlotIndex,
                    subscriptionId = it.subscriptionId,
                    phoneNumber = it.number?.takeIf { n -> n.isNotBlank() },
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
            SubscriptionManager.from(context)
                .getActiveSubscriptionInfo(subscriptionId)
                ?.simSlotIndex
        } catch (e: Throwable) {
            Log.w(TAG, "slotForSubscriptionId FAILED for subId=$subscriptionId", e)
            null
        }
    }
}
