/**
 * TelephonyGateway Plugin for NativePHP Mobile
 *
 * @example
 * import { telephonyGateway } from '@blutrixx/nativephp-telephony-gateway';
 *
 * const sims = await telephonyGateway.listSims();
 * await telephonyGateway.startService();
 */

const baseUrl = '/_native/api/call';

/**
 * Internal bridge call function
 * @private
 */
async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]')?.content || ''
        },
        body: JSON.stringify({ method, params })
    });

    const result = await response.json();

    if (result.status === 'error') {
        throw new Error(result.message || 'Native call failed');
    }

    const nativeResponse = result.data;
    if (nativeResponse && nativeResponse.data !== undefined) {
        return nativeResponse.data;
    }

    return nativeResponse;
}

/** Starts the persistent foreground service (SMS receiver anchor, CallLog observer, poll loop). */
export async function startService() {
    return bridgeCall('TelephonyGateway.StartService');
}

export async function stopService() {
    return bridgeCall('TelephonyGateway.StopService');
}

/**
 * Send an SMS. Fire-and-forget -- the outcome arrives later via the backend's own
 * message status, not this call's return value.
 */
export async function sendSms(to, body, clientRef) {
    return bridgeCall('TelephonyGateway.SendSms', { to, body, client_ref: clientRef });
}

/** Dial a USSD code. The response arrives later, asynchronously. */
export async function sendUssd(code, requestId) {
    return bridgeCall('TelephonyGateway.SendUssd', { code, request_id: requestId });
}

/** Enumerate active SIM slots: [{slot, subscription_id, phone_number, operator, is_present}]. */
export async function listSims() {
    return bridgeCall('TelephonyGateway.ListSims');
}

/**
 * TelephonyGateway namespace object
 */
export const telephonyGateway = {
    startService,
    stopService,
    sendSms,
    sendUssd,
    listSims
};

export default telephonyGateway;
