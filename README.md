# ADS1293 ECG BLE Forwarder for Wear OS

## Project Overview

This Wear OS application is designed to connect to a Texas Instruments ADS1293-based ECG (Electrocardiogram) sensor via Bluetooth Low Energy (BLE). Once connected, it receives raw ECG data packets from the sensor and forwards them over HTTP POST requests to a configurable backend server.

The primary goal is to act as a bridge, enabling real-time or near real-time collection of ECG data from a wearable sensor and transmitting it to a central point for further processing, storage, or analysis.

## Core Functionality

1.  **BLE Device Scanning:** Scans for nearby BLE devices advertising the specific TI ADS1293 ECG service UUID (`00002d0d-0000-1000-8000-00805f9b34fb`).
2.  **BLE Connection:** Establishes a GATT (Generic Attribute Profile) connection with the discovered ECG sensor.
3.  **Service & Characteristic Discovery:** Identifies the ECG service and the specific characteristic (`00002d37-0000-1000-8000-00805f9b34fb`) that provides ECG data.
4.  **Data Notification Subscription:** Enables notifications on the ECG data characteristic by writing to its Client Characteristic Configuration Descriptor (CCCD - `00002902-0000-1000-8000-00805f9b34fb`). This tells the sensor to start sending data.
5.  **ECG Data Reception:** Receives ECG data packets from the sensor whenever the characteristic's value changes.
6.  **HTTP Data Forwarding:** Forwards the received raw ECG data bytes as an `application/octet-stream` in an HTTP POST request to a specified backend server URL. The MAC address of the BLE sensor is included as an `X-Device-MAC` header.
7.  **User Interface (UI):** Provides a simple UI on the Wear OS device with:
    *   A "START" button to initiate the scanning and connection process.
    *   A "STOP" button to terminate the BLE connection and scanning.
    *   A text field to display the current status of the operation (e.g., "Scanning...", "Connected", "Streaming...", "Error...").
8.  **Runtime Permissions Handling:** Requests necessary Bluetooth and Internet permissions at runtime as required by modern Android versions.

## Contributions (What I Did)

As the developer of this application, my responsibilities and contributions included:

*   **System Design:** Conceptualized the data flow from the BLE sensor to the backend server via the Wear OS app.
*   **BLE Integration:**
    *   Implemented BLE scanning logic, filtered for the specific ADS1293 service.
    *   Developed the GATT connection management, including connection state handling, service discovery, characteristic interaction, and CCCD configuration for enabling notifications.
    *   Handled `onCharacteristicChanged` callbacks to receive incoming ECG data.
*   **Data Forwarding:**
    *   Integrated the OkHttp library for making HTTP POST requests.
    *   Constructed requests to send raw byte data and the device's MAC address.
    *   Implemented basic error logging for HTTP requests.
*   **User Interface (Jetpack Compose):**
    *   Designed and implemented a functional UI using Jetpack Compose for Wear OS, including buttons and a status display.
    *   Managed UI state updates based on BLE and HTTP operations.
