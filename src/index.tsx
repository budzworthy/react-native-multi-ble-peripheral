import type { EventSubscription } from 'react-native';
import type { Buffer } from 'buffer';
import { NativeModules, Platform, NativeEventEmitter } from 'react-native';
import { EventEmitter } from 'eventemitter3';

const LINKING_ERROR =
  `The package '@fugood/react-native-multi-ble-peripheral' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const { ReactNativeMultiBlePeripheral: NativePeripheral } = NativeModules;

let nextId = 0;
const nativeEvents = NativePeripheral
  ? new NativeEventEmitter(NativePeripheral)
  : null;

export enum Permission {
  READABLE = 1,
  READ_ENCRYPTED = 2,
  WRITEABLE = 4,
  WRITE_ENCRYPTED = 8,
}

export enum Property {
  BROADCAST = 1,
  READ = 2,
  WRITE_NO_RESPONSE = 4,
  WRITE = 8,
  NOTIFY = 16,
  INDICATE = 32,
  SIGNED_WRITE = 64,
  EXTENDED_PROPS = 128,
}

export enum TxPowerLevel {
  ULTRA_LOW = 0,
  LOW = 1,
  MEDIUM = 2,
  HIGH = 3,
}

export enum AdvertiseMode {
  LOW_LATENCY = 0,
  LOW_POWER = 1,
  BALANCED = 2,
}

export interface AdvertiseServices {
  [key: string]: Buffer;
}

export interface AdvertiseOptions {
  mode?: AdvertiseMode;
  txPowerLevel?: TxPowerLevel;
  connectable?: boolean;
  includeDeviceName?: boolean;
  includeTxPowerLevel?: boolean;
  manufacturerId?: number;
  manufacturerData?: Buffer;
}

export interface WriteEvent {
  serviceUuid: string;
  characteristicUuid: string;
  value: string;
}

export interface SubscriptionEvent {
  serviceUuid: string;
  characteristicUuid: string;
}

export enum ScanMode {
  LowPower = 0,
  Balanced = 1,
  LowLatency = 2,
}

export enum ScanCallbackType {
  AllMatches = 1,
  FirstMatch = 2,
  MatchLost = 4,
}

export enum LogLevel {
  Verbose = 0,
  Debug = 1,
  Info = 2,
  Warning = 3,
  Error = 4,
  None = 5,
}

export interface ScanOptions {
  allowDuplicates?: boolean;
  scanMode?: ScanMode;
  callbackType?: ScanCallbackType;
}

export interface ConnectionOptions {
  autoConnect?: boolean;
  timeout?: number;
}

export interface DeviceInfo {
  id: string;
  name: string | null;
  rssi: number | null;
  manufacturerData: string | null;
  serviceUUIDs: string[];
}

export interface Service {
  uuid: string;
  isPrimary?: boolean;
}

export interface Characteristic {
  id: string;
  uuid: string;
  serviceUUID: string;
  value: string | null;
  isReadable: boolean;
  isWritableWithResponse: boolean;
  isWritableWithoutResponse: boolean;
  isNotifiable: boolean;
  isIndicatable: boolean;
}

export type ScanListener = (error: Error | null, device: Device | null) => void;

export type CharacteristicListener = (
  error: Error | null,
  characteristic: Characteristic | null
) => void;

const asError = (error: unknown): Error =>
  error instanceof Error ? error : new Error(String(error));

const uuidKey = (uuid: string): string => uuid.toLowerCase();

/**
 * A discovered or connected BLE device. Values returned by reads and
 * notifications are base64-encoded, matching the native bridge.
 */
export class Device {
  readonly id: string;
  name: string | null;
  rssi: number | null;
  manufacturerData: string | null;
  serviceUUIDs: string[];
  private readonly central: Central;

  constructor(central: Central, info: DeviceInfo) {
    this.central = central;
    this.id = info.id;
    this.name = info.name;
    this.rssi = info.rssi;
    this.manufacturerData = info.manufacturerData;
    this.serviceUUIDs = info.serviceUUIDs || [];
  }

  /** Discover all GATT services and characteristics on this device. */
  async discoverAllServicesAndCharacteristics(): Promise<this> {
    await this.central.discover(this.id);
    return this;
  }

  /** Return services discovered on this device. */
  services(): Promise<Service[]> {
    return this.central.servicesForDevice(this.id);
  }

  /** Return the characteristics for a discovered service. */
  characteristicsForService(serviceUUID: string): Promise<Characteristic[]> {
    return this.central.characteristicsForService(this.id, serviceUUID);
  }

  writeCharacteristicWithResponseForService(
    serviceUUID: string,
    characteristicUUID: string,
    valueBase64: string
  ): Promise<Characteristic> {
    return this.central.writeCharacteristic(
      this.id,
      serviceUUID,
      characteristicUUID,
      valueBase64,
      true
    );
  }

  writeCharacteristicWithoutResponseForService(
    serviceUUID: string,
    characteristicUUID: string,
    valueBase64: string
  ): Promise<Characteristic> {
    return this.central.writeCharacteristic(
      this.id,
      serviceUUID,
      characteristicUUID,
      valueBase64,
      false
    );
  }

  /** Read a characteristic and return its base64-encoded value. */
  readCharacteristic(
    serviceUUID: string,
    characteristicUUID: string
  ): Promise<Characteristic> {
    return this.central.readCharacteristic(
      this.id,
      serviceUUID,
      characteristicUUID
    );
  }

  /** Read the current RSSI and update this device's `rssi` property. */
  readRSSI(): Promise<number> {
    return this.central.readRSSI(this.id).then((rssi) => {
      this.rssi = rssi;
      return rssi;
    });
  }

  /** Subscribe to characteristic value changes. Call `remove()` to unsubscribe. */
  monitorCharacteristic(
    serviceUUID: string,
    characteristicUUID: string,
    listener: CharacteristicListener
  ): { remove: () => void } {
    return this.central.monitorCharacteristicForDevice(
      this.id,
      serviceUUID,
      characteristicUUID,
      listener
    );
  }
}

/**
 * BLE central manager for scanning and communicating with remote peripherals.
 */
export class Central extends EventEmitter {
  private readonly id: number;
  private readonly ready: Promise<void>;
  private readonly nativeSubscriptions: EventSubscription[];
  private readonly devices = new Map<string, Device>();
  private scanListener?: ScanListener;
  private destroyed = false;

  constructor() {
    if (!NativePeripheral) {
      throw new Error(LINKING_ERROR);
    }
    super();
    this.id = nextId++;
    this.nativeSubscriptions = [
      nativeEvents!.addListener(
        'onDeviceFound',
        (event: { id: number; device: DeviceInfo }) => {
          if (!this.destroyed && event.id === this.id) {
            const device = this.deviceFromInfo(event.device);
            this.scanListener?.(null, device);
            this.emit('deviceFound', device);
          }
        }
      ),
      nativeEvents!.addListener(
        'onConnect',
        (event: { id: number; deviceId: string; device?: DeviceInfo }) => {
          if (!this.destroyed && event.id === this.id) {
            const connected = event.device
              ? this.deviceFromInfo(event.device)
              : this.devices.get(event.deviceId);
            if (connected) this.emit('connect', connected);
          }
        }
      ),
      nativeEvents!.addListener(
        'onDisconnect',
        (event: { id: number; deviceId: string; error?: string }) => {
          if (!this.destroyed && event.id === this.id) {
            this.emit(
              'disconnect',
              this.devices.get(event.deviceId),
              event.error
            );
          }
        }
      ),
      nativeEvents!.addListener(
        'onCharacteristicValueChanged',
        (event: {
          id: number;
          deviceId: string;
          serviceUUID: string;
          characteristicUUID: string;
          value: string;
        }) => {
          if (!this.destroyed && event.id === this.id) {
            this.emit(
              `value:${event.deviceId}:${uuidKey(event.serviceUUID)}:${uuidKey(
                event.characteristicUUID
              )}`,
              null,
              this.characteristicValue(event)
            );
          }
        }
      ),
      nativeEvents!.addListener(
        'onCentralError',
        (event: { id: number; error: string }) => {
          if (!this.destroyed && event.id === this.id) {
            const error = new Error(event.error);
            this.scanListener?.(error, null);
            this.emit('error', error);
          }
        }
      ),
    ];
    this.ready = NativePeripheral.createCentral(this.id).then(() => undefined);
    this.ready.catch((error: unknown) => {
      if (!this.destroyed) this.emit('error', asError(error));
    });
  }

  private deviceFromInfo(info: DeviceInfo): Device {
    const device = this.devices.get(info.id);
    if (device) {
      if (info.name !== null) device.name = info.name;
      if (info.rssi !== null) device.rssi = info.rssi;
      if (info.manufacturerData !== null) {
        device.manufacturerData = info.manufacturerData;
      }
      if (info.serviceUUIDs.length > 0) device.serviceUUIDs = info.serviceUUIDs;
      return device;
    }
    const created = new Device(this, info);
    this.devices.set(info.id, created);
    return created;
  }

  private characteristicValue(event: {
    deviceId: string;
    serviceUUID: string;
    characteristicUUID: string;
    value: string;
  }): Characteristic {
    return {
      id: `${event.deviceId}:${event.serviceUUID}:${event.characteristicUUID}`,
      uuid: event.characteristicUUID,
      serviceUUID: event.serviceUUID,
      value: event.value,
      isReadable: true,
      isWritableWithResponse: false,
      isWritableWithoutResponse: false,
      isNotifiable: true,
      isIndicatable: false,
    };
  }

  /** Start a BLE scan. The listener receives each discovered device. */
  async startDeviceScan(
    serviceUUIDs: string[] | null,
    options: ScanOptions,
    listener: ScanListener
  ): Promise<void> {
    this.scanListener = listener;
    await this.ready;
    await NativePeripheral.startDeviceScan(this.id, serviceUUIDs, {
      allowDuplicates: options.allowDuplicates ?? false,
      scanMode: options.scanMode ?? ScanMode.LowPower,
      callbackType: options.callbackType ?? ScanCallbackType.AllMatches,
    }).catch((error: unknown) => {
      const scanError = asError(error);
      listener(scanError, null);
      throw scanError;
    });
  }

  async stopDeviceScan(): Promise<void> {
    await this.ready;
    await NativePeripheral.stopDeviceScan(this.id);
    this.scanListener = undefined;
  }

  /** Connect to a discovered device. */
  async connectToDevice(
    deviceId: string,
    options: ConnectionOptions = {}
  ): Promise<Device> {
    await this.ready;
    const info = (await NativePeripheral.connectToDevice(
      this.id,
      deviceId,
      options
    )) as DeviceInfo;
    return this.deviceFromInfo(info);
  }

  /** Disconnect from a device. */
  async cancelDeviceConnection(deviceId: string): Promise<void> {
    await this.ready;
    await NativePeripheral.cancelDeviceConnection(this.id, deviceId);
  }

  disconnect(deviceId: string): Promise<void> {
    return this.cancelDeviceConnection(deviceId);
  }

  discover(deviceId: string): Promise<void> {
    return this.ready.then(() =>
      NativePeripheral.discoverAllServicesAndCharacteristics(this.id, deviceId)
    );
  }

  async servicesForDevice(deviceId: string): Promise<Service[]> {
    await this.ready;
    return NativePeripheral.servicesForDevice(this.id, deviceId);
  }

  characteristicsForService(
    deviceId: string,
    serviceUUID: string
  ): Promise<Characteristic[]> {
    return this.ready.then(() =>
      NativePeripheral.characteristicsForService(this.id, deviceId, serviceUUID)
    );
  }

  writeCharacteristic(
    deviceId: string,
    serviceUUID: string,
    characteristicUUID: string,
    valueBase64: string,
    withResponse: boolean
  ): Promise<Characteristic> {
    return this.ready.then(() =>
      NativePeripheral.writeCharacteristic(
        this.id,
        deviceId,
        serviceUUID,
        characteristicUUID,
        valueBase64,
        withResponse
      )
    );
  }

  readCharacteristic(
    deviceId: string,
    serviceUUID: string,
    characteristicUUID: string
  ): Promise<Characteristic> {
    return this.ready.then(
      () =>
        NativePeripheral.readCharacteristic(
          this.id,
          deviceId,
          serviceUUID,
          characteristicUUID
        ) as Promise<Characteristic>
    );
  }

  /** Read a remote characteristic by device, service and characteristic UUID. */
  readCharacteristicForDevice(
    deviceId: string,
    serviceUUID: string,
    characteristicUUID: string
  ): Promise<Characteristic> {
    return this.readCharacteristic(deviceId, serviceUUID, characteristicUUID);
  }

  /** Subscribe to remote characteristic updates for a device. */
  monitorCharacteristicForDevice(
    deviceId: string,
    serviceUUID: string,
    characteristicUUID: string,
    listener: CharacteristicListener
  ): { remove: () => void } {
    const eventName = `value:${deviceId}:${uuidKey(serviceUUID)}:${uuidKey(
      characteristicUUID
    )}`;
    const subscription = (
      error: Error | null,
      characteristic: Characteristic
    ) => listener(error, characteristic);
    this.on(eventName, subscription);
    this.ready
      .then(() =>
        NativePeripheral.monitorCharacteristic(
          this.id,
          deviceId,
          serviceUUID,
          characteristicUUID
        )
      )
      .catch((error: unknown) => listener(asError(error), null));
    return {
      remove: () => {
        this.removeListener(eventName, subscription);
        this.ready
          .then(() =>
            NativePeripheral.stopMonitoringCharacteristic(
              this.id,
              deviceId,
              serviceUUID,
              characteristicUUID
            )
          )
          .catch((error: unknown) => this.emit('error', asError(error)));
      },
    };
  }

  async readRSSI(deviceId: string): Promise<number> {
    await this.ready;
    return NativePeripheral.readRSSI(this.id, deviceId);
  }

  /** Set the native BLE logging verbosity. */
  static setLogLevel(level: LogLevel): Promise<void> {
    return NativePeripheral.setLogLevel(level);
  }

  setLogLevel(level: LogLevel): Promise<void> {
    return Central.setLogLevel(level);
  }

  /** Stop scanning, disconnect devices and release native resources. */
  /** Release this manager and disconnect its devices. */
  async destroy(): Promise<void> {
    if (this.destroyed) return;
    await this.ready;
    await NativePeripheral.destroyCentral(this.id);
    this.destroyed = true;
    this.nativeSubscriptions.forEach((listener) => listener.remove());
    this.devices.clear();
    this.removeAllListeners();
  }
}

class Peripheral extends EventEmitter {
  private id: number;
  private _listeners?: EventSubscription[];
  private destroyed: boolean = false;

  constructor() {
    if (!NativePeripheral) {
      throw new Error(LINKING_ERROR);
    }
    super();
    this.id = nextId++;
    this._listeners = [
      nativeEvents!.addListener('onWrite', ({ id, ...args }) => {
        if (!this.destroyed && id === this.id) {
          this.emit('write', args as WriteEvent);
        }
      }),
      nativeEvents!.addListener('onSubscribe', ({ id, ...args }) => {
        if (!this.destroyed && id === this.id) {
          this.emit('subscribe', args as SubscriptionEvent);
        }
      }),
      nativeEvents!.addListener('onUnsubscribe', ({ id, ...args }) => {
        if (!this.destroyed && id === this.id) {
          this.emit('unsubscribe', args as SubscriptionEvent);
        }
      }),
    ];
    this.on('destroy', () => {
      if (this.destroyed) return;
      this.destroyed = true;
      this._listeners?.forEach((listener) => listener.remove());
      delete this._listeners;
    });
    NativePeripheral.createPeripheral(this.id)
      .then(() => {
        this.emit('ready');
      })
      .catch((err: Error) => {
        this.emit('error', err);
      });
  }

  static async setDeviceName(name: string) {
    return NativePeripheral.setDeviceName(name);
  }

  async checkState(): Promise<string> {
    return NativePeripheral.checkState(this.id);
  }

  async startAdvertising(
    servicesOrOpts?: AdvertiseServices | AdvertiseOptions,
    options?: AdvertiseOptions
  ) {
    const { manufacturerData, ...rest } = options || servicesOrOpts || {};
    const stringifiedOptions = {
      ...rest,
      manufacturerData: manufacturerData?.toString('base64'),
    };
    const stringifiedServices =
      options && servicesOrOpts
        ? Object.fromEntries(
            Object.entries(servicesOrOpts).map(([key, value]) => [
              key,
              value.toString('base64'),
            ])
          )
        : null;
    return NativePeripheral.startAdvertising(
      this.id,
      stringifiedServices,
      stringifiedOptions
    );
  }

  async stopAdvertising() {
    return NativePeripheral.stopAdvertising(this.id);
  }

  async addService(uuid: string, primary: boolean) {
    return NativePeripheral.addService(this.id, uuid, primary);
  }

  async addCharacteristic(
    serviceUuid: string,
    uuid: string,
    properties: Property,
    permissions: Permission
  ) {
    return NativePeripheral.addCharacteristic(
      this.id,
      serviceUuid,
      uuid,
      properties,
      permissions
    );
  }

  async updateValue(
    serviceUuid: string,
    characteristicUuid: string,
    value: Buffer
  ) {
    return NativePeripheral.updateValue(
      this.id,
      serviceUuid,
      characteristicUuid,
      value.toString('base64')
    );
  }

  async sendNotification(
    serviceUuid: string,
    characteristicUuid: string,
    value: Buffer,
    isIndication: boolean = false
  ) {
    return NativePeripheral.sendNotification(
      this.id,
      serviceUuid,
      characteristicUuid,
      value.toString('base64'),
      isIndication
    );
  }

  async destroy() {
    return NativePeripheral.destroyPeripheral(this.id).then(() => {
      this.emit('destroy');
    });
  }
}

export default Peripheral;
