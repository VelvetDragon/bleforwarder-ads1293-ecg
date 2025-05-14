package com.gcm.ads1293_ecg_forwarder.presentation

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid // For ScanFilter
import android.util.Log // <-- ADD THIS IMPORT
import androidx.core.app.ActivityCompat // <-- ADD THIS IMPORT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.UUID // For direct UUID creation

class BleForwarder(
    private val ctx: Context,
    private val statusUpdate: (String) -> Unit // Renamed for clarity
) {
    companion object {
        val ECG_SERVICE: UUID = UUID.fromString("00002d0d-0000-1000-8000-00805f9b34fb")
        val ECG_CHAR: UUID    = UUID.fromString("00002d37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleForwarder_ECG" // Unique tag
    }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private val http = OkHttpClient()

    fun start() {
        Log.d(TAG, "start() called")
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) {
            Log.e(TAG, "No Bluetooth adapter found.")
            return statusUpdate("No Bluetooth adapter")
        }
        Log.d(TAG, "Bluetooth adapter obtained.")

        if (!btAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is off.")
            return statusUpdate("Bluetooth is off")
        }
        Log.d(TAG, "Bluetooth is enabled.")

        // --- BLUETOOTH_SCAN Permission Check ---
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission.")
            return statusUpdate("Missing BLUETOOTH_SCAN permission")
        }
        Log.d(TAG, "BLUETOOTH_SCAN permission is granted.")

        statusUpdate("Scanning for ECG device…")
        Log.d(TAG, "Status set to Scanning...")
        scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "BluetoothLeScanner is null. Cannot start scan.")
            return statusUpdate("Failed to get LE Scanner")
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(ECG_SERVICE))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        Log.d(TAG, "Starting BLE scan...")
        try {
            // Ensure scanner is not null again, though checked above.
            scanner!!.startScan(listOf(scanFilter), scanSettings, scanCb)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on startScan: ${e.message}", e)
            statusUpdate("Scan Error (Permission)")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on startScan (possibly already scanning or BT off): ${e.message}", e)
            statusUpdate("Scan Error (State)")
        } catch (e: Exception) {
            Log.e(TAG, "Generic Exception on startScan: ${e.message}", e)
            statusUpdate("Scan Error (Generic)")
        }
    }

    fun stop() {
        Log.d(TAG, "stop() called")
        try {
            // BLUETOOTH_SCAN might be needed for stopScan on some older APIs if permissions were dynamic
            // but generally, if startScan was successful, stopScan should also be.
            // However, good to have a check if issues arise.
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
                scanner?.stopScan(scanCb)
                Log.d(TAG, "Scan stopped via stop().")
            } else {
                Log.w(TAG, "BLUETOOTH_SCAN permission missing, cannot formally stop scan. Scanner will be nulled.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception stopping scan: ${e.message}", e)
        }

        try {
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED) {
                gatt?.disconnect() // Good practice to disconnect first
                gatt?.close()
                Log.d(TAG, "GATT disconnected and closed.")
            } else {
                Log.w(TAG, "BLUETOOTH_CONNECT permission missing, cannot formally close GATT. It will be nulled.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception closing GATT: ${e.message}", e)
        }

        scanner = null
        gatt = null
        statusUpdate("Stopped")
        Log.d(TAG, "Scanner and GATT nulled. Status set to Stopped.")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceAddress = result.device?.address ?: "Unknown Device"
            Log.d(TAG, "onScanResult: Found device ${deviceAddress} with RSSI ${result.rssi}")

            // Stop scanning
            try {
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED) {
                    scanner?.stopScan(this) // 'this' refers to the ScanCallback instance
                    Log.d(TAG, "Scan stopped after finding device: $deviceAddress")
                } else {
                    Log.w(TAG, "BLUETOOTH_SCAN permission missing, cannot stop scan in onScanResult.")
                    // Not returning, will attempt connect anyway, but scan might continue in background.
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception stopping scan in onScanResult: ${e.message}", e)
                // Not returning, will attempt connect anyway
            }


            statusUpdate("Connecting to $deviceAddress…")
            Log.d(TAG, "Status set to Connecting... to $deviceAddress")

            // --- BLUETOOTH_CONNECT Permission Check ---
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission before connectGatt to $deviceAddress")
                statusUpdate("Missing BLUETOOTH_CONNECT")
                return
            }
            Log.d(TAG, "BLUETOOTH_CONNECT permission granted for connecting to $deviceAddress.")

            try {
                Log.d(TAG, "Attempting to connect GATT to device: $deviceAddress")
                // Ensure gatt is null before new connection to avoid "already connected" issues if not cleaned up properly.
                if (gatt != null) {
                    Log.w(TAG, "Existing GATT connection found, closing it before new attempt.")
                    gatt?.close()
                    gatt = null
                }
                gatt = result.device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    Log.e(TAG, "connectGatt returned null for device $deviceAddress.")
                    statusUpdate("Failed to initiate GATT connection to $deviceAddress")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on connectGatt to $deviceAddress: ${e.message}", e)
                statusUpdate("Connect Error (Permission)")
            } catch (e: Exception) {
                Log.e(TAG, "Generic exception on connectGatt to $deviceAddress: ${e.message}", e)
                statusUpdate("Connect Error (Generic)")
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults: ${results?.size ?: 0} results")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: errorCode=$errorCode")
            var errorMessage = "Scan failed: "
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> errorMessage += "Already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> errorMessage += "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> errorMessage += "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> errorMessage += "Feature unsupported"
                // Add API 31+ error codes if needed, though targetSdk is 34 they are relevant
                // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
                // SCAN_FAILED_SCANNING_TOO_FREQUENTLY
                else -> errorMessage += "Unknown error code $errorCode"
            }
            statusUpdate(errorMessage)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = g.device?.address ?: "Unknown Device"
            Log.d(TAG, "onConnectionStateChange for $deviceAddress: GATT Status=$status, New State=$newState")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Successfully connected to $deviceAddress.")
                    statusUpdate("Discovering services on $deviceAddress…")
                    // Assign gatt here after successful connection
                    // this@BleForwarder.gatt = g // Already assigned from connectGatt return

                    // --- BLUETOOTH_CONNECT Permission Check ---
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.e(TAG, "Missing BLUETOOTH_CONNECT for discoverServices on $deviceAddress")
                        statusUpdate("Missing BLUETOOTH_CONNECT for discover")
                        g.close() // Close connection if can't proceed
                        this@BleForwarder.gatt = null
                        return
                    }
                    Log.d(TAG, "Attempting to discover services for $deviceAddress")
                    val discoveryInitiated = g.discoverServices()
                    if (!discoveryInitiated) {
                        Log.e(TAG, "discoverServices() failed to initiate for $deviceAddress")
                        statusUpdate("Failed to start service discovery on $deviceAddress")
                        g.close()
                        this@BleForwarder.gatt = null
                    } else {
                        Log.d(TAG, "Service discovery initiated for $deviceAddress.")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from $deviceAddress.")
                    statusUpdate("Disconnected from $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        g.close()
                        Log.d(TAG, "GATT closed for $deviceAddress after disconnect.")
                    } else {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT, cannot close GATT for $deviceAddress after disconnect.")
                    }
                    this@BleForwarder.gatt = null // Clear the gatt instance
                }
            } else {
                // Error occurred during connection state change
                Log.e(TAG, "GATT Error on connection state change for $deviceAddress: status=$status")
                statusUpdate("GATT Error: $status on $deviceAddress")
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    g.close()
                    Log.d(TAG, "GATT closed for $deviceAddress due to error.")
                } else {
                    Log.w(TAG, "Missing BLUETOOTH_CONNECT, cannot close GATT for $deviceAddress due to error.")
                }
                this@BleForwarder.gatt = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val deviceAddress = g.device?.address ?: "Unknown Device"
            Log.d(TAG, "onServicesDiscovered for $deviceAddress: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = g.getService(ECG_SERVICE)
                if (service == null) {
                    Log.e(TAG, "ECG service ($ECG_SERVICE) not found on $deviceAddress")
                    statusUpdate("ECG service not found on $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        g.close()
                        Log.d(TAG, "GATT closed for $deviceAddress, service not found.")
                    } else {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT, cannot close GATT for $deviceAddress, service not found.")
                    }
                    this@BleForwarder.gatt = null
                    return
                }
                Log.i(TAG, "ECG service found on $deviceAddress.")

                val characteristic = service.getCharacteristic(ECG_CHAR)
                if (characteristic == null) {
                    Log.e(TAG, "ECG characteristic ($ECG_CHAR) not found on $deviceAddress")
                    statusUpdate("ECG char not found on $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        g.close()
                        Log.d(TAG, "GATT closed for $deviceAddress, characteristic not found.")
                    } else {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT, cannot close GATT for $deviceAddress, characteristic not found.")
                    }
                    this@BleForwarder.gatt = null
                    return
                }
                Log.i(TAG, "ECG characteristic found on $deviceAddress.")

                // --- BLUETOOTH_CONNECT Permission Check for notifications ---
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "Missing BLUETOOTH_CONNECT for enabling notifications on $deviceAddress")
                    statusUpdate("Missing BLUETOOTH_CONNECT for notif")
                    g.close()
                    this@BleForwarder.gatt = null
                    return
                }

                Log.d(TAG, "Attempting to enable notifications for char on $deviceAddress.")
                if (!g.setCharacteristicNotification(characteristic, true)) {
                    Log.e(TAG, "setCharacteristicNotification failed for $deviceAddress")
                    statusUpdate("Failed to enable notifications on $deviceAddress")
                    g.close()
                    this@BleForwarder.gatt = null
                    return
                }
                Log.d(TAG, "setCharacteristicNotification success for $deviceAddress.")

                val cccdDescriptor = characteristic.getDescriptor(CCCD)
                if (cccdDescriptor == null) {
                    Log.w(TAG, "CCCD ($CCCD) not found for char on $deviceAddress. Notifications might still work for some devices.")
                    // Some devices enable notifications just by setCharacteristicNotification(true)
                    // If your device strictly requires CCCD write, this is an error.
                    statusUpdate("Streaming (CCCD missing) from $deviceAddress…") // Tentative status
                } else {
                    Log.d(TAG, "CCCD found. Writing ENABLE_NOTIFICATION_VALUE for $deviceAddress.")
                    cccdDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    if (!g.writeDescriptor(cccdDescriptor)) {
                        Log.e(TAG, "writeDescriptor for CCCD failed to initiate for $deviceAddress")
                        statusUpdate("Failed to write CCCD on $deviceAddress")
                        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            g.close()
                            Log.d(TAG, "GATT closed for $deviceAddress, writeDescriptor failed.")
                        } else {
                            Log.w(TAG, "Missing BLUETOOTH_CONNECT, cannot close GATT for $deviceAddress, writeDescriptor failed.")
                        }
                        this@BleForwarder.gatt = null
                        return
                    }
                    Log.d(TAG, "writeDescriptor for CCCD initiated for $deviceAddress.")
                    // Status "Streaming..." will be confirmed in onDescriptorWrite
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received error $status for $deviceAddress")
                statusUpdate("Service discovery failed: $status on $deviceAddress")
                if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    g.close()
                    Log.d(TAG, "GATT closed for $deviceAddress, service discovery error.")
                } else {
                    Log.w(TAG, "Missing BLUETOOTH_CONNECT, cannot close GATT for $deviceAddress, service discovery error.")
                }
                this@BleForwarder.gatt = null
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            super.onDescriptorWrite(g, descriptor, status)
            val deviceAddress = g.device?.address ?: "Unknown device"
            Log.d(TAG, "onDescriptorWrite for $deviceAddress: UUID=${descriptor.uuid}, Status=$status")
            if (descriptor.uuid == CCCD) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CCCD ($CCCD) write successful for $deviceAddress. Notifications fully enabled.")
                    statusUpdate("Streaming from $deviceAddress…")
                } else {
                    Log.e(TAG, "CCCD write failed for $deviceAddress with status: $status")
                    statusUpdate("Failed to enable notifications (CCCD write error $status) on $deviceAddress")
                    if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        g.close()
                        Log.d(TAG, "GATT closed for $deviceAddress, CCCD write failed.")
                    } else {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT, cannot close GATT for $deviceAddress, CCCD write failed.")
                    }
                    this@BleForwarder.gatt = null
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicChanged(g, characteristic) // Keep this for newer Android versions if they add base functionality
            val deviceAddress = g.device?.address ?: "Unknown device"
            val charUuid = characteristic.uuid

            // Log all characteristic changes initially for debugging
            // Log.d(TAG, "onCharacteristicChanged from $deviceAddress: UUID=$charUuid, length=${characteristic.value?.size ?: 0}")

            if (charUuid == ECG_CHAR) {
                val bytes = characteristic.value
                if (bytes != null && bytes.isNotEmpty()) {
                    Log.v(TAG, "ECG Data from $deviceAddress: ${bytes.size} bytes. First byte: ${bytes[0]}") // Verbose log
                    forwardToServer(deviceAddress, bytes)
                } else {
                    Log.w(TAG, "ECG characteristic ($ECG_CHAR) changed but value is null or empty for $deviceAddress.")
                }
            }
        }

        // Optional: Implement other callbacks like onReadRemoteRssi, onMtuChanged if needed
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
                        Log.e(TAG, "Server response body: ${response.body?.string()}") // Log body for errors
                        // Do not update UI directly from here with statusUpdate, could flood UI.
                        // Consider a different mechanism for HTTP error reporting if needed frequently.
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP error forwarding data for $mac: ${e.message}", e)
                // statusUpdate("HTTP error: ${e.message}") // Be careful with frequent UI updates
            }
        }
    }
}