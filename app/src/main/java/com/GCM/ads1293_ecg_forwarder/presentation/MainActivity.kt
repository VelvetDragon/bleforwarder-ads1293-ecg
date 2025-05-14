package com.gcm.ads1293_ecg_forwarder.presentation

import android.Manifest
import android.os.Bundle
import android.util.Log // <-- ADD THIS IMPORT
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button // Ensure this is material3 if your theme uses it
import androidx.compose.material3.Text   // Ensure this is material3
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
// If you are using androidx.wear.compose.material.Button/Text, use those imports instead.
// For this example, assuming Material 3 based on your dependencies.

class MainActivity : ComponentActivity() {
    private val uiStatus = mutableStateOf("Idle")
    private lateinit var forwarder: BleForwarder
    private val TAG = "MainActivity_ECG" // Unique tag for easier filtering

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ULTRA_BASIC_TEST", "MainActivity onCreate VERY START")
        Log.d(TAG, "onCreate started")

        // Request permissions
        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            Log.d(TAG, "Permission result received:")
            var allGranted = true
            permissions.entries.forEach {
                Log.d(TAG, "${it.key} = ${it.value}")
                if (!it.value) allGranted = false
            }
            if (allGranted) {
                Log.d(TAG, "All permissions granted.")
                // You could potentially auto-start or enable UI here if needed
            } else {
                Log.w(TAG, "Not all permissions were granted.")
                // Update UI to inform user permissions are needed
                uiStatus.value = "Permissions needed!"
            }
        }

        Log.d(TAG, "Requesting permissions...")
        permissionLauncher.launch(arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.INTERNET
        ))

        forwarder = BleForwarder(this) { msg ->
            Log.d(TAG, "Status Callback from BleForwarder: $msg")
            runOnUiThread { // Ensure UI updates are on the main thread
                uiStatus.value = msg
            }
        }
        Log.d(TAG, "BleForwarder instantiated")

        setContent {
            val currentStatus by uiStatus // Observe the state
            Log.d(TAG, "Compose content. Current status: $currentStatus")

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = currentStatus)
                Spacer(Modifier.height(24.dp))
                Row {
                    Button(onClick = {
                        Log.d(TAG, "!!! START BUTTON CLICKED - INSIDE LAMBDA !!!")
                        uiStatus.value = "Starting…" // Immediate feedback
                        forwarder.start()
                    }) {
                        Text("START")
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = {
                        Log.d(TAG, "STOP button clicked.")
                        forwarder.stop()
                        // uiStatus.value will be updated by BleForwarder's callback
                    }) {
                        Text("STOP")
                    }
                }
            }
        }
        Log.d(TAG, "onCreate finished")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        // Consider re-checking permissions if app was backgrounded and they could be revoked,
        // or if the user denied them initially and then comes back to the app.
    }
}