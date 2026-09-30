import Foundation
import CoreBluetooth
import React

extension Dictionary where Value: Equatable {
  func someKey(forValue val: Value) -> Key? {
    return first(where: { $1 == val })?.key
  }
}

@objc(ReactNativeMultiBlePeripheral)
class ReactNativeMultiBlePeripheral: RCTEventEmitter, CBPeripheralManagerDelegate, CBCentralManagerDelegate, CBPeripheralDelegate {

  var hasListeners: Bool = false
  var managers: [Int: CBPeripheralManager] = [:]
  var centralManagers: [Int: CBCentralManager] = [:]
  var centralDevices: [Int: [String: CBPeripheral]] = [:]
  var createRejects: [Int: RCTPromiseRejectBlock] = [:]
  var createResolves: [Int: RCTPromiseResolveBlock] = [:]
  var startRejects: [Int: RCTPromiseRejectBlock] = [:]
  var startResolves: [Int: RCTPromiseResolveBlock] = [:]
  var centralCreateResolves: [Int: RCTPromiseResolveBlock] = [:]
  var centralCreateRejects: [Int: RCTPromiseRejectBlock] = [:]
  var centralConnectResolves: [String: RCTPromiseResolveBlock] = [:]
  var centralConnectRejects: [String: RCTPromiseRejectBlock] = [:]
  var centralOperationNames: [String: String] = [:]
  var centralOperationResolves: [String: RCTPromiseResolveBlock] = [:]
  var centralOperationRejects: [String: RCTPromiseRejectBlock] = [:]
  var centralDiscoveryRemaining: [String: Int] = [:]
  var services: [Int: [String:CBMutableService]] = [:]
  var deviceName: String = ""

  deinit {
    managers.forEach { _, manager in
      if manager.isAdvertising {
        manager.stopAdvertising()
      }
    }
  }

  override func startObserving() {
    hasListeners = true
  }

  override func stopObserving() {
    hasListeners = false
  }

  override func supportedEvents() -> [String]! {
    return [
      "onWrite", "onSubscribe", "onUnsubscribe", "onDeviceFound", "onConnect",
      "onDisconnect", "onCharacteristicValueChanged", "onCentralError",
    ]
  }

  override class func requiresMainQueueSetup() -> Bool { return false }

  func getCharacteristic(
    _ id: Int,
    _ serviceUUID: String,
    _ characteristicUUID: CBUUID
  ) -> CBMutableCharacteristic? {
    return services[id]?[serviceUUID]?.characteristics?.first(where: {
      $0.uuid == characteristicUUID
    }) as? CBMutableCharacteristic
  }

  func centralKey(_ id: Int, _ deviceID: String) -> String {
    return "\(id):\(deviceID)"
  }

  func centralID(for peripheral: CBPeripheral) -> Int? {
    return centralDevices.first(where: { $0.value.values.contains(where: { $0 === peripheral }) })?.key
  }

