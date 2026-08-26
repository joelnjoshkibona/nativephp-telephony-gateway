<?php

namespace Blutrixx\NativephpTelephonyGateway;

class TelephonyGateway
{
    /** Starts the persistent foreground service (SMS receiver anchor, CallLog observer). */
    public function startService(): ?object
    {
        return $this->call('TelephonyGateway.StartService', []);
    }

    public function stopService(): ?object
    {
        return $this->call('TelephonyGateway.StopService', []);
    }

    /**
     * Send an SMS. Fire-and-forget from PHP's side -- the outcome arrives later via
     * telephony:sms-result (SmsResultReceiver -> ephemeral bridge), keyed by the
     * client_ref this returns.
     */
    public function sendSms(string $to, string $body, ?string $clientRef = null): ?object
    {
        return $this->call('TelephonyGateway.SendSms', [
            'to' => $to,
            'body' => $body,
            'client_ref' => $clientRef ?? (string) \Illuminate\Support\Str::uuid(),
        ]);
    }

    /** Dial a USSD code. The response arrives later via telephony:ussd-result. */
    public function sendUssd(string $code, ?string $requestId = null): ?object
    {
        return $this->call('TelephonyGateway.SendUssd', [
            'code' => $code,
            'request_id' => $requestId ?? (string) \Illuminate\Support\Str::uuid(),
        ]);
    }

    protected function call(string $function, array $params): ?object
    {
        if (! function_exists('nativephp_call')) {
            return null;
        }

        $result = nativephp_call($function, json_encode($params));

        return $result ? (json_decode($result)->data ?? null) : null;
    }
}
