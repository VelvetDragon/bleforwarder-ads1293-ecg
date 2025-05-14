package com.gcm.ads1293_ecg_forwarder.presentation

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class BleForwarder(
    private val ctx: Context,
    private val status: (String) -> Unit
) {
    companion object {
        val ECG_SERVICE = java.util.UUID.fromString("00002d0d-0000-1000-8000-00805f9b34fb")
        val ECG_CHAR    = java.util.UUID.fromString("00002d37-0000-1000-8000-00805f9b34fb")
        val CCCD        = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private val http = OkHttpClient()

    fun start() {
        val bt = BluetoothAdapter.getDefaultAdapter()
            ?: return status("No Bluetooth adapter")
        if (!bt.isEnabled) return status("Bluetooth is off")
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return status("Missing BLUETOOTH_SCAN")
        }

        status("Scanning…")
        scanner = bt.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(ECG_SERVICE))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner!!.startScan(listOf(filter), settings, scanCb)
    }

    fun stop() {
        scanner?.stopScan(scanCb)
        gatt?.close()
        scanner = null
        gatt = null
        status("Stopped")
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            scanner?.stopScan(this)
            status("Connecting…")
            result.device.connectGatt(ctx, false, gattCb)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Discovering services…")
                gatt = g
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status("Disconnected")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val svc = g.getService(ECG_SERVICE) ?: return status("Service not found")
            val ch  = svc.getCharacteristic(ECG_CHAR) ?: return status("Char not found")
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCCD) ?: return
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(desc)
            status("Streaming…")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic
        ) {
            val bytes = c.value
            val mac   = g.device.address
            forwardToServer(mac, bytes)
        }
    }

    private fun forwardToServer(mac: String, data: ByteArray) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Build a multipart / header with MAC + raw bytes
                val body = RequestBody.create("application/octet-stream".toMediaType(), data)
                val req  = Request.Builder()
                    .url("https://YOUR.GUI.SERVER/ingest")
                    .header("X-Device-MAC", mac)
                    .post(body)
                    .build()

                http.newCall(req).execute().use { resp ->
                    // optionally check resp.isSuccessful
                }
            } catch(e: Exception) {
                status("HTTP error: ${e.message}")
            }
        }
    }
}