*   **Permissions Management:**
    *   Identified and declared necessary permissions in `AndroidManifest.xml`.
    *   Implemented runtime permission requests using AndroidX Activity Result APIs for `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `INTERNET`.
    *   Added permission checks before critical BLE operations as required by Android 12+ (API 31+).
*   **Asynchronous Operations:** Utilized Kotlin Coroutines for performing network operations on a background thread to prevent blocking the UI.
*   **Project Setup & Build Configuration:**
    *   Configured the `build.gradle.kts` files, including dependencies for Compose, Wear OS, OkHttp, and Kotlin.
    *   Ensured the project compiles against the appropriate Android SDK versions (`compileSdk = 34`, `targetSdk = 34`).
*   **Debugging & Testing:**
    *   Utilized Android Studio's Logcat extensively for debugging BLE interactions, permission flows, and HTTP requests.
    *   Tested the application on a Samsung Watch 6 with an ADS1293 sensor.
    *   Iteratively refined the code to handle various states and potential error conditions encountered during BLE operations.

## Technical Stack

*   **Language:** Kotlin
*   **Platform:** Android (Wear OS)
*   **UI:** Jetpack Compose for Wear OS
*   **Asynchronous Programming:** Kotlin Coroutines
*   **Networking:** OkHttp
*   **Bluetooth:** Android Bluetooth Low Energy (BLE) APIs

## Project Structure (Key Files)

*   **`app/src/main/java/com/gcm/ads1293_ecg_forwarder/presentation/MainActivity.kt`**:
    *   Handles the main UI of the application using Jetpack Compose.
    *   Manages runtime permission requests.
    *   Instantiates and controls the `BleForwarder`.
    *   Updates the UI based on status messages from `BleForwarder`.
*   **`app/src/main/java/com/gcm/ads1293_ecg_forwarder/presentation/BleForwarder.kt`**:
    *   Contains all the core logic for Bluetooth Low Energy operations:
        *   Scanning for devices.
        *   Connecting to a GATT server.
        *   Discovering services and characteristics.
        *   Enabling notifications and handling incoming data.
        *   Managing connection states.
    *   Implements the HTTP forwarding logic using OkHttp.
    *   Provides status updates to `MainActivity` via a callback.
*   **`app/src/main/AndroidManifest.xml`**:
    *   Declares application components (e.g., `MainActivity`).
    *   Specifies required permissions (Bluetooth, Internet, Wake Lock).
    *   Declares that the app is for `android.hardware.type.watch` and is standalone.
*   **`app/build.gradle.kts`**:
    *   Defines application ID, SDK versions (`minSdk`, `compileSdk`, `targetSdk`).
    *   Lists all project dependencies (Compose, Wear OS libraries, OkHttp, etc.).
    *   Configures build types and Compose options.
*   **`build.gradle.kts` (Project Level)**:
    *   Specifies versions for Android Gradle Plugin and Kotlin Gradle Plugin.

## Setup and Running Instructions

### Prerequisites:

1.  **Android Studio:** Latest stable version (e.g., Hedgehog or newer).
2.  **Wear OS Device:** A physical Wear OS device (like a Samsung Watch 6) running Wear OS 3 or later (`minSdk = 30`).
    *   Developer Options and ADB/Wireless Debugging must be enabled on the watch.
3.  **ADS1293 BLE Sensor:** The target ECG sensor device, powered on and advertising.
4.  **Backend Server (Optional but Recommended for Full Test):** An HTTP server endpoint ready to receive POST requests with `application/octet-stream` data.

### Steps:

1.  **Clone the Repository:**
    ```bash
    git clone <repository-url>
    cd <repository-directory>
    ```
2.  **Open in Android Studio:**
    *   Launch Android Studio.
    *   Select "Open" and navigate to the cloned project directory.
    *   Allow Gradle to sync and build the project.
3.  **Configure Backend Server URL (IMPORTANT):**
    *   Open the file: `app/src/main/java/com/gcm/ads1293_ecg_forwarder/presentation/BleForwarder.kt`.
    *   Locate the `forwardToServer` function.
    *   Find the line:
        ```kotlin
        .url("https://YOUR.GUI.SERVER/ingest") // Original placeholder
        ```
    *   **Replace `"https://YOUR.GUI.SERVER/ingest"` with the actual URL of your backend server.**
    *   If you don't have a backend server yet and want to test BLE only, you can:
        *   Use a public request bin service (e.g., `webhook.site`) and put its URL here.
        *   Comment out the HTTP call within the `try-catch` block in `forwardToServer` to prevent network errors while focusing on BLE.
4.  **Connect Watch via ADB:**
    *   Ensure your Wear OS watch is connected to the same Wi-Fi network as your development machine.
    *   Enable "Wireless debugging" on the watch (Settings > Developer options > Wireless debugging).
    *   Pair the watch with ADB using the `adb pair <ip_address>:<pairing_port>` command from your PC's terminal (inside `platform-tools` directory). Enter the pairing code.
    *   Connect to the watch using `adb connect <ip_address>:<connection_port>`.
    *   Verify the connection with `adb devices`. Your watch should be listed.
5.  **Build and Run the Application:**
    *   In Android Studio, select your connected Wear OS device from the device dropdown menu.
    *   Click the "Run 'app'" button (green play icon) or use the menu `Run > Run 'app'`.
    *   The app will be built and installed on your watch.
6.  **Operate the App on the Watch:**
    *   Once the app launches, it will immediately request Bluetooth and Internet permissions. **Grant these permissions.**
    *   The UI will display "Idle".
    *   Ensure your ADS1293 sensor is powered on and advertising.
    *   Tap the "START" button on the watch app.
    *   Observe the status text for updates: "Starting...", "Scanning...", "Connecting...", "Discovering services...", "Streaming...".
    *   If the connection is successful and data is being received, it will be forwarded to your configured server.
    *   Tap the "STOP" button to disconnect from the BLE device and stop scanning/forwarding.

## Permissions Required

The application requires the following permissions, which are declared in `AndroidManifest.xml` and requested at runtime where necessary:

*   **`android.permission.BLUETOOTH` (Implicit):** Basic Bluetooth access.
*   **`android.permission.BLUETOOTH_ADMIN` (Implicit for older APIs):** For Bluetooth device discovery and management (largely superseded by SCAN and CONNECT for API 31+).
*   **`android.permission.BLUETOOTH_SCAN`:** (Runtime permission for API 31+) Required to scan for nearby BLE devices. The `android:usesPermissionFlags="neverForLocation"` attribute is used to indicate that this app does not derive location from Bluetooth scans.
*   **`android.permission.BLUETOOTH_CONNECT`:** (Runtime permission for API 31+) Required to connect to paired BLE devices, perform GATT operations (read/write characteristics, descriptors), and close connections.
*   **`android.permission.INTERNET`:** (Runtime permission) Required to send HTTP requests to the backend server.
*   **`android.permission.WAKE_LOCK`:** To ensure the CPU remains active for critical BLE and network operations if the screen turns off (though more robust background operation would use a Foreground Service).
*   **Note on Location:** For apps targeting Android versions prior to 12 (API 31), `ACCESS_FINE_LOCATION` was often required for BLE scanning. Since this app targets API 34 and uses `BLUETOOTH_SCAN` with `neverForLocation`, explicit location permission is not strictly needed for the scan functionality on compatible devices.

## Troubleshooting & Key Considerations

*   **Logcat is Your Friend:** Use Android Studio's Logcat (View > Tool Windows > Logcat) to monitor detailed logs from the app. Filter by tags `MainActivity_ECG` and `BleForwarder_ECG` to see app-specific messages. This is crucial for diagnosing BLE and HTTP issues.
*   **Permissions:** Ensure all requested permissions (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `INTERNET`) are granted on the watch. If not, the app will not function correctly.
*   **BLE Device:** Verify the ADS1293 sensor is powered on, within range, and advertising correctly.
*   **Server Endpoint:** Double-check that the server URL in `BleForwarder.kt` is correct and that the server is running and accessible.
*   **Network Connectivity:** Ensure the Wear OS watch has a stable Wi-Fi connection to reach the backend server.
*   **API Level Specifics:** The app correctly handles runtime permissions for `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` required since Android 12 (API 31).

## Potential Future Enhancements

*   **Robust Error Handling:** Implement more comprehensive error handling and user feedback for various BLE and network failure scenarios.
*   **Background Operation:** Convert the core functionality into a Foreground Service to allow reliable data collection and forwarding even when the app UI is not visible.
*   **Data Buffering & Retry:** Implement a queue for ECG data and a retry mechanism for HTTP requests in case of temporary network issues.
*   **Configuration Screen:** Add a settings screen within the app to allow users to configure the backend server URL without needing to recompile.
*   **Battery Optimization:** Further optimize BLE scanning and connection parameters to minimize battery drain.
*   **Data Visualization (Basic):** Potentially display a very rudimentary plot of incoming ECG data on the watch for quick verification (if feasible and performant).

---
