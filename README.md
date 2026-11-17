# MedWand DECL Android Sample

Android Jetpack Compose sample for the MedWand Device Encryption and Communication Library (DECL). The application demonstrates using `MedWandController` for device initialization, temperature, pulse oximetry, ECG, stethoscope, and camera workflows, and `FirmwareController` for firmware updates.

This README describes the SDK calls **used by the Android sample**, not every API exposed by the SDK packages.

## Requirements

- An Android development environment with JDK 21 and Android SDK Platform 36. The project uses Android Gradle Plugin `9.0.0`, Kotlin `2.2.10`, and the Gradle `9.4.1` wrapper.
- Android Studio with support for the project's Android Gradle Plugin version, or the Android command-line tools. Open the repository root as the Android Studio project.
- A physical Android device with USB host support, a USB connection to a MedWand device, and a MedWand SDK license and public key. The project declares `minSdk = 26` (Android 8.0), `targetSdk = 35`, and `compileSdk = 36`.
- Access to download the Gradle wrapper distribution and resolve plugins and dependencies from Google, Maven Central, and the Gradle Plugin Portal.

The sample references `com.medwand:mwsdk:3.0.2.2`. It also references `com.herohan:UVCAndroid` version `1.0.11` and AndroidX Compose, activity, lifecycle, and navigation libraries.

## Setup and run

