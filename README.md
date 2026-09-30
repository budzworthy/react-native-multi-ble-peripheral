# react-native-multi-ble-peripheral

React Native BLE peripheral and central manager for iOS and Android. The library provides both GATT server/advertising and scanning/GATT client APIs without a separate BLE stack dependency.

## Installation

```sh
npm install @budzworthy/react-native-multi-ble-peripheral
```

For iOS, install CocoaPods dependencies with `pod install`.

## Permissions

### iOS

Add a Bluetooth usage description to the app's `Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Bluetooth is used to discover and communicate with nearby devices.</string>
```

### Android

Add the following permissions to the app manifest. The library manifest declares these permissions for manifest merging as well; apps must still request the runtime permissions before using Bluetooth on Android 12 and later.

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
```

Request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` before central operations and `BLUETOOTH_ADVERTISE` before peripheral operations on Android 12+. For Android 11 and earlier, request location permission before scanning.

## Central role: scan and connect

```ts
import {
  Central,
  LogLevel,
  ScanCallbackType,
  ScanMode,
} from '@budzworthy/react-native-multi-ble-peripheral';

const central = new Central();
central.setLogLevel(LogLevel.Verbose);

await central.startDeviceScan(
  ['0000180d-0000-1000-8000-00805f9b34fb'],
  {
    allowDuplicates: false,
    scanMode: ScanMode.LowLatency,
    callbackType: ScanCallbackType.AllMatches,
  },
  (error, device) => {
    if (error) {
      console.warn(error);
      return;
    }
    if (device) {
      console.log(device.id, device.name, device.rssi, device.serviceUUIDs);
    }
  }
);

await central.stopDeviceScan();
const device = await central.connectToDevice(deviceId, {
  autoConnect: true,
  timeout: 10000,
});
await device.discoverAllServicesAndCharacteristics();
const services = await device.services();
const characteristics = await device.characteristicsForService(services[0].uuid);
```

`Device` exposes `id`, `name`, `rssi`, `manufacturerData` and `serviceUUIDs`. Characteristic values are base64 strings:

```ts
const serviceUUID = services[0].uuid;
const characteristicUUID = characteristics[0].uuid;

const value = await central.readCharacteristicForDevice(
  device.id,
  serviceUUID,
  characteristicUUID
);

await device.writeCharacteristicWithoutResponseForService(
  serviceUUID,
  characteristicUUID,
  base64Value
);

const subscription = device.monitorCharacteristic(
  serviceUUID,
  characteristicUUID,
  (error, characteristic) => {
    if (!error && characteristic) console.log(characteristic.value);
  }
);

const rssi = await device.readRSSI();
subscription.remove();
await central.cancelDeviceConnection(device.id);
await central.destroy();
```

The manager also emits `connect`, `disconnect`, and `error` events. `monitorCharacteristicForDevice(deviceId, serviceUUID, characteristicUUID, listener)` is available on `Central` as well as `device.monitorCharacteristic(...)`.

## Peripheral role: advertise a GATT service

```ts
import Peripheral, { Permission, Property } from '@budzworthy/react-native-multi-ble-peripheral';
import { Buffer } from 'buffer';

const peripheral = new Peripheral();
peripheral.on('ready', async () => {
  await peripheral.addService('1234', true);
  await peripheral.addCharacteristic(
    '1234',
    'abcd',
    Property.READ | Property.WRITE | Property.NOTIFY,
    Permission.READABLE | Permission.WRITEABLE
  );
  await peripheral.updateValue('1234', 'abcd', Buffer.from('Hello World!'));
  await peripheral.startAdvertising();
});
```

Peripheral events include `write`, `subscribe`, `unsubscribe`, `ready`, `error` and `destroy`. `Peripheral.setDeviceName(name)` sets the local advertising name.

## API enums

`Central` exports/uses `ScanMode` (`LowPower`, `Balanced`, `LowLatency`), `ScanCallbackType` (`AllMatches`, `FirstMatch`, `MatchLost`) and `LogLevel` (`Verbose`, `Debug`, `Info`, `Warning`, `Error`, `None`). Peripheral exports `Permission`, `Property`, `TxPowerLevel` and `AdvertiseMode`.

## Contributing

See the [contributing guide](CONTRIBUTING.md) to learn about the development workflow.

## License

MIT
