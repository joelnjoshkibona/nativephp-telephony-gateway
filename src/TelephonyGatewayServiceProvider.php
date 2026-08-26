<?php

namespace Blutrixx\NativephpTelephonyGateway;

use Illuminate\Support\ServiceProvider;
use Blutrixx\NativephpTelephonyGateway\Commands\CopyAssetsCommand;

class TelephonyGatewayServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(TelephonyGateway::class, function () {
            return new TelephonyGateway();
        });
    }

    public function boot(): void
    {
        // Register plugin hook commands
        if ($this->app->runningInConsole()) {
            $this->commands([
                CopyAssetsCommand::class,
            ]);
        }
    }
}