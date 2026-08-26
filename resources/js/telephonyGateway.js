/**
 * TelephonyGateway Plugin for NativePHP Mobile
 *
 * @example
 * import { telephonyGateway } from '@blutrixx/nativephp-telephony-gateway';
 *
 * // Execute functionality
 * const result = await telephonyGateway.execute({ option1: 'value' });
 *
 * // Get status
 * const status = await telephonyGateway.getStatus();
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

/**
 * Execute the plugin functionality
 * @param {Object} options - Options to pass to the native function
 * @returns {Promise<any>}
 */
export async function execute(options = {}) {
    return bridgeCall('TelephonyGateway.Execute', options);
}

/**
 * Get the current status
 * @returns {Promise<Object>}
 */
export async function getStatus() {
    return bridgeCall('TelephonyGateway.GetStatus');
}

/**
 * TelephonyGateway namespace object
 */
export const telephonyGateway = {
    execute,
    getStatus
};

export default telephonyGateway;