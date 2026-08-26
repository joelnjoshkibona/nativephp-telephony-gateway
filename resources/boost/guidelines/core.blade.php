## blutrixx/nativephp-telephony-gateway

A NativePHP Mobile plugin

### Installation

```bash
composer require blutrixx/nativephp-telephony-gateway
```

### PHP Usage (Livewire/Blade)

Use the `TelephonyGateway` facade:

@verbatim
<code-snippet name="Using TelephonyGateway Facade" lang="php">
use Blutrixx\NativephpTelephonyGateway\Facades\TelephonyGateway;

// Execute the plugin functionality
$result = TelephonyGateway::execute(['option1' => 'value']);

// Get the current status
$status = TelephonyGateway::getStatus();
</code-snippet>
@endverbatim

### Available Methods

- `TelephonyGateway::execute()`: Execute the plugin functionality
- `TelephonyGateway::getStatus()`: Get the current status

### Events

- `TelephonyGatewayCompleted`: Listen with `#[OnNative(TelephonyGatewayCompleted::class)]`

@verbatim
<code-snippet name="Listening for TelephonyGateway Events" lang="php">
use Native\Mobile\Attributes\OnNative;
use Blutrixx\NativephpTelephonyGateway\Events\TelephonyGatewayCompleted;

#[OnNative(TelephonyGatewayCompleted::class)]
public function handleTelephonyGatewayCompleted($result, $id = null)
{
    // Handle the event
}
</code-snippet>
@endverbatim

### JavaScript Usage (Vue/React/Inertia)

@verbatim
<code-snippet name="Using TelephonyGateway in JavaScript" lang="javascript">
import { telephonyGateway } from '@blutrixx/nativephp-telephony-gateway';

// Execute the plugin functionality
const result = await telephonyGateway.execute({ option1: 'value' });

// Get the current status
const status = await telephonyGateway.getStatus();
</code-snippet>
@endverbatim