1. Clone or download [Developer-Suite-Android](https://github.com/MedWand/Developer-Suite-Android).
2. Configure the Android SDK location in the repository's `local.properties` (`sdk.dir`, normally created by Android Studio). Keep that entry and add your issued license and public key before syncing or building:

   ```properties
   MW_SDK_LICENSE=YOUR_LICENSE
   MW_SDK_PUBLIC_KEY=YOUR_PUBLIC_KEY
   ```

   Enter the property values without surrounding quotes. [`app/build.gradle.kts`](app/build.gradle.kts) requires both properties and generates `BuildConfig.MW_SDK_LICENSE` and `BuildConfig.MW_SDK_PUBLIC_KEY`. The `Settings` object in [`MainActivity.kt`](app/src/main/java/com/medwand/developersuite/android/MainActivity.kt) reads those generated values. `local.properties` is ignored by Git; keep your real credentials out of committed source files.

3. In Android Studio, select JDK 21 as the Gradle JDK, sync the project, select your Android device, and run the `app` configuration. For command-line builds, configure `JAVA_HOME` to point to JDK 21 and run the following from the repository root.

   **On a macOS or Linux development computer:**

   ```bash
   bash gradlew :app:assembleDebug
   bash gradlew :app:installDebug
   ```

   **In PowerShell on a Windows development computer:**

   ```powershell
   .\gradlew.bat :app:assembleDebug
   .\gradlew.bat :app:installDebug
   ```

   Both command sets build and install this Android application; choose the commands for your development computer's operating system. `assembleDebug` creates `app/build/outputs/apk/debug/app-debug.apk`. `installDebug` also builds the APK and requires an Android device connected to the computer through ADB with debugging authorized. See Android's [device setup instructions](https://developer.android.com/studio/run/device); wireless debugging on supported devices allows the Android USB port to remain connected to the MedWand.

4. Connect the MedWand by USB to the Android device. Launch **MedWand SDK Sample Application** if it is not already running, then tap **Continue** on the beta notice to begin SDK initialization. Grant the application access to the MedWand USB device when Android prompts. If a device is not found, the sample displays **OK** to retry or **Cancel** to end startup.
5. After initialization, select **Thermometer**, **Pulse Oximeter**, **Stethoscope**, **Camera**, or **ECG** using the toolbar icons. The application requests microphone permission when opening Stethoscope and camera permission when opening Camera. These runtime permissions are separate from USB device access.

The Android entry point is `MainActivity`, declared in [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml). [`MainActivity.kt`](app/src/main/java/com/medwand/developersuite/android/MainActivity.kt) contains multiple Kotlin classes: `MainActivity`, a private application coordinator named `MainWindow`, and the sensor views and view models described below. `MainActivity.onCreate()` creates `MainWindow(this)` and renders the Compose UI. After **Continue**, the UI calls the coordinator's `InitializeSafe()`; `MainActivity.onDestroy()` calls its `Cleanup()`.

The initialized toolbar enables Thermometer, Pulse Oximeter, and ECG directly. Stethoscope requires `HasValidStethoscope`; Camera requires both `CanUseCamera` and `HasValidOtoscope`. The Summary icon is hidden and non-interactive. Use the toolbar's **Exit** action to close the sample; its Android Back handler performs no action.

## `MedWandController` — public MedWand SDK API

### Create, connect, and initialize

The application coordinator's `ConnectMedWand()`, `InitializeMedWand()`, and `InitializeUserInterface()` methods, all in [`MainActivity.kt`](app/src/main/java/com/medwand/developersuite/android/MainActivity.kt), perform these operations in order:

1. Rejects empty license inputs, creates `MedWandController(activity.applicationContext)`, and assigns `OnLicenseError`.
2. Calls `Construct(Settings.MwSdkLicense, Settings.MwSdkPublicKey)` and checks `IsLicenseValid`.
3. Requests Android USB permission, calls `Connect()`, and checks `IsConnected`.
4. Handles a required firmware update or recovery request as described below.
5. Assigns device callbacks and calls `Initialize()`.
6. Checks `IsInitialized`, assigns reading callbacks, creates the sensor views, and calls `Configure(null)`.
7. Displays device information and enables the available workflows. The ECG view supplies its Android `ImageView` to `Configure(...)` when attached.

The sample uses the following device information members when displaying status or device details:

| Members | Usage in the Android sample |
| --- | --- |
| `ComPort`, `VendorId`, `ProductId`, `DeviceId`, `Udi` | Connection and device identifiers |
| `Generation`, `FirmwareVersion`, `CameraModel` | Device and firmware information |
| `DeviceState` | Displayed device state |
| `IsConnected`, `IsInitialized`, `IsLicenseValid` | Connection, initialization, and license checks |
| `IsBootloaderMode(false)` | Bootloader state shown in device information |
| `HasValidStethoscope`, `CanUseCamera`, `HasValidOtoscope` | Stethoscope and Camera toolbar availability after initialization |

`SetNavigation(...)` contains a `HasValidEcg` condition, but the subsequent initialization and workflow-state handlers set the ECG toolbar button to enabled directly. Its enabled state alone does not establish ECG availability on the connected device.

### Device and reading events

| Callback | Handler parameter in the sample | Usage |
| --- | --- | --- |
| `OnLicenseError` | `state` | Prints the license state to the application log |
| `OnDeviceError` | `MedWandDeviceError` | Passes errors to the active sensor view |
| `OnDeviceStateChanged` | Not used by the handler | Assigned to an empty device-state handler |
| `OnReadingStateChanged` | `ReadingState` | Passes reading state to the active sensor view |
| `OnReadingReceived` | `MedWandReading` | Recognizes thermometer, pulse oximeter, and ECG `SensorType` values and forwards readings to the currently active view |
| `OnLedIntensityChanged` | `Int` | Updates the camera view's displayed LED intensity |

The sample assigns SDK callbacks as Kotlin lambdas. Preview, LED, camera-capture result, and firmware state/progress handlers dispatch UI updates to the Android UI thread; device-error and reading callbacks forward directly to the active view. `ReleaseMedWandController()` calls `StopSensor()`, replaces the license, device, and reading callbacks with empty lambdas, and calls `Dispose()`. When deactivated, Stethoscope and Camera restore previous capture handlers, and ECG clears `RecordedStripReady`.

### Thermometer

The `ThermometerViewModel` in [`MainActivity.kt`](app/src/main/java/com/medwand/developersuite/android/MainActivity.kt) calls `StartThermometer()` to begin a measurement and `StopSensor()` when the user stops it. It receives values through `OnReadingReceived` and displays `MedWandReading.TempObject` with an `F` suffix. The view model adds that label without performing a temperature-unit conversion. Use **Start** and **Stop** to control the measurement.

### Pulse oximeter

The `PulseOximeterViewModel` calls `StartPulseOximeter()` and `StopSensor()`. It processes `MedWandReading` values, including `Spo2` and `PulseRate`, received through the controller's reading callback. Use **Start** and **Stop** to control the measurement.

### ECG

The `EcgViewModel` uses:

- `StartEcg()` and `StopSensor()` for ECG monitoring.
- `StartRecording()` and `StopRecording()` for an ECG capture.
- `Ecg.RecordedStripReady` to receive recorded strip bytes.
- `EcgBmpFromCapture(bytes)` to process a recorded strip.

The `EcgView` passes its Android `ImageView` to `Configure(...)` when the render view is attached, and calls `Configure(null)` when it is detached. Activating ECG calls `StartEcg()` to begin monitoring; **Start Recording** and **Stop Recording** control capture separately. Leaving the workflow calls `StopSensor()`.

### Stethoscope

The `StethoscopeView` supports **Off**, **Heart**, **Lungs**, and **Bowel** modes. It uses:

- `SetStethoscopeMode(mode, null)` to select a mode using the SDK audio stack.
- `StartRecording()` and `StopRecording()` to control audio capture.
- `Stethoscope.RecordedFramesReady` to receive captured audio bytes.
- `StethoscopeMode` and `StethoscopeModel` to read the selected mode and device model.

The application coordinator requests Android microphone permission before opening this workflow. Select **Heart**, **Lungs**, or **Bowel**, then use **Start Recording** and **Stop Recording**. The sample's capture handler appends the timestamp, model, mode, and byte count to `captures.txt`; it does not write the received audio bytes to a WAV file.

A separate single-argument `SetStethoscopeMode(...)` call is present only as a **commented-out alternative** for applications that provide their own audio stack; it is not the active sample implementation.

### Camera: dermatoscope and otoscope

The `CameraView` uses `SetCameraMode(previewView, mode)` with `CameraHelper.CameraModes.Dermatoscope` and `CameraHelper.CameraModes.Otoscope`. The Compose UI embeds an Android `ImageView` for preview using `AndroidView`. The application coordinator requests Android camera permission before opening this workflow.

Select **Dermatoscope** or **Otoscope** to start preview, wait for a frame, then select **Capture** to request a still image via `StartRecording()`. Select **Off** to stop preview via `StopSensor()`. Captured bytes arrive through `Camera.RecordedFrameReady`; the sample passes them to `CameraBmpFromCapture(bytes)`, removes a `base64,` prefix if present, decodes the result, and writes the decoded image bytes to a `.png` file in app-private storage.

Other camera API calls demonstrated by the sample are:

| API | Purpose in the sample |
| --- | --- |
| `Camera.FrameReady`, `CameraIsMonitoring`, `CameraMode` | Track preview readiness and current camera state |
| `CameraSetFocusMode(...)`, `CameraSetFocusValue(...)` | Select and adjust focus |
| `CameraFocusInfo`, `CameraFocusModes` | Read supported focus modes, limits, and selected mode |
| `CameraMove(...)` | Move the otoscope overlay |
| `CameraZoom(...)` | Adjust otoscope zoom |
| `CameraRadius(...)` | Adjust the otoscope mask radius |
| `CameraReset()` | Reset otoscope overlay adjustments |
| `CameraSetLedIntensity(...)`, `LedIntensity`, `CameraLedIntensityMax`, `CameraLedIntensityAdjustable`, `OnLedIntensityChanged` | Read and adjust supported LED intensity |

The camera view provides these on-screen controls:

| Control | Sample action |
| --- | --- |
| White LED slider or **Turn on** / **Turn off** | Adjust intensity or toggle the LED, depending on device capabilities |
| **Use Auto** / **Use Manual** | Select focus mode when both modes are supported |
| Manual focus slider | Adjust focus when manual focus is supported and selected |
| Arrow buttons | Move the otoscope overlay |
| Zoom **+** / **−** | Zoom in / out |
| Radius **+** / **−** | Increase / decrease the otoscope mask radius |
| **Reset** | Reset otoscope adjustments |

These controls are implemented by the Android sample and are shown according to the selected mode and device capabilities.

## `FirmwareController` — public API used by the sample

The firmware workflow is implemented by `CheckFirmwareUpdate(...)` and `RunFirmwareUpdate(...)`, methods of the private `MainWindow` application coordinator inside [`MainActivity.kt`](app/src/main/java/com/medwand/developersuite/android/MainActivity.kt).

The sample catches `MedWandFirmwareUpdateRequiredException` from `Connect()` and reads its `CurrentVersion` and `RequiredVersion` values. It also offers firmware recovery if `IsConnected` remains false and `FindMedWandUsbDevice(...)` still finds a device. That fallback is triggered by a failed connection; it does not first confirm bootloader mode. The application prompts with **Start firmware update** and **Exit**, then disposes of the current `MedWandController` before starting the updater if the user chooses to continue.

Inside `RunFirmwareUpdate(...)`, the firmware controller is constructed with the Android application context and the coordinator's USB permission helper:

```kotlin
val usbManager = activity.getSystemService(Activity.USB_SERVICE) as UsbManager
val firmwareController = FirmwareController(
    context = activity.applicationContext,
    usbPermissionRequester = { device ->
        RequestMedWandUsbPermission(usbManager, device)
    }
)
```

The sample assigns updater callbacks, calls `firmwareController.StartAsync()` from the coordinator's suspend function, examines its Boolean result, and calls `firmwareController.DisposeAsync()` in `finally`. USB reconnection during the update is delegated to the SDK; the supplied helper handles permission requests. After a successful update, the sample reconnects the normal controller and continues initialization. A failed update displays the retained error message when available and exits after acknowledgment.

| Callback | Handler arguments in the sample | Usage |
| --- | --- | --- |
| `FirmwareStateChanged` | `_, state` (`UpdaterState`) | Display the current firmware updater state |
| `FirmwareProgressChanged` | `_, progress` | Display firmware progress as a percentage |
| `FirmwareError` | `_, exception` | Retain the error message for a failed update |
| `DeviceErrorReceived` | `_, exception` | Retain device errors reported during the update |

**Firmware-check behavior:** `CheckFirmwareUpdate(...)` handles an update or recovery request returned by `ConnectMedWand()`: it presents confirmation and runs the updater when accepted. The application contains no explicit `CheckFirmwareVersion()` call or periodic firmware-check interval. Review the firmware-check and firmware-update obligations in your MedWand agreement before deploying an application.

## Captured data in the sample

The sample writes its capture log and camera images under Android's app-private `activity.filesDir`:

| Workflow | Output |
| --- | --- |
| ECG | Calls `EcgBmpFromCapture(bytes)` and appends the returned value with a timestamp to `captures.txt` |
| Stethoscope | Appends the timestamp, stethoscope model, mode, and captured byte count to the same `captures.txt`; the app's handler logs metadata only |
| Camera | Calls `CameraBmpFromCapture(bytes)`, decodes the Base64 result, and writes `camera-captures/dermatoscope-<epoch-milliseconds>.png` or `camera-captures/otoscope-<epoch-milliseconds>.png` |

On a debug installation, use Android Studio's [Device Explorer](https://developer.android.com/studio/debug/device-file-explorer) to inspect the app's `files` directory, typically at `/data/data/com.medwand.developersuite.android/files/`. The ECG handler logs the conversion result without writing a separate image file itself; the stethoscope handler logs metadata without writing a WAV file. Camera image files are written directly by the sample. These handlers do not export files to shared device storage.

## Source and SDK

- [`settings.gradle.kts`](settings.gradle.kts): Gradle project definition and dependency repositories.
- [`build.gradle.kts`](build.gradle.kts): Android and Kotlin plugin versions.
- [`app/build.gradle.kts`](app/build.gradle.kts): Android targets, JDK configuration, license properties, and dependencies, including `com.medwand:mwsdk`.
- [`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties): Gradle wrapper version.
- [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml): Declares the launcher activity (`MainActivity`) and camera and microphone permissions.
- [`app/src/main/java/com/medwand/developersuite/android/MainActivity.kt`](app/src/main/java/com/medwand/developersuite/android/MainActivity.kt): Contains `MainActivity`, `Settings`, the private `MainWindow` coordinator, sensor classes (`ThermometerView`, `PulseOximeterView`, `StethoscopeView`, `CameraView`, and `EcgView`), view models, and Compose UI functions. SDK lifecycle, USB permission handling, and firmware updates are implemented here.
- [`app/src/main/res/`](app/src/main/res): Application resources and toolbar images.

The sample imports SDK namespaces including `com.medwand.sdk_core`, `com.medwand.sdk_core.Core`, and `com.medwand.sdk_core.Internal`. The public API references above reflect calls visible in this repository rather than an exhaustive contract or complete type declaration for every SDK member.

## Troubleshooting

- **Build reports a missing license property:** Add `MW_SDK_LICENSE` and `MW_SDK_PUBLIC_KEY` to the repository's `local.properties`. Gradle requires both values to generate `BuildConfig`.
- **License validation fails:** Confirm that both properties contain your issued values. The sample rejects empty strings before calling `Construct(...)` and checks `IsLicenseValid` before connecting.
- **Build uses the wrong Java version:** Configure the Android Studio Gradle JDK or the terminal's `JAVA_HOME` to use JDK 21. Both Java and Kotlin compilation target JVM 21 in this project.
- **Installation cannot find a device:** Check the Android device's ADB connection and authorize debugging. This connects your development computer to Android; the application separately requests USB access to the MedWand attached to Android.
- **MedWand not found or USB permission denied:** Confirm the MedWand is connected to the Android device through a USB host connection and grant the app USB access when prompted. The connection code checks `IsConnected` after `Connect()`; use **OK** to retry when the device-not-found dialog appears. If startup ends after a permission denial, relaunch the application to try again.
- **Initialization fails:** Review the application's **Initialization Error** dialog and the connection code in `MainActivity.kt`. The sample checks `IsConnected` before `Initialize()` and `IsInitialized` afterward; `DeviceState` is included in the device information shown after successful initialization.
- **Firmware update or recovery is requested:** Keep the device connected and powered, follow the application's confirmation and progress messages, and grant USB access again if prompted after reconnection. The firmware update uses `FirmwareController.StartAsync()` and reports state, progress, and errors through its callbacks.
- **Stethoscope or Camera icon is disabled:** Check `HasValidStethoscope` or the combination of `CanUseCamera` and `HasValidOtoscope`, respectively. These checks control toolbar availability after initialization.
- **Audio or camera permission is denied:** Grant the requested microphone or camera permission in Android settings and reopen the workflow. Camera focus and LED controls also depend on the capabilities reported by the SDK; **Capture** becomes available after preview delivers a frame.
- **No WAV file after stethoscope recording:** The sample records capture metadata in `captures.txt`; add audio persistence to your application if needed.

For additional MedWand developer information, see the [MedWand developer support resources](https://support.medwand.com/portal/en/kb/medwand-support/developers).
