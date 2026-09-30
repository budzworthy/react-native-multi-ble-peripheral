package com.fugood.reactnativemultibleperipheral

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.provider.SyncStateContract
import android.util.Log
import android.util.Base64
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

import java.util.UUID
import java.util.Arrays

import kotlin.collections.MutableSet
import kotlin.collections.MutableMap
import kotlin.collections.LinkedHashMap

@ReactModule(name = ReactNativeMultiBlePeripheralModule.NAME)
class ReactNativeMultiBlePeripheralModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String {
    return NAME
  }

  private var bluetoothManager: BluetoothManager? = null
  private var bluetoothAdapter: BluetoothAdapter? = null
  private var bluetoothLeAdvertisers: MutableMap<Int, BluetoothLeAdvertiser> = LinkedHashMap()
  private var bluetoothGattServers: MutableMap<Int, BluetoothGattServer> = LinkedHashMap()
  private var registeredDevices: MutableMap<Int, MutableSet<BluetoothDevice>> = LinkedHashMap()
  private var services: MutableMap<Int, MutableMap<String, BluetoothGattService>> = LinkedHashMap()
  private var advertiseCallbacks: MutableMap<Int, AdvertiseCallback> = LinkedHashMap()
  private val centralScans = LinkedHashMap<Int, ScanCallback>()
  private val centralGatts = LinkedHashMap<Int, MutableMap<String, BluetoothGatt>>()
  private val centralConnectPromises = LinkedHashMap<String, Promise>()
  private val centralOperationPromises = LinkedHashMap<String, Promise>()
  private val centralOperationNames = LinkedHashMap<String, String>()
  private val centralMonitors = LinkedHashMap<String, BluetoothGattCharacteristic>()
  private val centralScanDuplicates = LinkedHashMap<Int, Boolean>()
  private val centralScanSeenDevices = LinkedHashMap<Int, MutableSet<String>>()
  private var centralLogLevel = Log.INFO

  init {
    bluetoothManager = ContextCompat.getSystemService(
      reactContext,
      BluetoothManager::class.java
    )
    bluetoothAdapter = bluetoothManager?.adapter
  }

  fun sendEvent(name: String, params: WritableMap) {
    getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(name, params)
  }

  @ReactMethod
  fun setDeviceName(name: String, promise: Promise) {
    if (bluetoothAdapter == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    bluetoothAdapter!!.name = name
    promise.resolve(null)
  }

  @ReactMethod
  fun createPeripheral(id: Int, promise: Promise) {
    val bluetoothAdapter = bluetoothAdapter
    val bluetoothManager = bluetoothManager
    if (bluetoothAdapter == null || bluetoothManager == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    if (!bluetoothAdapter.isMultipleAdvertisementSupported() && bluetoothLeAdvertisers.isNotEmpty()) {
      promise.reject("error", "Not support multiple advertisement")
      return
    }
    val bluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not support bluetoothLeAdvertiser")
      return
    }
    bluetoothLeAdvertisers[id] = bluetoothLeAdvertiser

    services[id] = LinkedHashMap()

    promise.resolve(null)
  }

  @ReactMethod
  fun checkState(id: Int, promise: Promise) {
    val bluetoothAdapter = bluetoothAdapter
    if (bluetoothAdapter == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    var stateString = when (bluetoothAdapter.state) {
      BluetoothAdapter.STATE_ON -> "on"
      BluetoothAdapter.STATE_OFF -> "off"
      BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
      BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
      else -> "unknown"
    }
    promise.resolve(stateString)
  }

  @ReactMethod
  fun addService(
    id: Int,
    uuid: String,
    primary: Boolean,
    promise: Promise
  ) {
    val serviceUUID = UUID.fromString(uuid)
    val service = BluetoothGattService(
      serviceUUID,
      if (primary) BluetoothGattService.SERVICE_TYPE_PRIMARY else BluetoothGattService.SERVICE_TYPE_SECONDARY
    )
    services[id]?.put(uuid, service)
    promise.resolve(null)
  }

  @ReactMethod
  fun addCharacteristic(
    id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    properties: Int,
    permissions: Int,
    promise: Promise
  ) {
    if (services[id] == null) {
      promise.reject("error", "Not found peripheral")
      return
    }
    val service = services[id]?.get(serviceUUID)
    if (service == null) {
      promise.reject("error", "Not found service")
      return
    }
    val characteristic = BluetoothGattCharacteristic(
      UUID.fromString(characteristicUUID),
      properties,
      permissions
    )
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
      val descriptor = BluetoothGattDescriptor(
        Constants.CLIENT_CONFIG,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
      )
      characteristic.addDescriptor(descriptor)
    }
    service.addCharacteristic(characteristic)
    promise.resolve(null)
  }

  @ReactMethod
  fun updateValue(
    id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    value: String,
    promise: Promise
  ) {
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not found bluetoothLeAdvertiser")
      return
    }
    val service = services[id]?.get(serviceUUID)
    if (service == null) {
      promise.reject("error", "Not found service")
      return
    }
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
    if (characteristic == null) {
      promise.reject("error", "Not found characteristic")
      return
    }
    characteristic.value = Base64.decode(value, Base64.DEFAULT)
    promise.resolve(null)
  }

  @ReactMethod
  fun sendNotification(
    id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    value: String,
    confirm: Boolean,
    promise: Promise
  ) {
    val bluetoothGattServer = bluetoothGattServers[id]
    if (bluetoothGattServer == null) {
      promise.reject("error", "Have not started advertising")
      return
    }
    val registeredDevices = registeredDevices[id]
    if (registeredDevices == null) {
      promise.reject("error", "Not found registeredDevices")
      return
    }
    val service = bluetoothGattServer.getService(UUID.fromString(serviceUUID))
    if (service == null) {
      promise.reject("error", "Not found service")
      return
    }
    val characteristic = service.getCharacteristic(UUID.fromString(characteristicUUID))
    if (characteristic == null) {
      promise.reject("error", "Not found characteristic")
      return
    }
    var value = Base64.decode(value, Base64.DEFAULT)
    characteristic.value = value
    for (device in registeredDevices) {
      val response = if (Build.VERSION.SDK_INT >= 33) {
        bluetoothGattServer.notifyCharacteristicChanged(
          device,
          characteristic,
          confirm,
          value
        )
      } else {
        bluetoothGattServer.notifyCharacteristicChanged(
          device,
          characteristic,
          confirm
        )
      }
      Log.d(NAME, "Notify ${device.name} (${device.address}) response = $response")
    }
    promise.resolve(null)
  }

  @ReactMethod
  fun startAdvertising(
    id: Int,
    advServices: ReadableMap?,
    options: ReadableMap?,
    promise: Promise
  ) {
    val bluetoothManager = bluetoothManager
    if (bluetoothManager == null) {
      promise.reject("error", "Not support bluetooth")
      return
    }
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not found bluetoothLeAdvertiser")
      return
    }
    if (advertiseCallbacks[id] != null) {
      promise.reject("error", "Already advertising")
      return
    }

    registeredDevices[id] = mutableSetOf<BluetoothDevice>()

    val bluetoothGattServer = bluetoothManager.openGattServer(
      getReactApplicationContext(),
      object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
          super.onConnectionStateChange(device, status, newState)
          var registeredDevices = registeredDevices[id]
          if (registeredDevices != null) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
              Log.i(NAME, "BluetoothDevice CONNECTED: $device")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
              Log.i(NAME, "BluetoothDevice DISCONNECTED: $device")
              registeredDevices.remove(device)
            }
          }
        }

        override fun onCharacteristicReadRequest(
          device: BluetoothDevice,
          requestId: Int,
          offset: Int,
          characteristic: BluetoothGattCharacteristic
        ) {
          super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
          var gattServer = bluetoothGattServers[id]
          if (gattServer != null) {
            if (offset > characteristic.value.size) {
              gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
              gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
            }
          }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
          super.onNotificationSent(device, status)
        }

        override fun onCharacteristicWriteRequest(
          device: BluetoothDevice,
          requestId: Int,
          characteristic: BluetoothGattCharacteristic,
          preparedWrite: Boolean,
          responseNeeded: Boolean,
          offset: Int,
          value: ByteArray
        ) {
          super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
          var gattServer = bluetoothGattServers[id]
          if (gattServer != null) {
            val params = Arguments.createMap()
            params.putString("id", id.toString())
            params.putString("device", device.address)
            params.putString("service", characteristic.service.uuid.toString())
            params.putString("characteristic", characteristic.uuid.toString())
            params.putInt("offset", offset)
            params.putString("value", Base64.encodeToString(value, Base64.NO_WRAP))
            sendEvent("onWrite", params)
            if (responseNeeded) {
              gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
          }
        }

        override fun onDescriptorReadRequest(
          device: BluetoothDevice,
          requestId: Int,
          offset: Int,
          descriptor: BluetoothGattDescriptor
        ) {
          super.onDescriptorReadRequest(device, requestId, offset, descriptor)
          var gattServer = bluetoothGattServers[id]
          var registeredDevices = registeredDevices[id]
          if (gattServer != null && registeredDevices != null) {
            if (Constants.CLIENT_CONFIG == descriptor.uuid) {
                val returnValue =
                  if (registeredDevices.contains(device))
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                  else
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                gattServer.sendResponse(
                  device,
                  requestId,
                  BluetoothGatt.GATT_SUCCESS,
                  0,
                  returnValue
                )
            } else {
              Log.w(NAME, "Unknown descriptor write request")
              gattServer.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_FAILURE,
                0,
                null
              )
            }
          }
        }

        override fun onDescriptorWriteRequest(
          device: BluetoothDevice,
          requestId: Int,
          descriptor: BluetoothGattDescriptor,
          preparedWrite: Boolean,
          responseNeeded: Boolean,
          offset: Int,
          value: ByteArray
        ) {
          super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
          var gattServer = bluetoothGattServers[id]
          var registeredDevices = registeredDevices[id]
          if (gattServer != null && registeredDevices != null) {
            if (Constants.CLIENT_CONFIG == descriptor.uuid) {
              if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                registeredDevices.add(device)
              } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                registeredDevices.remove(device)
              }
              if (responseNeeded) {
                gattServer.sendResponse(
                  device,
                  requestId,
                  BluetoothGatt.GATT_SUCCESS,
                  0,
                  null
                )
              }
            } else {
              Log.w(NAME, "Unknown descriptor write request")
              gattServer.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_FAILURE,
                0,
                null
              )
            }
          }
        }

      }
    )
    bluetoothGattServers[id] = bluetoothGattServer

    for (service in services[id]!!.values) {
      bluetoothGattServer.addService(service)
    }

    val connectable = Utils.get<Boolean>(options, "connectable", true)
    val includeDeviceName = Utils.get<Boolean>(options, "includeDeviceName", true)
    val includeTxPower = Utils.get<Boolean>(options, "includeTxPower", false)
    val advertiseMode = Utils.get<Int>(options, "mode", AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    val txPowerLevel = Utils.get<Int>(options, "txPowerLevel", AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
    val manufacturerId = Utils.getInt(options, "manufacturerId")
    val manufacturerData = Utils.getString(options, "manufacturerData")

    val advertiseSettings = AdvertiseSettings.Builder()
      .setAdvertiseMode(advertiseMode)
      .setTxPowerLevel(txPowerLevel)
      .setConnectable(connectable)
      .setTimeout(0)
      .build()

    val advertiseDataBuilder = AdvertiseData.Builder()
      .setIncludeDeviceName(includeDeviceName)
      .setIncludeTxPowerLevel(includeTxPower)
    if (manufacturerId != null && manufacturerData != null) {
      advertiseDataBuilder.addManufacturerData(
        manufacturerId,
        Base64.decode(manufacturerData, Base64.DEFAULT)
      )
    }
    if (advServices != null) {
      val iterator = advServices.keySetIterator()
      while (iterator.hasNextKey()) {
        val key = iterator.nextKey()
        val uuid = UUID.fromString(key)
        val valueType = advServices.getType(key)
        if (valueType == com.facebook.react.bridge.ReadableType.String) {
          val value = advServices.getString(key)
          if (value != null && value != "") {
            val data = Base64.decode(value, Base64.DEFAULT)
            advertiseDataBuilder.addServiceData(ParcelUuid(uuid), data)
          }
        }
        advertiseDataBuilder.addServiceUuid(ParcelUuid(uuid))
      }
    }

    if (advertiseCallbacks[id] == null) {
      advertiseCallbacks[id] = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
          super.onStartSuccess(settingsInEffect)
          promise.resolve(null)
        }
        override fun onStartFailure(errorCode: Int) {
          super.onStartFailure(errorCode)
          promise.reject("error", "Error on advertising, code $errorCode")
        }
      }
    }

    val advertiseData = advertiseDataBuilder.build()
    bluetoothLeAdvertiser.startAdvertising(advertiseSettings, advertiseData, advertiseCallbacks[id])
  }

  @ReactMethod
  fun stopAdvertising(
    id: Int,
    promise: Promise
  ) {
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (bluetoothLeAdvertiser == null) {
      promise.reject("error", "Not found bluetoothLeAdvertiser")
      return
    }
    if (advertiseCallbacks[id] == null) {
      promise.reject("error", "Had not start advertising")
      return
    }
    bluetoothLeAdvertiser.stopAdvertising(advertiseCallbacks[id])
    registeredDevices.remove(id)
    promise.resolve(null)
  }

  @ReactMethod
  fun destroyPeripheral(
    id: Int,
    promise: Promise
  ) {
    val callback = advertiseCallbacks[id]
    val bluetoothLeAdvertiser = bluetoothLeAdvertisers[id]
    if (callback != null && bluetoothLeAdvertiser != null) {
      bluetoothLeAdvertiser.stopAdvertising(callback)
    }
    bluetoothGattServers[id]?.close()
    bluetoothGattServers.remove(id)
    bluetoothLeAdvertisers.remove(id)
    advertiseCallbacks.remove(id)
    registeredDevices.remove(id)
    promise.resolve(null)
  }

  private fun centralKey(id: Int, deviceId: String) = "$id:$deviceId"

  private fun deviceMap(device: BluetoothDevice, rssi: Int? = null, scanRecord: android.bluetooth.le.ScanRecord? = null): WritableMap {
    val result = Arguments.createMap()
    result.putString("id", device.address)
    result.putString("name", scanRecord?.deviceName ?: device.name)
    if (rssi == null) result.putNull("rssi") else result.putInt("rssi", rssi)
    val manufacturer = scanRecord?.manufacturerSpecificData
    if (manufacturer != null && manufacturer.size() > 0) {
      result.putString("manufacturerData", Base64.encodeToString(manufacturer.valueAt(0), Base64.NO_WRAP))
    } else {
      result.putNull("manufacturerData")
    }
    val uuids = Arguments.createArray()
    scanRecord?.serviceUuids?.forEach { uuids.pushString(it.uuid.toString()) }
    result.putArray("serviceUUIDs", uuids)
    return result
  }

  private fun characteristicMap(characteristic: BluetoothGattCharacteristic): WritableMap {
    val result = Arguments.createMap()
    result.putString("id", characteristic.uuid.toString())
    result.putString("uuid", characteristic.uuid.toString())
    result.putString("serviceUUID", characteristic.service.uuid.toString())
    result.putString("value", characteristic.value?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
    result.putBoolean("isReadable", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
    result.putBoolean("isWritableWithResponse", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
    result.putBoolean("isWritableWithoutResponse", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
    result.putBoolean("isNotifiable", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
    result.putBoolean("isIndicatable", characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
    return result
  }

  private fun finishCentralOperation(id: Int, deviceId: String, operation: String, status: Int, value: Any? = null) {
    val key = centralKey(id, deviceId)
    if (centralOperationNames[key] != operation) return
    centralOperationNames.remove(key)
    val promise = centralOperationPromises.remove(key) ?: return
    if (status != BluetoothGatt.GATT_SUCCESS) {
      promise.reject("error", "GATT $operation failed with status $status")
    } else {
      promise.resolve(value)
    }
  }

  private fun beginCentralOperation(id: Int, deviceId: String, operation: String, promise: Promise): Boolean {
    val key = centralKey(id, deviceId)
    if (centralOperationPromises.containsKey(key)) {
      promise.reject("error", "A GATT operation is already in progress for $deviceId")
      return false
    }
    centralOperationPromises[key] = promise
    centralOperationNames[key] = operation
    return true
  }

  private fun centralCallback(id: Int): BluetoothGattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
      val deviceId = gatt.device.address
      val key = centralKey(id, deviceId)
      if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
        centralConnectPromises.remove(key)?.resolve(deviceMap(gatt.device))
        val event = Arguments.createMap()
        event.putInt("id", id)
        event.putString("deviceId", deviceId)
        event.putMap("device", deviceMap(gatt.device))
        sendEvent("onConnect", event)
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        centralConnectPromises.remove(key)?.reject("error", "Connection to $deviceId failed with status $status")
        centralOperationPromises.remove(key)?.reject("error", "Device $deviceId disconnected")
        centralOperationNames.remove(key)
        centralGatts[id]?.remove(deviceId)?.close()
        val event = Arguments.createMap()
        event.putInt("id", id)
        event.putString("deviceId", deviceId)
        if (status != BluetoothGatt.GATT_SUCCESS) event.putString("error", "GATT status $status")
        sendEvent("onDisconnect", event)
      }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
      finishCentralOperation(id, gatt.device.address, "discover", status)
    }

    override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      val result = if (status == BluetoothGatt.GATT_SUCCESS) characteristicMap(characteristic) else null
      finishCentralOperation(id, gatt.device.address, "read", status, result)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      val result = if (status == BluetoothGatt.GATT_SUCCESS) characteristicMap(characteristic) else null
      finishCentralOperation(id, gatt.device.address, "write", status, result)
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      finishCentralOperation(id, gatt.device.address, "monitor", status)
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      val event = Arguments.createMap()
      event.putInt("id", id)
      event.putString("deviceId", gatt.device.address)
      event.putString("serviceUUID", characteristic.service.uuid.toString())
      event.putString("characteristicUUID", characteristic.uuid.toString())
      event.putString("value", characteristic.value?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
      sendEvent("onCharacteristicValueChanged", event)
    }

    override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
      finishCentralOperation(id, gatt.device.address, "rssi", status, rssi)
    }
  }

  @ReactMethod
  fun createCentral(id: Int, promise: Promise) {
    if (bluetoothAdapter == null) {
      promise.reject("error", "Bluetooth is not supported")
      return
    }
    centralGatts[id] = LinkedHashMap()
    promise.resolve(null)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun startDeviceScan(id: Int, serviceUUIDs: ReadableArray?, options: ReadableMap?, promise: Promise) {
    val scanner = bluetoothAdapter?.bluetoothLeScanner
    if (scanner == null) {
      promise.reject("error", "Bluetooth LE scanning is unavailable")
      return
    }
    centralScans[id]?.let {
      scanner.stopScan(it)
      centralScans.remove(id)
    }
    val filters = mutableListOf<ScanFilter>()
    serviceUUIDs?.let { uuids ->
      for (index in 0 until uuids.size()) {
        uuids.getString(index)?.let { uuid ->
          filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(uuid))).build())
        }
      }
    }
    val mode = Utils.get<Int>(options, "scanMode", ScanSettings.SCAN_MODE_LOW_POWER)
    val callbackType = Utils.get<Int>(options, "callbackType", ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
    centralScanDuplicates[id] = Utils.get<Boolean>(options, "allowDuplicates", false)
    centralScanSeenDevices[id] = mutableSetOf()
    val settings = ScanSettings.Builder()
      .setScanMode(mode)
      .setCallbackType(callbackType)
      .setReportDelay(0)
      .build()
    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        val seenDevices = centralScanSeenDevices[id] ?: mutableSetOf()
        if (centralScanDuplicates[id] != true && !seenDevices.add(result.device.address)) return
        if (centralLogLevel <= Log.DEBUG) Log.d(NAME, "Central scan result received")
        val event = Arguments.createMap()
        event.putInt("id", id)
        event.putMap("device", deviceMap(result.device, result.rssi, result.scanRecord))
        sendEvent("onDeviceFound", event)
      }

      override fun onBatchScanResults(results: MutableList<ScanResult>) {
        results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
      }

      override fun onScanFailed(errorCode: Int) {
        val event = Arguments.createMap()
        event.putInt("id", id)
        event.putString("error", "BLE scan failed with code $errorCode")
        sendEvent("onCentralError", event)
      }
    }
    try {
      scanner.startScan(filters, settings, callback)
      centralScans[id] = callback
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("error", error.message, error)
    }
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun stopDeviceScan(id: Int, promise: Promise) {
    val callback = centralScans.remove(id)
    if (callback != null) bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
    centralScanDuplicates.remove(id)
    centralScanSeenDevices.remove(id)
    promise.resolve(null)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun connectToDevice(id: Int, deviceId: String, options: ReadableMap?, promise: Promise) {
    val adapter = bluetoothAdapter
    val device = try { adapter?.getRemoteDevice(deviceId) } catch (_: IllegalArgumentException) { null }
    if (device == null) {
      promise.reject("error", "Unknown Bluetooth device: $deviceId")
      return
    }
    val key = centralKey(id, deviceId)
    centralConnectPromises.remove(key)?.reject("error", "Connection request replaced")
    centralConnectPromises[key] = promise
    val autoConnect = Utils.get<Boolean>(options, "autoConnect", false)
    try {
      device.connectGatt(getReactApplicationContext(), autoConnect, centralCallback(id))
        ?.let {
          centralGatts[id]?.put(deviceId, it)
          if (it.device.address != deviceId) centralGatts[id]?.put(it.device.address, it)
        }
        ?: run {
          centralConnectPromises.remove(key)
          promise.reject("error", "Unable to connect to $deviceId")
        }
      val timeout = Utils.get<Int>(options, "timeout", 30000)
      if (timeout > 0) Handler(getReactApplicationContext().mainLooper).postDelayed({
        centralConnectPromises.remove(key)?.let {
          centralGatts[id]?.remove(deviceId)?.disconnect()
          it.reject("error", "Connection to $deviceId timed out")
        }
      }, timeout.toLong())
    } catch (error: Exception) {
      centralConnectPromises.remove(key)
      promise.reject("error", error.message, error)
    }
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun cancelDeviceConnection(id: Int, deviceId: String, promise: Promise) {
    centralGatts[id]?.get(deviceId)?.disconnect()
    promise.resolve(null)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun discoverAllServicesAndCharacteristics(id: Int, deviceId: String, promise: Promise) {
    val gatt = centralGatts[id]?.get(deviceId)
    if (gatt == null || !beginCentralOperation(id, deviceId, "discover", promise)) {
      if (gatt == null) promise.reject("error", "Device is not connected: $deviceId")
      return
    }
    if (!gatt.discoverServices()) finishCentralOperation(id, deviceId, "discover", BluetoothGatt.GATT_FAILURE)
  }

  @ReactMethod
  fun servicesForDevice(id: Int, deviceId: String, promise: Promise) {
    val services = centralGatts[id]?.get(deviceId)?.services
    if (services == null) {
      promise.reject("error", "Services have not been discovered for $deviceId")
      return
    }
    val result = Arguments.createArray()
    services.forEach { service ->
      val item = Arguments.createMap()
      item.putString("uuid", service.uuid.toString())
      item.putBoolean("isPrimary", service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY)
      result.pushMap(item)
    }
    promise.resolve(result)
  }

  @ReactMethod
  fun characteristicsForService(id: Int, deviceId: String, serviceUUID: String, promise: Promise) {
    val service = centralGatts[id]?.get(deviceId)?.getService(UUID.fromString(serviceUUID))
    if (service == null) {
      promise.reject("error", "Service not found: $serviceUUID")
      return
    }
    val result = Arguments.createArray()
    service.characteristics.forEach { result.pushMap(characteristicMap(it)) }
    promise.resolve(result)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun writeCharacteristic(id: Int, deviceId: String, serviceUUID: String, characteristicUUID: String, value: String, withResponse: Boolean, promise: Promise) {
    val gatt = centralGatts[id]?.get(deviceId)
    val characteristic = gatt?.getService(UUID.fromString(serviceUUID))?.getCharacteristic(UUID.fromString(characteristicUUID))
    if (gatt == null || characteristic == null) {
      promise.reject("error", "Characteristic not found: $characteristicUUID")
      return
    }
    if (!beginCentralOperation(id, deviceId, "write", promise)) return
    characteristic.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    characteristic.value = Base64.decode(value, Base64.DEFAULT)
    if (!gatt.writeCharacteristic(characteristic)) finishCentralOperation(id, deviceId, "write", BluetoothGatt.GATT_FAILURE)
    if (!withResponse) finishCentralOperation(id, deviceId, "write", BluetoothGatt.GATT_SUCCESS, characteristicMap(characteristic))
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun readCharacteristic(id: Int, deviceId: String, serviceUUID: String, characteristicUUID: String, promise: Promise) {
    val gatt = centralGatts[id]?.get(deviceId)
    val characteristic = gatt?.getService(UUID.fromString(serviceUUID))?.getCharacteristic(UUID.fromString(characteristicUUID))
    if (gatt == null || characteristic == null) {
      promise.reject("error", "Characteristic not found: $characteristicUUID")
      return
    }
    if (!beginCentralOperation(id, deviceId, "read", promise)) return
    if (!gatt.readCharacteristic(characteristic)) finishCentralOperation(id, deviceId, "read", BluetoothGatt.GATT_FAILURE)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun monitorCharacteristic(id: Int, deviceId: String, serviceUUID: String, characteristicUUID: String, promise: Promise) {
    val gatt = centralGatts[id]?.get(deviceId)
    val characteristic = gatt?.getService(UUID.fromString(serviceUUID))?.getCharacteristic(UUID.fromString(characteristicUUID))
    if (gatt == null || characteristic == null) {
      promise.reject("error", "Characteristic not found: $characteristicUUID")
      return
    }
    if (!beginCentralOperation(id, deviceId, "monitor", promise)) return
    if (!gatt.setCharacteristicNotification(characteristic, true)) {
      finishCentralOperation(id, deviceId, "monitor", BluetoothGatt.GATT_FAILURE)
      return
    }
    val descriptor = characteristic.getDescriptor(Constants.CLIENT_CONFIG)
    if (descriptor == null) {
      finishCentralOperation(id, deviceId, "monitor", BluetoothGatt.GATT_FAILURE)
      return
    }
    descriptor.value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
      BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    centralMonitors["$id:$deviceId:$serviceUUID:$characteristicUUID"] = characteristic
    if (!gatt.writeDescriptor(descriptor)) finishCentralOperation(id, deviceId, "monitor", BluetoothGatt.GATT_FAILURE)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun stopMonitoringCharacteristic(id: Int, deviceId: String, serviceUUID: String, characteristicUUID: String, promise: Promise) {
    val gatt = centralGatts[id]?.get(deviceId)
    val characteristic = centralMonitors.remove("$id:$deviceId:$serviceUUID:$characteristicUUID")
    if (gatt != null && characteristic != null) {
      gatt.setCharacteristicNotification(characteristic, false)
      characteristic.getDescriptor(Constants.CLIENT_CONFIG)?.let {
        it.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(it)
      }
    }
    promise.resolve(null)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun readRSSI(id: Int, deviceId: String, promise: Promise) {
    val gatt = centralGatts[id]?.get(deviceId)
    if (gatt == null || !beginCentralOperation(id, deviceId, "rssi", promise)) {
      if (gatt == null) promise.reject("error", "Device is not connected: $deviceId")
      return
    }
    if (!gatt.readRemoteRssi()) finishCentralOperation(id, deviceId, "rssi", BluetoothGatt.GATT_FAILURE)
  }

  @ReactMethod
  fun setLogLevel(level: Int, promise: Promise) {
    centralLogLevel = when (level) {
      0 -> Log.VERBOSE
      1 -> Log.DEBUG
      2 -> Log.INFO
      3 -> Log.WARN
      4 -> Log.ERROR
      else -> Int.MAX_VALUE
    }
    promise.resolve(null)
  }

  @SuppressLint("MissingPermission")
  @ReactMethod
  fun destroyCentral(id: Int, promise: Promise) {
    centralScans.remove(id)?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
    centralGatts.remove(id)?.values?.forEach {
      it.disconnect()
      it.close()
    }
    centralConnectPromises.keys.filter { it.startsWith("$id:") }.forEach { centralConnectPromises.remove(it)?.reject("error", "Central destroyed") }
    centralOperationPromises.keys.filter { it.startsWith("$id:") }.forEach { centralOperationPromises.remove(it)?.reject("error", "Central destroyed") }
    centralOperationNames.keys.removeAll { it.startsWith("$id:") }
    centralMonitors.keys.removeAll { it.startsWith("$id:") }
    centralScanDuplicates.remove(id)
    centralScanSeenDevices.remove(id)
    promise.resolve(null)
  }

  companion object {
    const val NAME = "ReactNativeMultiBlePeripheral"
  }
}