  func centralDeviceInfo(_ peripheral: CBPeripheral, rssi: Int? = nil, advertisementData: [String: Any]? = nil) -> [String: Any] {
    var info: [String: Any] = [
      "id": peripheral.identifier.uuidString,
      "name": peripheral.name as Any? ?? NSNull(),
      "rssi": rssi.map { $0 as Any } ?? NSNull(),
      "manufacturerData": NSNull(),
      "serviceUUIDs": (advertisementData?[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []).map { $0.uuidString },
    ]
    if let data = advertisementData?[CBAdvertisementDataManufacturerDataKey] as? Data {
      info["manufacturerData"] = data.base64EncodedString()
    }
    return info
  }

  func centralCharacteristicInfo(_ characteristic: CBCharacteristic) -> [String: Any] {
    let properties = characteristic.properties
    return [
      "id": characteristic.uuid.uuidString,
      "uuid": characteristic.uuid.uuidString,
      "serviceUUID": characteristic.service?.uuid.uuidString ?? "",
      "value": characteristic.value?.base64EncodedString() ?? NSNull() as Any,
      "isReadable": properties.contains(.read),
      "isWritableWithResponse": properties.contains(.write),
      "isWritableWithoutResponse": properties.contains(.writeWithoutResponse),
      "isNotifiable": properties.contains(.notify),
      "isIndicatable": properties.contains(.indicate),
    ]
  }

  func finishCentralOperation(_ peripheral: CBPeripheral, operation: String, error: Error?, value: Any? = nil) {
    guard let id = centralID(for: peripheral) else { return }
    let key = centralKey(id, deviceID)
    guard centralOperationNames[key] == operation else { return }
    centralOperationNames.removeValue(forKey: key)
    let resolve = centralOperationResolves.removeValue(forKey: key)
    let reject = centralOperationRejects.removeValue(forKey: key)
    if let error = error {
      reject?("error", error.localizedDescription, error)
    } else {
      resolve?(value)
    }
  }

  func beginCentralOperation(
    _ id: Int,
    _ deviceID: String,
    operation: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Bool {
    let key = centralKey(id, deviceID)
    guard centralOperationResolves[key] == nil else {
      reject("error", "A GATT operation is already in progress for \(deviceID)", nil)
      return false
    }
    centralOperationNames[key] = operation
    centralOperationResolves[key] = resolve
    centralOperationRejects[key] = reject
    return true
  }

  @objc(setDeviceName:withResolver:withRejecter:)
  func setDeviceName(
    _ name: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    deviceName = name
    resolve(nil)
  }

  @objc(createPeripheral:withResolver:withRejecter:)
  func createPeripheral(
    _ id: Int,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    createResolves[id] = resolve
    createRejects[id] = reject
    managers[id] = CBPeripheralManager(delegate: self, queue: nil, options: nil)
    services[id] = [:]
  }

  @objc(checkState:withResolver:withRejecter:)
  func checkState(
    _ id: Int,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let manager = managers[id]
    if manager == nil {
      reject("error", "Peripheral id does not exist", nil)
      return
    }
    let stateName: String
    switch manager!.state {
    case .unknown:
      stateName = "unknown"
    case .resetting:
      stateName = "resetting"
    case .unsupported:
      stateName = "unsupported"
    case .unauthorized:
      stateName = "unauthorized"
    case .poweredOff:
      stateName = "off"
    case .poweredOn:
      stateName = "on"
    default:
      stateName = "unknown"
    }
    resolve(stateName)
  }

  @objc(addService:serviceUUID:primary:withResolver:withRejecter:)
  func addService(
    _ id: Int,
    serviceUUID: String,
    primary: Bool,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let manager = managers[id]
    if manager == nil {
      reject("error", "Peripheral id does not exist", nil)
      return
    }
    let uuid = CBUUID(string: serviceUUID)
    let service = CBMutableService(type: uuid, primary: primary)
    service.characteristics = [CBCharacteristic]()
    services[id]?[serviceUUID] = service
    manager?.add(service)
    resolve(nil)
  }

  @objc(addCharacteristic:serviceUUID:characteristicUUID:properties:permissions:withResolver:withRejecter:)
  func addCharacteristic(
    _ id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    properties: UInt,
    permissions: UInt,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let manager = managers[id]
    if manager == nil {
      reject("error", "Peripheral id does not exist", nil)
      return
    }
    let uuid = CBUUID(string: characteristicUUID)
    let characteristic = CBMutableCharacteristic(
      type: uuid,
      properties: CBCharacteristicProperties(rawValue: properties),
      value: nil,
      permissions: CBAttributePermissions(rawValue: permissions)
    )
    if let service = services[id]?[serviceUUID] {
      service.characteristics?.append(characteristic)
      manager?.removeAllServices()
      for (_, service) in services[id]! {
        manager?.add(service)
      }
      resolve(nil)
    } else {
      reject("error", "Service does not exist", nil)
    }
  }

  @objc(startAdvertising:withServices:withOptions:withResolver:withRejecter:)
  func startAdvertising(
    _ id: Int,
    advServices: NSDictionary?,
    options: NSDictionary?,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let manager = managers[id]
    if manager == nil {
      reject("error", "Peripheral id does not exist", nil)
      return
    }
    if manager?.state != .poweredOn {
      reject("error", "Peripheral is not powered on", nil)
      return
    }
    if manager?.isAdvertising == true {
      reject("error", "Peripheral is already advertising", nil)
      return
    }
    let uuids = advServices?.map { key, _ in
      CBUUID(string: key as! String)
    } ?? services[id]?.map { key, _ in
      CBUUID(string: key)
    }
    let advertisementData = [
      CBAdvertisementDataLocalNameKey: deviceName,
      CBAdvertisementDataServiceUUIDsKey: uuids,
    ] as [String : Any]
    startResolves[id] = resolve
    startRejects[id] = reject
    manager!.startAdvertising(advertisementData)
  }

  @objc(stopAdvertising:withResolver:withRejecter:)
  func stopAdvertising(
    _ id: Int,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let manager = managers[id]
    if manager == nil {
      reject("error", "Peripheral id does not exist", nil)
      return
    }
    if manager!.isAdvertising {
      manager!.stopAdvertising()
    }
    resolve(nil)
  }

  @objc(updateValue:serviceUUID:characteristicUUID:value:withResolver:withRejecter:)
  func updateValue(
    _ id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    value: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let uuid = CBUUID(string: characteristicUUID)
    if let characteristic = getCharacteristic(id, serviceUUID, uuid) {
      characteristic.value = Data(base64Encoded: value)
      resolve(nil)
    } else {
      reject("error", "Not found characteristic", nil)
    }
  }

  @objc(sendNotification:serviceUUID:characteristicUUID:value:confirm:withResolver:withRejecter:)
  func sendNotification(
    _ id: Int,
    serviceUUID: String,
    characteristicUUID: String,
    value: String,
    confirm: Bool,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let uuid = CBUUID(string: characteristicUUID)
    if
      let manager = managers[id],
      let characteristic = getCharacteristic(id, serviceUUID, uuid),
      let data = Data(base64Encoded: value)
    {
      let didSend = manager.updateValue(data, for: characteristic, onSubscribedCentrals: nil)
      if didSend == true {
        resolve(nil)
      } else {
        reject("error", "Send notification failed", nil)
      }
    } else {
      reject("error", "Not found characteristic", nil)
    }
  }

  @objc(destroyPeripheral:withResolver:withRejecter:)
  func destroyPeripheral(
    _ id: Int,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) -> Void {
    let manager = managers[id]
    if manager == nil {
      reject("error", "Peripheral id does not exist", nil)
      return
    }
    if manager!.isAdvertising == true {
      manager!.stopAdvertising()
    }
    manager!.removeAllServices()
    managers.removeValue(forKey: id)
    services.removeValue(forKey: id)
    resolve(nil)
  }

  @objc(createCentral:withResolver:withRejecter:)
  func createCentral(
    _ id: Int,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    centralCreateResolves[id] = resolve
    centralCreateRejects[id] = reject
    centralDevices[id] = [:]
    centralManagers[id] = CBCentralManager(delegate: self, queue: nil)
  }

  @objc(startDeviceScan:serviceUUIDs:options:withResolver:withRejecter:)
  func startDeviceScan(
    _ id: Int,
    serviceUUIDs: [String]?,
    options: NSDictionary?,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard let manager = centralManagers[id] else {
      reject("error", "Central id does not exist", nil)
      return
    }
    guard manager.state == .poweredOn else {
      reject("error", "Bluetooth is not powered on", nil)
      return
    }
    let services = serviceUUIDs?.map { CBUUID(string: $0) }
    let allowDuplicates = options?["allowDuplicates"] as? Bool ?? false
    manager.scanForPeripherals(
      withServices: services,
      options: [CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates]
    )
    resolve(nil)
  }

  @objc(stopDeviceScan:withResolver:withRejecter:)
  func stopDeviceScan(
    _ id: Int,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    centralManagers[id]?.stopScan()
    resolve(nil)
  }

  @objc(connectToDevice:deviceID:options:withResolver:withRejecter:)
  func connectToDevice(
    _ id: Int,
    deviceID: String,
    options: NSDictionary?,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard let manager = centralManagers[id] else {
      reject("error", "Central id does not exist", nil)
      return
    }
    let peripheral = centralDevices[id]?[deviceID] ?? UUID(uuidString: deviceID).flatMap {
      manager.retrievePeripherals(withIdentifiers: [$0]).first
    }
    guard let peripheral = peripheral else {
      reject("error", "Unknown Bluetooth device: \(deviceID)", nil)
      return
    }
    centralDevices[id]?[peripheral.identifier.uuidString] = peripheral
    peripheral.delegate = self
    let key = centralKey(id, peripheral.identifier.uuidString)
    centralConnectResolves[key] = resolve
    centralConnectRejects[key] = reject
    manager.connect(peripheral, options: nil)
    let timeout = options?["timeout"] as? Double ?? 30000
    if timeout > 0 {
      DispatchQueue.main.asyncAfter(deadline: .now() + timeout / 1000) { [weak self, weak peripheral] in
        guard
          let self = self,
          let peripheral = peripheral,
          let reject = self.centralConnectRejects.removeValue(forKey: key)
        else { return }
        self.centralConnectResolves.removeValue(forKey: key)
        manager.cancelPeripheralConnection(peripheral)
        reject("error", "Connection to \(deviceID) timed out", nil)
      }
    }
  }

  @objc(cancelDeviceConnection:deviceID:withResolver:withRejecter:)
  func cancelDeviceConnection(
    _ id: Int,
    deviceID: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard let peripheral = centralDevices[id]?[deviceID], let manager = centralManagers[id] else {
      resolve(nil)
      return
    }
    manager.cancelPeripheralConnection(peripheral)
    resolve(nil)
  }

  @objc(discoverAllServicesAndCharacteristics:deviceID:withResolver:withRejecter:)
  func discoverAllServicesAndCharacteristics(
    _ id: Int,
    deviceID: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard let peripheral = centralDevices[id]?[deviceID] else {
      reject("error", "Device is not connected: \(deviceID)", nil)
      return
    }
    guard beginCentralOperation(id, deviceID, operation: "discover", resolve: resolve, reject: reject) else { return }
    peripheral.discoverServices(nil)
  }

  @objc(servicesForDevice:deviceID:withResolver:withRejecter:)
  func servicesForDevice(
    _ id: Int,
    deviceID: String,
    resolve: RCTPromiseResolveBlock,
    reject: RCTPromiseRejectBlock
  ) {
    guard let services = centralDevices[id]?[deviceID]?.services else {
      reject("error", "Services have not been discovered for \(deviceID)", nil)
      return
    }
    resolve(services.map { ["uuid": $0.uuid.uuidString, "isPrimary": $0.isPrimary] })
  }

  @objc(characteristicsForService:deviceID:serviceUUID:withResolver:withRejecter:)
  func characteristicsForService(
    _ id: Int,
    deviceID: String,
    serviceUUID: String,
    resolve: RCTPromiseResolveBlock,
    reject: RCTPromiseRejectBlock
  ) {
    guard
      let services = centralDevices[id]?[deviceID]?.services,
      let service = services.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(serviceUUID) == .orderedSame }),
      let characteristics = service.characteristics
    else {
      reject("error", "Service not found: \(serviceUUID)", nil)
      return
    }
    resolve(characteristics.map { centralCharacteristicInfo($0) })
  }

