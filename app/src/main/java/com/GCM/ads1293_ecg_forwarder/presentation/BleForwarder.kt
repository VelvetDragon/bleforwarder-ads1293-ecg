package com.gcm.ads1293_ecg_forwarder.presentation

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid // For ScanFilter
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.UUID

class BleForwarder(
    private val ctx: Context,
    private val statusUpdate: (String) -> Unit
) {
    companion object {
        val ECG_SERVICE: UUID = UUID.fromString("00002d0d-0000-1000-8000-00805f9b34fb")
        val ECG_CHAR: UUID    = UUID.fromString("00002d37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleForwarder_ECG"
    }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private val http = OkHttpClient()
    private var isBusy = false // Flag to manage operation state

    private fun resetOperationState(reason: String) {
        Log.d(TAG, "Resetting operation state. Reason: $reason. Current isBusy = $isBusy")
        isBusy = false
    }

    fun start() {
        Log.d(TAG, "start() called. Current isBusy = $isBusy")
        if (isBusy) {
            Log.w(TAG, "Operation already in progress. Ignoring start() call.")
            // Optionally, inform the user: statusUpdate("Operation in progress...")
            return
        }
        isBusy = true // Set busy flag
        Log.d(TAG, "Set isBusy = true")

        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) {
            Log.e(TAG, "No Bluetooth adapter found.")
            resetOperationState("No Bluetooth adapter")
            statusUpdate("No Bluetooth adapter")
            return
        }
        Log.d(TAG, "Bluetooth adapter obtained.")

        if (!btAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is off.")
            resetOperationState("Bluetooth is off")
            statusUpdate("Bluetooth is off")
            return
        }
        Log.d(TAG, "Bluetooth is enabled.")

        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission.")
            resetOperationState("Missing BLUETOOTH_SCAN permission")
            statusUpdate("Missing BLUETOOTH_SCAN permission")
            return
        }
        Log.d(TAG, "BLUETOOTH_SCAN permission is granted.")

        statusUpdate("Scanning for ECG device…")
        Log.d(TAG, "Status set to Scanning...")
        scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null. Cannot start scan.")
            resetOperationState("Failed to get LE Scanner")
            statusUpdate("Failed to get LE Scanner")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(ECG_SERVICE))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d(TAG, "Starting BLE scan...")
        try {
            scanner!!.startScan(listOf(scanFilter), scanSettings, scanCb)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on startScan: ${e.message}", e)
            resetOperationState("Scan SecurityException")
            statusUpdate("Scan Error (Permission)")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on startScan: ${e.message}", e)
            resetOperationState("Scan IllegalStateException")
            statusUpdate("Scan Error (State): ${e.message}") // Give more specific error
        } catch (e: Exception) {
            Log.e(TAG, "Generic Exception on startScan: ${e.message}", e)
            resetOperationState("Scan Generic Exception")
            statusUpdate("Scan Error (Generic)")
        }
    }

    fun stop() {
        Log.d(TAG, "stop() called. Current isBusy = $isBusy")
        // Attempt to stop scan
        try {
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
                scanner?.stopScan(scanCb) // Use the same callback instance
                Log.d(TAG, "Scan stop initiated via stop().")
            } else {
                Log.w(TAG, "BLUETOOTH_SCAN permission missing for stopScan in stop().")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping scan in stop(): ${e.message}", e)
        }
        scanner = null // Nullify scanner immediately after attempting to stop

        // Attempt to disconnect and close GATT
        try {
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
                gatt?.disconnect()
                Log.d(TAG, "GATT disconnect initiated.")
                gatt?.close()
                Log.d(TAG, "GATT close initiated.")
            } else {
                Log.w(TAG, "BLUETOOTH_CONNECT permission missing for GATT operations in stop().")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during GATT disconnect/close in stop(): ${e.message}", e)
        }
        gatt = null // Nullify gatt immediately

        resetOperationState("stop() called")
        statusUpdate("Stopped")
        Log.d(TAG, "Scanner and GATT nulled. Status set to Stopped.")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceAddress = result.device?.address ?: "Unknown Device"
            Log.d(TAG, "onScanResult: Found device ${deviceAddress} with RSSI ${result.rssi}. Current isBusy = $isBusy")

            if (!isBusy) { // Check if we should still be processing this scan result
                Log.w(TAG, "onScanResult received but operation is no longer busy (e.g., stop() was called). Ignoring.")
                return
            }

            // Stop scanning
            try {
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED) {
                    scanner?.stopScan(this)
                    Log.d(TAG, "Scan stopped after finding device: $deviceAddress")
                } else {
                    Log.w(TAG, "BLUETOOTH_SCAN permission missing, cannot stop scan in onScanResult.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping scan in onScanResult: ${e.message}", e)
            }
            // scanner = null; // Don't nullify here, stop() handles it, or a new startScan will re-init

            statusUpdate("Connecting to $deviceAddress…")
            Log.d(TAG, "Status set to Connecting... to $deviceAddress")

            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission before connectGatt to $deviceAddress")
                resetOperationState("Missing BLUETOOTH_CONNECT for connectGatt")
                statusUpdate("Missing BLUETOOTH_CONNECT")
                return
            }
            Log.d(TAG, "BLUETOOTH_CONNECT permission granted for connecting to $deviceAddress.")

            try {
                Log.d(TAG, "Attempting to connect GATT to device: $deviceAddress")
                if (gatt != null) {
                    Log.w(TAG, "Existing GATT object found before connectGatt. Closing it first.")
                    // Permission for close should be fine if we have CONNECT for connectGatt
                    gatt?.close()
                    gatt = null
                }
                gatt = result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    Log.e(TAG, "connectGatt returned null for device $deviceAddress.")
                    resetOperationState("connectGatt returned null")
                    statusUpdate("Failed to initiate GATT connection to $deviceAddress")
                } else {
                    Log.d(TAG, "connectGatt call successful for $deviceAddress, awaiting callback.")
                    // isBusy remains true, waiting for onConnectionStateChange
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on connectGatt to $deviceAddress: ${e.message}", e)
                resetOperationState("connectGatt SecurityException")
                statusUpdate("Connect Error (Permission)")
            } catch (e: Exception) {
                Log.e(TAG, "Generic exception on connectGatt to $deviceAddress: ${e.message}", e)
                resetOperationState("connectGatt Generic Exception")
                statusUpdate("Connect Error (Generic)")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: errorCode=$errorCode. Current isBusy = $isBusy")
            var errorMessage = "Scan failed: "
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> errorMessage += "Already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> errorMessage += "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> errorMessage += "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> errorMessage += "Feature unsupported"
                else -> errorMessage += "Unknown error code $errorCode"
            }
            resetOperationState("onScanFailed errorCode=$errorCode")
            statusUpdate(errorMessage)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = g.device?.address ?: "Unknown Device"
            Log.d(TAG, "onConnectionStateChange for $deviceAddress: GATT Status=$status, New State=$newState. Current isBusy = $isBusy")

            if (!isBusy && newState != BluetoothProfile.STATE_DISCONNECTED) {
                // If not busy and not a final disconnect event, we might be getting a late callback.
                // Typically, we'd only care if it's a disconnect that needs cleanup.
                Log.w(TAG, "onConnectionStateChange received while not busy. State: $newState. Ignoring unless it's a disconnect.")
                // If it's a disconnect event, we might still want to ensure gatt is closed and nulled
                // But primary control of isBusy should be handled by stop() or other error paths.
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Late disconnect event for $deviceAddress. Ensuring GATT is closed.")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        g.close()
                    }
                    if (this@BleForwarder.gatt == g) { // Only nullify if it's the current gatt instance
                        this@BleForwarder.gatt = null
                    }
                }
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Successfully connected to $deviceAddress.")
                    statusUpdate("Discovering services on $deviceAddress…")
                    // this@BleForwarder.gatt = g // Already assigned from connectGatt
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "Missing BLUETOOTH_CONNECT for discoverServices on $deviceAddress")
                        resetOperationState("Missing BLUETOOTH_CONNECT for discoverServices")
                        statusUpdate("Missing BLUETOOTH_CONNECT for discover")
                        g.close() // Permission for close should be fine here as it's an error path
                        this@BleForwarder.gatt = null
                        return
                    }
                    Log.d(TAG, "Attempting to discover services for $deviceAddress")
                    val discoveryInitiated = g.discoverServices()
                    if (!discoveryInitiated) {
                        Log.e(TAG, "discoverServices() failed to initiate for $deviceAddress")
                        resetOperationState("discoverServices failed to initiate")
                        statusUpdate("Failed to start service discovery on $deviceAddress")
                        g.close()
                        this@BleForwarder.gatt = null
                    } else {
                        Log.d(TAG, "Service discovery initiated for $deviceAddress.")
                        // isBusy remains true
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from $deviceAddress (GATT_SUCCESS path).")
                    resetOperationState("Disconnected (GATT_SUCCESS)")
                    statusUpdate("Disconnected from $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        g.close()
                    }
                    this@BleForwarder.gatt = null
                }
            } else { // GATT operation error (status != BluetoothGatt.GATT_SUCCESS)
                Log.e(TAG, "GATT Error on connection state change for $deviceAddress: status=$status. New state=$newState")
                resetOperationState("GATT Error status=$status")
                statusUpdate("GATT Error: $status on $deviceAddress")
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    g.close()
                }
                this@BleForwarder.gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val deviceAddress = g.device?.address ?: "Unknown Device"
            Log.d(TAG, "onServicesDiscovered for $deviceAddress: status=$status. Current isBusy = $isBusy")

            if (!isBusy) {
                Log.w(TAG, "onServicesDiscovered received but operation is no longer busy. Ignoring.")
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = g.getService(ECG_SERVICE)
                if (service == null) {
                    Log.e(TAG, "ECG service ($ECG_SERVICE) not found on $deviceAddress")
                    resetOperationState("ECG service not found")
                    statusUpdate("ECG service not found on $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { g.close() }
                    this@BleForwarder.gatt = null
                    return
                }
                Log.i(TAG, "ECG service found on $deviceAddress.")

                val characteristic = service.getCharacteristic(ECG_CHAR)
                if (characteristic == null) {
                    Log.e(TAG, "ECG characteristic ($ECG_CHAR) not found on $deviceAddress")
                    resetOperationState("ECG characteristic not found")
                    statusUpdate("ECG char not found on $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { g.close() }
                    this@BleForwarder.gatt = null
                    return
                }
                Log.i(TAG, "ECG characteristic found on $deviceAddress.")

                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT for enabling notifications on $deviceAddress")
                    resetOperationState("Missing BLUETOOTH_CONNECT for notifications")
                    statusUpdate("Missing BLUETOOTH_CONNECT for notif")
                    g.close() // Already checked permission for this specific close
                    this@BleForwarder.gatt = null
                    return
                }

                Log.d(TAG, "Attempting to enable notifications for char on $deviceAddress.")
                if (!g.setCharacteristicNotification(characteristic, true)) {
                    Log.e(TAG, "setCharacteristicNotification failed for $deviceAddress")
                    resetOperationState("setCharacteristicNotification failed")
                    statusUpdate("Failed to enable notifications on $deviceAddress")
                    g.close()
                    this@BleForwarder.gatt = null
                    return
                }
                Log.d(TAG, "setCharacteristicNotification success for $deviceAddress.")

                val cccdDescriptor = characteristic.getDescriptor(CCCD)
                if (cccdDescriptor == null) {
                    Log.w(TAG, "CCCD ($CCCD) not found for char on $deviceAddress. Assuming notifications enabled.")
                    statusUpdate("Streaming (CCCD missing) from $deviceAddress…")
                    // isBusy remains true as we assume streaming
                } else {
                    Log.d(TAG, "CCCD found. Writing ENABLE_NOTIFICATION_VALUE for $deviceAddress.")
                    cccdDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!g.writeDescriptor(cccdDescriptor)) {
                        Log.e(TAG, "writeDescriptor for CCCD failed to initiate for $deviceAddress")
                        resetOperationState("writeDescriptor for CCCD failed")
                        statusUpdate("Failed to write CCCD on $deviceAddress")
                        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { g.close() }
                        this@BleForwarder.gatt = null
                        return
                    }
                    Log.d(TAG, "writeDescriptor for CCCD initiated for $deviceAddress.")
                    // isBusy remains true, waiting for onDescriptorWrite
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received error $status for $deviceAddress")
                resetOperationState("onServicesDiscovered error status=$status")
                statusUpdate("Service discovery failed: $status on $deviceAddress")
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { g.close() }
                this@BleForwarder.gatt = null
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(g, descriptor, status)
            val deviceAddress = g.device?.address ?: "Unknown device"
            Log.d(TAG, "onDescriptorWrite for $deviceAddress: UUID=${descriptor.uuid}, Status=$status. Current isBusy = $isBusy")

            if (!isBusy) {
                Log.w(TAG, "onDescriptorWrite received but operation is no longer busy. Ignoring.")
                return
            }

            if (descriptor.uuid == CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CCCD ($CCCD) write successful for $deviceAddress. Notifications fully enabled.")
                    statusUpdate("Streaming from $deviceAddress…")
                    // isBusy remains true as we are now successfully streaming
                } else {
                    Log.e(TAG, "CCCD write failed for $deviceAddress with status: $status")
                    resetOperationState("CCCD write failed status=$status")
                    statusUpdate("Failed to enable notifications (CCCD write error $status) on $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) { g.close() }
                    this@BleForwarder.gatt = null
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(g, characteristic)
            val deviceAddress = g.device?.address ?: "Unknown device"
            val charUuid = characteristic.uuid

            if (!isBusy) { // Though streaming implies isBusy should be true
                Log.w(TAG, "onCharacteristicChanged received but not busy. UUID=$charUuid. Ignoring.")
                return
            }

            if (charUuid == ECG_CHAR) {
                val bytes = characteristic.value
                if (bytes != null && bytes.isNotEmpty()) {
                    Log.v(TAG, "ECG Data from $deviceAddress: ${bytes.size} bytes. First byte: ${bytes[0]}")
                    forwardToServer(deviceAddress, bytes)
                } else {
                    Log.w(TAG, "ECG characteristic ($ECG_CHAR) changed but value is null or empty for $deviceAddress.")
                }
            }
        }
    }

    private fun forwardToServer(mac: String, data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Forwarding ${data.size} bytes from $mac to server.")
                val reqBody = RequestBody.create("application/octet-stream".toMediaType(), data)
                val req = Request.Builder()
                    .url("https://YOUR.GUI.SERVER/ingest") // <-- MAKE SURE THIS IS YOUR ACTUAL SERVER URL
                    .header("X-Device-MAC", mac)
                    .post(reqBody)
                    .build()

                http.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "Data successfully forwarded for $mac. Server response: ${response.code}")
                    } else {
                        Log.e(TAG, "Failed to forward data for $mac. Server error: ${response.code} ${response.message}")
                        Log.e(TAG, "Server response body: ${response.body?.string()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP error forwarding data for $mac: ${e.message}", e)
                // statusUpdate("HTTP error: ${e.message}") // Avoid frequent UI updates from background thread
            }
        }
    }
}