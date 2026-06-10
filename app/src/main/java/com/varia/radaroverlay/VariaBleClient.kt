package com.varia.radaroverlay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
class VariaBleClient(private val context: Context) {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onRadarStateUpdated(state: RadarState)
        fun onDeviceFound(device: BluetoothDevice)
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGatt: BluetoothGatt? = null
    private val decoder = RadarV1Decoder()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    var connectionState = ConnectionState.DISCONNECTED
        private set(value) {
            field = value
            listener?.onConnectionStateChanged(value)
        }

    var listener: Listener? = null
    private var isScanning = false

    // BLE Service and Characteristic UUIDs from specification
    companion object {
        private const val TAG = "VariaBleClient"
        
        // Varia Radar Service
        val RADAR_SERVICE_UUID: UUID = UUID.fromString("6a4e3200-667b-11e3-949a-0800200c9a66")
        
        // Varia V1 Radar Measurement Characteristic
        val RADAR_CHAR_UUID: UUID = UUID.fromString("6a4e3203-667b-11e3-949a-0800200c9a66")
        
        // Standard SIG UUID for Garmin (fe1f) in 128-bit form
        val GARMIN_SIG_UUID: UUID = UUID.fromString("0000fe1f-0000-1000-8000-00805f9b34fb")
        
        // Standard Battery Service & Characteristic
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        
        // Client Characteristic Configuration Descriptor (CCCD)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    // Scan Callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = device.name
            Log.d(TAG, "Device found: ${device.address} (${deviceName ?: "Unknown"})")
            
            // Filter: Name contains Varia or device advertises Garmin SIG UUID
            val isVaria = (deviceName != null && deviceName.contains("Varia", ignoreCase = true)) ||
                    (result.scanRecord?.serviceUuids?.contains(ParcelUuid(GARMIN_SIG_UUID)) == true) ||
                    (result.scanRecord?.serviceUuids?.contains(ParcelUuid(RADAR_SERVICE_UUID)) == true)

            if (isVaria) {
                mainHandler.post {
                    listener?.onDeviceFound(device)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            isScanning = false
        }
    }

    fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not enabled or not supported")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        if (isScanning) return

        isScanning = true
        Log.d(TAG, "Starting BLE scan...")

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(GARMIN_SIG_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(RADAR_SERVICE_UUID)).build()
        )
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)
    }

    fun stopScanning() {
        if (!isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        Log.d(TAG, "Stopping BLE scan...")
        scanner.stopScan(scanCallback)
        isScanning = false
    }

    fun connect(macAddress: String) {
        if (bluetoothAdapter == null) return
        
        // If already connecting or connected, disconnect first
        disconnect()

        val device = bluetoothAdapter.getRemoteDevice(macAddress)
        Log.d(TAG, "Connecting to Varia at $macAddress")
        connectionState = ConnectionState.CONNECTING
        
        decoder.reset()

        // Connect GATT
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            Log.d(TAG, "Disconnecting from GATT")
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        connectionState = ConnectionState.DISCONNECTED
        decoder.reset()
        listener?.onRadarStateUpdated(RadarState(threatLevel = ThreatLevel.OFFLINE))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT Status error: $status, disconnecting...")
                mainHandler.post { disconnect() }
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "GATT Connected. Discovering services...")
                mainHandler.post {
                    connectionState = ConnectionState.CONNECTED
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "GATT Disconnected")
                mainHandler.post {
                    disconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }

            Log.d(TAG, "Services discovered successfully!")

            // 1. Find and subscribe to the Radar Service characteristic (V1)
            val radarService = gatt.getService(RADAR_SERVICE_UUID)
            if (radarService != null) {
                val radarChar = radarService.getCharacteristic(RADAR_CHAR_UUID)
                if (radarChar != null) {
                    subscribeToNotifications(gatt, radarChar)
                    Log.d(TAG, "Subscribed to Varia Radar notifications")
                } else {
                    Log.e(TAG, "Radar characteristic not found!")
                }
            } else {
                Log.e(TAG, "Radar service not found!")
            }

            // 2. Find and subscribe to the Battery Service
            val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
            if (batteryService != null) {
                val batteryChar = batteryService.getCharacteristic(BATTERY_CHAR_UUID)
                if (batteryChar != null) {
                    // Read battery value once initial
                    gatt.readCharacteristic(batteryChar)
                    
                    // Also subscribe to notifications for battery updates
                    subscribeToNotifications(gatt, batteryChar)
                    Log.d(TAG, "Subscribed to Battery notifications")
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicData(characteristic)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicData(characteristic)
        }
    }

    private fun subscribeToNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun handleCharacteristicData(characteristic: BluetoothGattCharacteristic) {
        val data = characteristic.value ?: return
        
        when (characteristic.uuid) {
            RADAR_CHAR_UUID -> {
                val state = decoder.feed(data)
                if (state != null) {
                    mainHandler.post {
                        listener?.onRadarStateUpdated(state)
                    }
                }
            }
            BATTERY_CHAR_UUID -> {
                if (data.isNotEmpty()) {
                    val batteryVal = data[0].toInt() and 0xFF
                    Log.d(TAG, "Battery level update: $batteryVal%")
                    val state = decoder.updateBattery(batteryVal)
                    mainHandler.post {
                        listener?.onRadarStateUpdated(state)
                    }
                }
            }
        }
    }
}
