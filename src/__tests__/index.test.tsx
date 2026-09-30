jest.mock('react-native', () => {
  const ReactNative = jest.requireActual('react-native');
  return {
    Platform: ReactNative.Platform,
    NativeEventEmitter: ReactNative.NativeEventEmitter,
    DeviceEventEmitter: ReactNative.DeviceEventEmitter,
    NativeModules: {
      ...ReactNative.NativeModules,
      ReactNativeMultiBlePeripheral: {
        addListener: jest.fn(),
        removeListeners: jest.fn(),
        createCentral: jest.fn(() => Promise.resolve()),
        startDeviceScan: jest.fn(() => Promise.resolve()),
        stopDeviceScan: jest.fn(() => Promise.resolve()),
        connectToDevice: jest.fn(() =>
          Promise.resolve({
            id: 'AA:BB',
            name: 'Test device',
            rssi: -40,
            manufacturerData: null,
            serviceUUIDs: [],
          })
        ),
        cancelDeviceConnection: jest.fn(() => Promise.resolve()),
        setLogLevel: jest.fn(() => Promise.resolve()),
        destroyCentral: jest.fn(() => Promise.resolve()),
      },
    },
  };
});

import { DeviceEventEmitter, NativeModules } from 'react-native';
import { Central, LogLevel, ScanCallbackType, ScanMode } from '../index';

const native = NativeModules.ReactNativeMultiBlePeripheral;

describe('Central', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('starts a scan and returns discovered devices to the listener', async () => {
    const central = new Central();
    const listener = jest.fn();

    await central.startDeviceScan(
      null,
      {
        allowDuplicates: true,
        scanMode: ScanMode.LowLatency,
        callbackType: ScanCallbackType.AllMatches,
      },
      listener
    );
    DeviceEventEmitter.emit('onDeviceFound', {
      id: 0,
      device: {
        id: 'AA:BB',
        name: 'Test device',
        rssi: -40,
        manufacturerData: null,
        serviceUUIDs: [],
      },
    });

    expect(native.startDeviceScan).toHaveBeenCalledWith(0, null, {
      allowDuplicates: true,
      scanMode: ScanMode.LowLatency,
      callbackType: ScanCallbackType.AllMatches,
    });
    expect(listener).toHaveBeenCalledWith(
      null,
      expect.objectContaining({ id: 'AA:BB', name: 'Test device' })
    );

    await central.stopDeviceScan();
    await central.destroy();
    expect(native.destroyCentral).toHaveBeenCalledWith(0);
  });

  it('connects and returns a Device wrapper', async () => {
    const central = new Central();
    const device = await central.connectToDevice('AA:BB', {
      autoConnect: true,
    });

    expect(native.connectToDevice).toHaveBeenCalledWith(1, 'AA:BB', {
      autoConnect: true,
    });
    expect(device.id).toBe('AA:BB');
    await central.setLogLevel(LogLevel.Verbose);
    expect(native.setLogLevel).toHaveBeenCalledWith(LogLevel.Verbose);
    await central.destroy();
  });
});
