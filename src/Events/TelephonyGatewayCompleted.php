<?php

namespace Blutrixx\NativephpTelephonyGateway\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class TelephonyGatewayCompleted
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $result,
        public ?string $id = null
    ) {}
}