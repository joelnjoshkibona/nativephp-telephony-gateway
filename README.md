# TelephonyGateway Plugin for NativePHP Mobile

A NativePHP Mobile plugin

## Installation

```bash
composer require blutrixx/nativephp-telephony-gateway
```

## Usage

```php
use Blutrixx\NativephpTelephonyGateway\Facades\TelephonyGateway;

// Execute functionality
$result = TelephonyGateway::execute(['option1' => 'value']);

// Get status
$status = TelephonyGateway::getStatus();
```

## Listening for Events

```php
use Livewire\Attributes\On;

#[On('native:Blutrixx\NativephpTelephonyGateway\Events\TelephonyGatewayCompleted')]
public function handleTelephonyGatewayCompleted($result, $id = null)
{
    // Handle the event
}
```

## License

MIT