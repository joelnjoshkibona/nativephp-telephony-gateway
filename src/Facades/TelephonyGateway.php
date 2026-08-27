<?php

namespace Blutrixx\NativephpTelephonyGateway\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static object|null startService()
 * @method static object|null stopService()
 * @method static object|null sendSms(string $to, string $body, ?string $clientRef = null)
 * @method static object|null sendUssd(string $code, ?string $requestId = null)
 * @method static object|null listSims()
 * @method static object|null checkBatteryOptimizationExemption()
 * @method static object|null requestBatteryOptimizationExemption()
 *
 * @see \Blutrixx\NativephpTelephonyGateway\TelephonyGateway
 */
class TelephonyGateway extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\NativephpTelephonyGateway\TelephonyGateway::class;
    }
}