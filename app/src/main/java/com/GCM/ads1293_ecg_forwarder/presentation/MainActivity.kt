package com.gcm.ads1293_ecg_forwarder.presentation

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    // 1) Create a Compose State at the class level
    private val uiStatus = mutableStateOf("Idle")
    private lateinit var forwarder: BleForwarder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2) Ask for the permissions (no-op in the callback)
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { /* nothing here */ }
            .launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.INTERNET
            ))

        // 3) Pass a lambda that updates our uiStatus State
        forwarder = BleForwarder(this) { msg ->
            uiStatus.value = msg
        }

        // 4) Compose your screen, reading from uiStatus
        setContent {
            // use `.value` inside Compose
            val status by uiStatus

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(text = status)       // ← shows “Scanning…”, “Connecting…”, etc.
                Spacer(Modifier.height(24.dp))
                Row {
                    Button(onClick = {
                        uiStatus.value = "Starting…"
                        forwarder.start()
                    }) {
                        Text("START")
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = {
                        forwarder.stop()
                        uiStatus.value = "Stopped"
                    }) {
                        Text("STOP")
                    }
                }
            }
        }
    }
}
