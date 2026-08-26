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

    /** Enumerate active SIM slots for enrollment and the poll loop's own claim payload. */
    public function listSims(): ?object
    {
        return $this->call('TelephonyGateway.ListSims', []);
    }

    /**
     * nativephp_call() returns the bridge function's own map serialized flat
     * (e.g. `{"sims": [...]}`, `{"launched": true}`) -- there is no `data`
     * envelope at this layer (confirmed live via BridgeJNI's own log output:
     * `Result JSON: {"sims":[{...}]}` for TelephonyGateway.ListSims). A
     * previous `json_decode($result)->data ?? null` here silently returned
     * null for every call through this method, including sendSms/sendUssd/
     * startService/stopService -- never noticed because nothing before
     * ListSims actually consumed the return value; each was only verified by
     * its side effect (SMS sent, service started), not its PHP return shape.
     */
    protected function call(string $function, array $params): ?object
    {
        if (! function_exists('nativephp_call')) {
            return null;
        }

        $result = nativephp_call($function, json_encode($params));

        return $result ? json_decode($result) : null;
    }
}