  @objc(writeCharacteristic:deviceID:serviceUUID:characteristicUUID:value:withResponse:withResolver:withRejecter:)
  func writeCharacteristic(
    _ id: Int,
    deviceID: String,
    serviceUUID: String,
    characteristicUUID: String,
    value: String,
    withResponse: Bool,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard
      let peripheral = centralDevices[id]?[deviceID],
      let characteristic = peripheral.services?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(serviceUUID) == .orderedSame })?.characteristics?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(characteristicUUID) == .orderedSame }),
      let data = Data(base64Encoded: value)
    else {
      reject("error", "Characteristic not found: \(characteristicUUID)", nil)
      return
    }
    if withResponse {
      guard beginCentralOperation(id, deviceID, operation: "write", resolve: resolve, reject: reject) else { return }
    }
    peripheral.writeValue(data, for: characteristic, type: withResponse ? .withResponse : .withoutResponse)
    if !withResponse {
      resolve(centralCharacteristicInfo(characteristic).merging(["value": value]) { _, new in new })
    }
  }

  @objc(readCharacteristic:deviceID:serviceUUID:characteristicUUID:withResolver:withRejecter:)
  func readCharacteristic(
    _ id: Int,
    deviceID: String,
    serviceUUID: String,
    characteristicUUID: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard
      let peripheral = centralDevices[id]?[deviceID],
      let characteristic = peripheral.services?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(serviceUUID) == .orderedSame })?.characteristics?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(characteristicUUID) == .orderedSame })
    else {
      reject("error", "Characteristic not found: \(characteristicUUID)", nil)
      return
    }
    guard beginCentralOperation(id, deviceID, operation: "read", resolve: resolve, reject: reject) else { return }
    peripheral.readValue(for: characteristic)
  }

  @objc(monitorCharacteristic:deviceID:serviceUUID:characteristicUUID:withResolver:withRejecter:)
  func monitorCharacteristic(
    _ id: Int,
    deviceID: String,
    serviceUUID: String,
    characteristicUUID: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard
      let peripheral = centralDevices[id]?[deviceID],
      let characteristic = peripheral.services?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(serviceUUID) == .orderedSame })?.characteristics?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(characteristicUUID) == .orderedSame })
    else {
      reject("error", "Characteristic not found: \(characteristicUUID)", nil)
      return
    }
    guard beginCentralOperation(id, deviceID, operation: "monitor", resolve: resolve, reject: reject) else { return }
    peripheral.setNotifyValue(true, for: characteristic)
  }

  @objc(stopMonitoringCharacteristic:deviceID:serviceUUID:characteristicUUID:withResolver:withRejecter:)
  func stopMonitoringCharacteristic(
    _ id: Int,
    deviceID: String,
    serviceUUID: String,
    characteristicUUID: String,
    resolve: RCTPromiseResolveBlock,
    reject: RCTPromiseRejectBlock
  ) {
    if let peripheral = centralDevices[id]?[deviceID],
       let characteristic = peripheral.services?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(serviceUUID) == .orderedSame })?.characteristics?.first(where: { $0.uuid.uuidString.caseInsensitiveCompare(characteristicUUID) == .orderedSame }) {
      peripheral.setNotifyValue(false, for: characteristic)
    }
    resolve(nil)
  }

  @objc(readRSSI:deviceID:withResolver:withRejecter:)
  func readRSSI(
    _ id: Int,
    deviceID: String,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    guard let peripheral = centralDevices[id]?[deviceID] else {
      reject("error", "Device is not connected: \(deviceID)", nil)
      return
    }
    guard beginCentralOperation(id, deviceID, operation: "rssi", resolve: resolve, reject: reject) else { return }
    peripheral.readRSSI()
  }

  @objc(setLogLevel:withResolver:withRejecter:)
  func setLogLevel(
    _ level: Int,
    resolve: RCTPromiseResolveBlock,
    reject: RCTPromiseRejectBlock
  ) {
    resolve(nil)
  }

  @objc(destroyCentral:withResolver:withRejecter:)
  func destroyCentral(
    _ id: Int,
    resolve: RCTPromiseResolveBlock,
    reject: RCTPromiseRejectBlock
  ) {
    centralManagers[id]?.stopScan()
    centralDevices[id]?.values.forEach { centralManagers[id]?.cancelPeripheralConnection($0) }
    centralManagers.removeValue(forKey: id)
    centralDevices.removeValue(forKey: id)
    centralConnectResolves.keys.filter { $0.hasPrefix("\(id):") }.forEach { centralConnectResolves.removeValue(forKey: $0) }
    centralConnectRejects.keys.filter { $0.hasPrefix("\(id):") }.forEach {
      centralConnectRejects.removeValue(forKey: $0)?("error", "Central destroyed", nil)
    }
    centralOperationNames.keys.filter { $0.hasPrefix("\(id):") }.forEach { centralOperationNames.removeValue(forKey: $0) }
    centralOperationResolves.keys.filter { $0.hasPrefix("\(id):") }.forEach { centralOperationResolves.removeValue(forKey: $0) }
    centralOperationRejects.keys.filter { $0.hasPrefix("\(id):") }.forEach {
      centralOperationRejects.removeValue(forKey: $0)?("error", "Central destroyed", nil)
    }
    centralCreateResolves.removeValue(forKey: id)
    centralCreateRejects.removeValue(forKey: id)
    resolve(nil)
  }

  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    guard let id = centralManagers.someKey(forValue: central) else { return }
    switch central.state {
    case .poweredOn:
      centralCreateResolves.removeValue(forKey: id)?(nil)
      centralCreateRejects.removeValue(forKey: id)
    case .poweredOff:
      centralCreateResolves.removeValue(forKey: id)?(nil)
      centralCreateRejects.removeValue(forKey: id)
      sendEvent(withName: "onCentralError", body: ["id": id, "error": "Bluetooth is powered off"])
    case .unauthorized, .unsupported:
      let error = "Central Bluetooth state is \(central.state.rawValue)"
      centralCreateRejects.removeValue(forKey: id)?("error", error, nil)
      centralCreateResolves.removeValue(forKey: id)
      sendEvent(withName: "onCentralError", body: ["id": id, "error": error])
    case .resetting, .unknown:
      break
    @unknown default:
      break
    }
  }

  func centralManager(
    _ central: CBCentralManager,
    didDiscover peripheral: CBPeripheral,
    advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) {
    guard let id = centralManagers.someKey(forValue: central) else { return }
    centralDevices[id]?[peripheral.identifier.uuidString] = peripheral
    sendEvent(withName: "onDeviceFound", body: [
      "id": id,
      "device": centralDeviceInfo(peripheral, rssi: RSSI.intValue, advertisementData: advertisementData),
    ])
  }

  func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
    guard let id = centralID(for: peripheral) else { return }
    centralConnectRejects.removeValue(forKey: centralKey(id, peripheral.identifier.uuidString))
    centralConnectResolves.removeValue(forKey: centralKey(id, peripheral.identifier.uuidString))?(centralDeviceInfo(peripheral))
    sendEvent(withName: "onConnect", body: [
      "id": id,
      "deviceId": peripheral.identifier.uuidString,
      "device": centralDeviceInfo(peripheral),
    ])
  }

  func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
    guard let id = centralID(for: peripheral) else { return }
    let key = centralKey(id, peripheral.identifier.uuidString)
    centralConnectResolves.removeValue(forKey: key)
    centralConnectRejects.removeValue(forKey: key)?("error", error?.localizedDescription ?? "Connection failed", error)
  }

  func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
    guard let id = centralID(for: peripheral) else { return }
    let key = centralKey(id, peripheral.identifier.uuidString)
    centralConnectResolves.removeValue(forKey: key)
    centralConnectRejects.removeValue(forKey: key)?(
      "error",
      error?.localizedDescription ?? "Device disconnected before connecting",
      error
    )
    centralOperationResolves.removeValue(forKey: key)
    centralOperationRejects.removeValue(forKey: key)?("error", "Device disconnected", error)
    centralOperationNames.removeValue(forKey: key)
    sendEvent(withName: "onDisconnect", body: [
      "id": id,
      "deviceId": peripheral.identifier.uuidString,
      "error": error?.localizedDescription as Any? ?? NSNull(),
    ])
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
    if let error = error {
      finishCentralOperation(peripheral, operation: "discover", error: error)
      return
    }
    let services = peripheral.services ?? []
    let key = centralKey(centralID(for: peripheral) ?? -1, peripheral.identifier.uuidString)
    centralDiscoveryRemaining[key] = services.count
    if services.isEmpty {
      finishCentralOperation(peripheral, operation: "discover", error: nil)
      return
    }
    services.forEach { peripheral.discoverCharacteristics(nil, for: $0) }
  }

  func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
    if let error = error {
      finishCentralOperation(peripheral, operation: "discover", error: error)
      return
    }
    let key = centralKey(centralID(for: peripheral) ?? -1, peripheral.identifier.uuidString)
    let remaining = (centralDiscoveryRemaining[key] ?? 1) - 1
    centralDiscoveryRemaining[key] = remaining
    if remaining == 0 {
      centralDiscoveryRemaining.removeValue(forKey: key)
      finishCentralOperation(peripheral, operation: "discover", error: nil)
    }
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
    guard let id = centralID(for: peripheral) else { return }
    if centralOperationNames[centralKey(id, peripheral.identifier.uuidString)] == "read" {
      finishCentralOperation(peripheral, operation: "read", error: error, value: centralCharacteristicInfo(characteristic))
      return
    }
    guard let serviceUUID = characteristic.service?.uuid.uuidString else { return }
    sendEvent(withName: "onCharacteristicValueChanged", body: [
      "id": id,
      "deviceId": peripheral.identifier.uuidString,
      "serviceUUID": serviceUUID,
      "characteristicUUID": characteristic.uuid.uuidString,
      "value": characteristic.value?.base64EncodedString() ?? "",
    ])
  }

  func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
    finishCentralOperation(peripheral, operation: "write", error: error, value: centralCharacteristicInfo(characteristic))
  }

  func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
    finishCentralOperation(peripheral, operation: "monitor", error: error)
  }

  func peripheral(_ peripheral: CBPeripheral, didReadRSSI RSSI: NSNumber, error: Error?) {
    finishCentralOperation(peripheral, operation: "rssi", error: error, value: RSSI)
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveRead request: CBATTRequest
  ) {
    guard let value = request.characteristic.value, request.offset <= value.count else {
      peripheral.respond(to: request, withResult: .invalidOffset)
      return
    }
    request.value = value.subdata(in: request.offset..<value.count)
    peripheral.respond(to: request, withResult: .success)
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didReceiveWrite requests: [CBATTRequest]
  ) {
    let id = managers.someKey(forValue: peripheral)
    for request in requests {
      let characteristic = request.characteristic
      let characteristicUUID = characteristic.uuid.uuidString
      let serviceUUID = characteristic.service?.uuid.uuidString
      let base64 = request.value?.base64EncodedString()
      sendEvent(withName: "onWrite", body: [
        "id": id,
        "serviceUUID": serviceUUID,
        "characteristicUUID": characteristicUUID,
        "value": base64,
      ])
    }
  }

  func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
    if let id = managers.someKey(forValue: peripheral) {
      let state = peripheral.state
      switch state {
      case .poweredOn:
        createResolves[id]?(nil)
        createResolves.removeValue(forKey: id)
        createRejects.removeValue(forKey: id)
      case .poweredOff:
        createRejects[id]?("error", "Peripheral is powered off", nil)
        createRejects.removeValue(forKey: id)
      case .resetting:
        createRejects[id]?("error", "Peripheral is resetting", nil)
        createRejects.removeValue(forKey: id)
      case .unauthorized:
        createRejects[id]?("error", "Peripheral is unauthorized", nil)
        createRejects.removeValue(forKey: id)
      case .unsupported:
        createRejects[id]?("error", "Peripheral is unsupported", nil)
        createRejects.removeValue(forKey: id)
      case .unknown:
        createRejects[id]?("error", "Peripheral state is unknown", nil)
        createRejects.removeValue(forKey: id)
      }
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    central: CBCentral,
    didSubscribeTo characteristic: CBCharacteristic
  ) {
    let id = managers.someKey(forValue: peripheral)
    let characteristicUUID = characteristic.uuid.uuidString
    let serviceUUID = characteristic.service?.uuid.uuidString
    sendEvent(withName: "onSubscribe", body: [
      "id": id,
      "serviceUUID": serviceUUID,
      "characteristicUUID": characteristicUUID,
    ])
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    central: CBCentral,
    didUnsubscribeFrom characteristic: CBCharacteristic
  ) {
    let id = managers.someKey(forValue: peripheral)
    let characteristicUUID = characteristic.uuid.uuidString
    let serviceUUID = characteristic.service?.uuid.uuidString
    sendEvent(withName: "onUnsubscribe", body: [
      "id": id,
      "serviceUUID": serviceUUID,
      "characteristicUUID": characteristicUUID,
    ])
  }

  func peripheralManagerDidStartAdvertising(
    _ peripheral: CBPeripheralManager,
    error: Error?
  ) {
    if
      let id = managers.someKey(forValue: peripheral),
      let resolve = startResolves[id],
      let reject = startRejects[id]
    {
      if error != nil {
        reject("error", "Fail to start: \(error)", error)
      } else {
        resolve(nil)
      }
    } else {
      print("Not found ID or promise")
    }
  }

  func peripheralManager(
    _ peripheral: CBPeripheralManager,
    didAdd service: CBService,
    error: Error?
  ) {
    if let id = managers.someKey(forValue: peripheral) {
      print("id: \(id), service: \(service), error: \(error)")
    }
  }

  func peripheralManagerDidStopAdvertising(
    _ peripheral: CBPeripheralManager,
    error: Error?
  ) {
    if let id = managers.someKey(forValue: peripheral) {
      print("id: \(id), error: \(error)")
    }
  }

}
