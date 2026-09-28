package com.medwand.developersuite.android

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.medwand.developersuite.android.BuildConfig
import com.medwand.sdk_core.ReadingState
import com.medwand.sdk_core.MedWandSensor
import com.medwand.sdk_core.MedWandDeviceError
import com.medwand.sdk_core.MedWandReading
import com.medwand.sdk_core.Internal.CameraHelper
import com.medwand.sdk_core.Internal.StethoscopeHelpers
import com.medwand.sdk_core.FirmwareController
import com.medwand.sdk_core.Core.MedWandFirmwareUpdateRequiredException
import com.medwand.sdk_core.MedWandController
import com.medwand.sdk_core.UpdaterState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Base64
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * Android host activity that creates the application shell and delegates cleanup
 * to the shell when the Activity is destroyed.
 */
class MainActivity : ComponentActivity() {
    private lateinit var MainWindow: MainWindow

    /**
     * Creates the shell state once and renders the Compose application tree.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainWindow = MainWindow(this)

        setContent {
            MedWandSdkSampleApplication(MainWindow)
        }
    }

    /**
     * Releases the current sensor, SDK callbacks, and controller when Android
     * destroys the Activity.
     */
    override fun onDestroy() {
        MainWindow.Cleanup()
        super.onDestroy()
    }
}

/**
 * Application metadata and SDK construction inputs used by the startup path.
 */
private object Settings {
    const val AppCompany = "MedWand Solutions, Inc."
    const val AppProduct = "MedWand SDK Sample Application"
    const val AppCopyright = "2026"
    const val AppVersion = "3.0.1.0"
    const val AppBuild = "Unknown Build"
    const val MwSdkLicense = BuildConfig.MW_SDK_LICENSE
    const val MwSdkPublicKey = BuildConfig.MW_SDK_PUBLIC_KEY
}

/** Private broadcast action used to receive the Android USB permission result. */
private const val ACTION_USB_PERMISSION = "com.medwand.developersuite.android.USB_PERMISSION"

private const val StartupNoticeMessage =
    "This is a BETA only sample application and SDK. This is not intended for use in production and should only be used for initial development work. Camera testing requires Android camera permission. You will still need to request a license through your sales representative."

/**
 * Shared action state for workflow buttons and navigation locking.
 */
private enum class ActionState {
    Idle,
    Busy,
    Disabled
}

/**
 * Sensor workflow surface used by the shell to activate views and route SDK
 * reading, state, and error callbacks to the active workflow.
 */
private interface ISensorView : AutoCloseable {
    /** SDK sensor represented by this workflow. */
    val MedWandSensor: MedWandSensor

    /** Callback used by workflow state changes to lock or unlock navigation. */
    var ViewLockStateChanged: ((Boolean) -> Unit)?

    /** Prepares the workflow when it becomes the active content view. */
    fun Activate()

    /** Stops or releases workflow runtime state before another view is shown. */
    fun Deactivate()

    /** Receives the SDK reading-state changes routed by the shell. */
    fun OnReadingStateChanged(readingState: ReadingState)

    /** Receives SDK readings for this workflow's sensor type. */
    fun OnReadingReceived(reading: MedWandReading)

    /** Receives SDK device errors while this workflow is active. */
    fun OnDeviceError(error: MedWandDeviceError?)

    /** Renders the workflow content inside the shell's main frame. */
    @Composable
    fun Render()
}

/**
 * Dialog result values consumed by the shell's suspendable message-box helper.
 */
private sealed class MessageBoxResult {
    data object OK : MessageBoxResult()
    data object Cancel : MessageBoxResult()
    data object Yes : MessageBoxResult()
    data object No : MessageBoxResult()
    data object StartFirmwareUpdate : MessageBoxResult()
    data object Exit : MessageBoxResult()
}

/**
 * Pending dialog state rendered by Compose while the caller awaits the result.
 */
private data class MessageBoxRequest(
    val message: String,
    val title: String,
    val buttons: List<MessageBoxResult>,
    val result: CompletableDeferred<MessageBoxResult>
)

private data class FirmwareUpdateRequest(
    val currentVersion: String,
    val targetVersion: String,
    val recovery: Boolean
)

/**
 * Owns the visible shell state, direct MedWand SDK controller lifecycle, toolbar
 * navigation, status text, and active sensor workflow.
 */
private class MainWindow(private val activity: Activity) {
    private var _medWandController: MedWandController? = null
    private var _thermometerView: ThermometerView? = null
    private var _pulseOximeterView: PulseOximeterView? = null
    private var _stethoscopeView: StethoscopeView? = null
    private var _cameraView: CameraView? = null
    private var _ecgView: EcgView? = null
    private var _currentSensorView: ISensorView? by mutableStateOf(null)

    private var _audioPermissionResult: CompletableDeferred<Boolean>? = null
    private val _audioPermissionLauncher: ActivityResultLauncher<String>? =
        (activity as? ComponentActivity)?.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            _audioPermissionResult?.complete(granted)
            _audioPermissionResult = null
        }

    private var _cameraPermissionResult: CompletableDeferred<Boolean>? = null
    private val _cameraPermissionLauncher: ActivityResultLauncher<String>? =
        (activity as? ComponentActivity)?.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            _cameraPermissionResult?.complete(granted)
            _cameraPermissionResult = null
        }

    private val GeneralStatus: String
        get() = "Device: ${_medWandController?.ComPort}/${_medWandController?.VendorId}/${_medWandController?.ProductId} | ${_medWandController?.Udi} | ${_medWandController?.Generation} v${_medWandController?.FirmwareVersion}"

    var ToolButtonThermometerEnabled by mutableStateOf(true)
    var ToolButtonPulseOximeterEnabled by mutableStateOf(true)
    var ToolButtonStethoscopeEnabled by mutableStateOf(true)
    var ToolButtonCameraEnabled by mutableStateOf(true)
    var ToolButtonEcgEnabled by mutableStateOf(true)
    var ToolButtonSummaryEnabled by mutableStateOf(true)
    var ToolButtonExitEnabled by mutableStateOf(true)
    var StatusMessage by mutableStateOf("Starting")
    var PlaceholderInfo by mutableStateOf("Starting...")
    var MessageBox by mutableStateOf<MessageBoxRequest?>(null)
    var StartupNoticeAccepted by mutableStateOf(false)
    var FirmwareUpdateVisible by mutableStateOf(false)
    var FirmwareUpdateState by mutableStateOf("")
    var FirmwareUpdateProgress by mutableStateOf(0)
    var FirmwareUpdateMessage by mutableStateOf("")

    init {
        // Startup disables sensor navigation until the controller is connected
        // and initialized; Exit remains controlled by the caller.
        SetNavigation(false, false)
        UpdateStatus("Starting...")
    }

    /** Allows startup initialization to continue after the beta notice screen. */
    fun ContinueStartupNotice() {
        StartupNoticeAccepted = true
    }

    /**
     * Runs startup and presents initialization failures through the visible
     * error dialog and cleanup path.
     */
    suspend fun InitializeSafe() {
        try {
            Initialize()
        } catch (ex: Exception) {
            MessageBox(ex.message ?: "", "Initialization Error", listOf(MessageBoxResult.OK))
            Cleanup()
            activity.finish()
        }
    }

    /**
     * Executes the application startup sequence: connect the device, initialize
     * the SDK controller, then create the available workflow views.
     */
    private suspend fun Initialize() {
        val firmwareUpdate = ConnectMedWand()
        if (firmwareUpdate != null && !CheckFirmwareUpdate(firmwareUpdate)) return
        InitializeMedWand()
        InitializeUserInterface()
    }

    /**
     * Constructs the SDK controller, validates license inputs, requests Android
     * USB access when needed, and connects to the physical MedWand device.
     */
    private suspend fun ConnectMedWand(): FirmwareUpdateRequest? {
        if (Settings.MwSdkLicense.isEmpty() || Settings.MwSdkPublicKey.isEmpty()) {
            throw Exception("No valid license information")
        }

        val usbManager = activity.getSystemService(Activity.USB_SERVICE) as UsbManager
        _medWandController = _medWandController ?: MedWandController(activity.applicationContext)
        _medWandController?.OnLicenseError = { state ->
            println("LicenseError: $state")
        }
        _medWandController?.Construct(Settings.MwSdkLicense, Settings.MwSdkPublicKey)
        if (_medWandController?.IsLicenseValid != true) {
            throw Exception("No valid license")
        }

        UpdateStatus("Connecting to MedWand.")
        if (!RequestMedWandUsbPermission(usbManager)) {
            throw Exception("MedWand USB permission denied.")
        }

        var done = false
        do {
            try {
                _medWandController?.Connect()
            } catch (updateRequired: MedWandFirmwareUpdateRequiredException) {
                return FirmwareUpdateRequest(
                    currentVersion = updateRequired.CurrentVersion,
                    targetVersion = updateRequired.RequiredVersion,
                    recovery = false
                )
            } catch (outerEx: Exception) {
                println(outerEx)
            }

            if (_medWandController?.IsConnected == true) {
                done = true
            } else if (FindMedWandUsbDevice(usbManager) != null) {
                return FirmwareUpdateRequest(
                    currentVersion = "Unavailable",
                    targetVersion = "Latest available",
                    recovery = true
                )
            } else {
                val resultDialog = MessageBox(
                    "MedWand not found. Please connect your MedWand and try again.",
                    "MedWand Not Found",
                    listOf(MessageBoxResult.OK, MessageBoxResult.Cancel)
                )
                if (resultDialog == MessageBoxResult.Cancel) {
                    break
                }
            }
        } while (!done)

        if (done) {
            _medWandController?.OnDeviceError = { MedWandController_MedWandDeviceError(it) }
            _medWandController?.OnDeviceStateChanged = { MedWandController_DeviceStateChanged() }
            return null
        }

        _medWandController?.OnLicenseError = {}
        _medWandController = null
        throw Exception("No MedWand Connected!")
    }

    /**
     * Requests runtime permission for the discovered MedWand USB device and
     * suspends until Android broadcasts the grant or denial.
     */
    private suspend fun RequestMedWandUsbPermission(
        usbManager: UsbManager,
        medWandDevice: UsbDevice? = FindMedWandUsbDevice(usbManager)
    ): Boolean {
        medWandDevice ?: return true
        if (usbManager.hasPermission(medWandDevice)) {
            return true
        }

        val result = CompletableDeferred<Boolean>()
        val permissionIntent = PendingIntent.getBroadcast(
            activity,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(activity.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) {
                    return
                }

                val device = intent.usbDevice()
                if (device?.deviceName != medWandDevice.deviceName) {
                    return
                }

                result.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }

        var registered = false
        try {
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                activity.registerReceiver(receiver, filter)
            }
            registered = true
            usbManager.requestPermission(medWandDevice, permissionIntent)
            return result.await()
        } finally {
            if (registered) {
                runCatching { activity.unregisterReceiver(receiver) }
            }
        }
    }

    private fun FindMedWandUsbDevice(usbManager: UsbManager): UsbDevice? {
        val devices = usbManager.deviceList.values
        return devices.firstOrNull { it.productId == 60 }
            ?: devices.firstOrNull { device ->
                listOfNotNull(device.manufacturerName, device.productName, device.deviceName)
                    .any { it.contains("medwand", ignoreCase = true) || it.contains("med wand", ignoreCase = true) }
            }
    }

    private suspend fun CheckFirmwareUpdate(update: FirmwareUpdateRequest): Boolean {
        UpdateStatus("Firmware update required.")
        val message = if (update.recovery) {
            """
            The MedWand could not start in normal mode and may be in bootloader or recovery state.

            Firmware recovery will install the latest available firmware.

            Keep the MedWand connected and powered during the update.
            """.trimIndent()
        } else {
            """
            A new MedWand firmware update is available.

            Current version: ${update.currentVersion}
            New version: ${update.targetVersion}

            Keep the MedWand connected and powered during the update.
            """.trimIndent()
        }

        val result = MessageBox(
            message,
            if (update.recovery) "Firmware Recovery Required" else "Firmware Update Available",
            listOf(MessageBoxResult.StartFirmwareUpdate, MessageBoxResult.Exit)
        )

        if (result == MessageBoxResult.Exit) {
            Cleanup()
            activity.finishAndRemoveTask()
            return false
        }

        return RunFirmwareUpdate(update.currentVersion, update.targetVersion)
    }

    /**
     * Gives the firmware updater exclusive ownership of the MedWand USB device,
     * displays its progress, then reconnects the normal SDK controller after a
     * successful update. The updater itself owns bootloader re-enumeration.
     */
    private suspend fun RunFirmwareUpdate(currentVersion: String, targetVersion: String): Boolean {
        SetNavigation(false, false)
        FirmwareUpdateVisible = true
        FirmwareUpdateState = "Starting"
        FirmwareUpdateProgress = 0
        FirmwareUpdateMessage =
            "Updating MedWand firmware from $currentVersion to $targetVersion. Do not disconnect the device."
        UpdateStatus("Starting firmware update.")

        ReleaseMedWandController()

        val usbManager = activity.getSystemService(Activity.USB_SERVICE) as UsbManager
        val firmwareController = FirmwareController(
            context = activity.applicationContext,
            usbPermissionRequester = { device -> RequestMedWandUsbPermission(usbManager, device) }
        )
        var lastError: String? = null

        firmwareController.FirmwareStateChanged = { _, state ->
            activity.runOnUiThread {
                FirmwareUpdateState = state.name
                FirmwareUpdateMessage = when (state) {
                    UpdaterState.Initializing -> "Preparing the device and firmware package."
                    UpdaterState.Reconnecting -> "The MedWand is reconnecting. Keep it connected."
                    UpdaterState.Erasing -> "Erasing the old firmware. Do not disconnect the MedWand."
                    UpdaterState.Programming -> "Programming the new firmware."
                    UpdaterState.Reading -> "Reading the programmed firmware back."
                    UpdaterState.Verifying -> "Verifying the new firmware."
                    UpdaterState.Commiting -> "Committing the verified firmware. Do not disconnect the MedWand."
                    UpdaterState.Complete -> "Firmware update completed."
                }
                UpdateStatus("Firmware update: ${state.name}")
            }
        }
        firmwareController.FirmwareProgressChanged = { _, progress ->
            activity.runOnUiThread {
                FirmwareUpdateProgress = progress.coerceIn(0, 100)
            }
        }
        firmwareController.FirmwareError = { _, exception ->
            lastError = exception.message
        }
        firmwareController.DeviceErrorReceived = { _, exception ->
            lastError = exception.message
        }

        val success = try {
            firmwareController.StartAsync()
        } catch (ex: Exception) {
            lastError = ex.message
            false
        } finally {
            firmwareController.DisposeAsync()
        }

        if (!success) {
            FirmwareUpdateState = "Failed"
            FirmwareUpdateMessage = lastError?.let { "Firmware update failed: $it" }
                ?: "Firmware update failed. Please restart the application and try again."
            UpdateStatus("Firmware update failed.")
            MessageBox(
                FirmwareUpdateMessage,
                "Firmware Update Failed",
                listOf(MessageBoxResult.Exit)
            )
            Cleanup()
            activity.finishAndRemoveTask()
            return false
        }

        FirmwareUpdateState = "Complete"
        FirmwareUpdateProgress = 100
        FirmwareUpdateMessage = "Firmware update completed. Reconnecting to the MedWand."
        UpdateStatus("Firmware update complete. Reconnecting.")

        val remainingUpdate = ConnectMedWand()
        if (remainingUpdate != null) {
            throw Exception("MedWand still requires firmware update after the update completed.")
        }
        FirmwareUpdateVisible = false
        return true
    }

    /**
     * Initializes the connected controller and attaches SDK reading callbacks
     * used by the active sensor view.
     */
    private fun InitializeMedWand() {
        UpdateStatus("Initializing MedWand")

        if (_medWandController?.IsConnected != true) {
            throw Exception("MedWand not connected.")
        }

        // Initializes the physical MedWand through the SDK before any workflow
        // view can be enabled.
        _medWandController?.Initialize()

        if (_medWandController?.IsInitialized != true) {
            throw Exception("MedWand not initialized.")
        }

        // Reading callbacks are routed by sensor type to the current workflow.
        _medWandController?.OnReadingStateChanged = { MedWandController_ReadingStateChanged(it) }
        _medWandController?.OnReadingReceived = { MedWandController_ReadingReceived(it) }
    }

    /**
     * Creates workflow views after SDK initialization and enables only the
     * workflows currently available in this application.
     */
    private fun InitializeUserInterface() {
        if (_medWandController?.IsInitialized != true) {
            SetNavigation(false, true)
            return
        }

        _thermometerView = ThermometerView(
            requireNotNull(_medWandController),
            locked = { CurrentSensorView_ViewLockStateChanged(locked = it) }
        )
        _pulseOximeterView = PulseOximeterView(
            requireNotNull(_medWandController),
            locked = { CurrentSensorView_ViewLockStateChanged(locked = it) }
        )
        _stethoscopeView = StethoscopeView(
            requireNotNull(_medWandController),
            File(activity.filesDir, "captures.txt"),
            { CurrentSensorView_ViewLockStateChanged(locked = it) }
        )
        _cameraView = CameraView(
            requireNotNull(_medWandController),
            activity,
            File(activity.filesDir, "camera-captures"),
            { RequestMedWandUsbPermission(activity.getSystemService(Activity.USB_SERVICE) as UsbManager) },
            { CurrentSensorView_ViewLockStateChanged(locked = it) }
        )
        _ecgView = EcgView(
            requireNotNull(_medWandController),
            capturesFile = File(activity.filesDir, "captures.txt"),
            locked = { CurrentSensorView_ViewLockStateChanged(locked = it) }
        )
        _medWandController?.Configure(null)

        DeviceInformation()

        ToolButtonThermometerEnabled = true
        ToolButtonPulseOximeterEnabled = true
        ToolButtonStethoscopeEnabled = _medWandController?.HasValidStethoscope == true
        ToolButtonCameraEnabled =
            _medWandController?.CanUseCamera == true && _medWandController?.HasValidOtoscope == true
        ToolButtonEcgEnabled = true
        ToolButtonSummaryEnabled = false
        ToolButtonExitEnabled = true
        UpdateStatus(GeneralStatus)
    }

    /**
     * Populates the placeholder area with application and connected-device
     * details read from the initialized SDK controller.
     */
    private fun DeviceInformation() {
        if (_medWandController?.IsConnected != true) {
            throw Exception("MedWand not connected.")
        }

        PlaceholderInfo = """
            Application Information:
            --------------------------------
            Company: ${Settings.AppCompany}
            Product: ${Settings.AppProduct} (c)${Settings.AppCopyright}
            Version: ${Settings.AppVersion}
            Build: ${Settings.AppBuild}

            Device Information:
            --------------------------------
            ComPort: ${_medWandController?.ComPort}
            VendorId: ${_medWandController?.VendorId}
            ProductId: ${_medWandController?.ProductId}
            DeviceId: ${_medWandController?.DeviceId}
            UDI: ${_medWandController?.Udi}
            DeviceState: ${_medWandController?.DeviceState}
            IsConnected: ${_medWandController?.IsConnected}
            IsInitialized: ${_medWandController?.IsInitialized}
            IsBootloaderMode: ${_medWandController?.IsBootloaderMode(false)}
            Firmware: ${_medWandController?.FirmwareVersion}
            Generation: ${_medWandController?.Generation}
            Camera: ${_medWandController?.CameraModel}
        """.trimIndent()
    }

    /**
     * Updates toolbar enabled state for the currently licensed and connected workflows.
     */
    private fun SetNavigation(enabled: Boolean, exitEnabled: Boolean) {
        ToolButtonThermometerEnabled = enabled
        ToolButtonPulseOximeterEnabled = enabled
        ToolButtonStethoscopeEnabled = enabled && _medWandController?.HasValidStethoscope == true
        ToolButtonCameraEnabled = enabled &&
                _medWandController?.CanUseCamera == true && _medWandController?.HasValidOtoscope == true
        ToolButtonEcgEnabled = enabled && _medWandController?.HasValidEcg == true
        ToolButtonSummaryEnabled = enabled
        ToolButtonExitEnabled = exitEnabled
    }

    /**
     * Updates the shell status bar text shown at the bottom of the app.
     */
    private fun UpdateStatus(status: String) {
        StatusMessage = status
    }

    /**
     * Deactivates the current workflow, installs the next active workflow, and
     * activates it so SDK events route to the new content view.
     */
    private fun ShowView(sensorView: ISensorView?) {
        _currentSensorView?.Deactivate()
        _currentSensorView?.ViewLockStateChanged = null

        _currentSensorView = sensorView

        if (sensorView == null) {
            return
        }

        sensorView.ViewLockStateChanged = { CurrentSensorView_ViewLockStateChanged(it) }
        sensorView.Activate()
    }

    /** Releases only the normal SDK controller and its callbacks. */
    private fun ReleaseMedWandController() {
        _medWandController?.StopSensor()
        _medWandController?.OnLicenseError = {}
        _medWandController?.OnDeviceError = {}
        _medWandController?.OnDeviceStateChanged = {}
        _medWandController?.OnReadingStateChanged = {}
        _medWandController?.OnReadingReceived = {}
        _medWandController?.Dispose()
        _medWandController = null
    }

    /**
     * Stops the active workflow, clears SDK callbacks, stops any active sensor,
     * and closes the controller.
     */
    fun Cleanup() {
        SetNavigation(false, false)
        UpdateStatus("Cleaning up")

        ShowView(null)

        _thermometerView?.close()
        _pulseOximeterView?.close()
        _stethoscopeView?.close()
        _cameraView?.close()
        _ecgView?.close()
        _thermometerView = null
        _pulseOximeterView = null
        _stethoscopeView = null
        _cameraView = null
        _ecgView = null

        // Stop the active sensor and release callbacks/serial ownership.
        ReleaseMedWandController()
    }

    /** Shows the Thermometer workflow when the toolbar action is enabled. */
    fun Thermometer_Click() {
        _thermometerView?.let { ShowView(it) }
    }

    /** Shows the Pulse Oximeter workflow when the toolbar action is enabled. */
    fun PulseOximeter_Click() {
        _pulseOximeterView?.let { ShowView(it) }
    }

    /** Shows the Stethoscope workflow when Android audio permission is available. */
    suspend fun Stethoscope_Click() {
        if (EnsureAudioPermission()) {
            _stethoscopeView?.let { ShowView(it) }
        } else {
            MessageBox(
                "Android microphone permission is required to use the Stethoscope.",
                "Stethoscope Permission",
                listOf(MessageBoxResult.OK)
            )
        }
    }

    /** Requests Android camera access, then opens the Camera workflow. */
    suspend fun Camera_Click() {
        if (EnsureCameraPermission()) {
            _cameraView?.let { ShowView(it) }
        } else {
            MessageBox(
                "Android camera permission is required to use the Otoscope and Dermatoscope.",
                "Camera Permission",
                listOf(MessageBoxResult.OK)
            )
        }
    }

    /** Shows the ECG workflow when the toolbar action is enabled. */
    fun Ecg_Click() {
        _ecgView?.let { ShowView(it) }
    }

    /**
     * Confirms exit, performs SDK cleanup, updates shutdown status, and
     * terminates the Android task/process.
     */
    suspend fun Exit_Click() {
        if (MessageBox(
                "Are you sure you want to exit?",
                "Exit Confirmation",
                listOf(MessageBoxResult.Yes, MessageBoxResult.No)
            ) == MessageBoxResult.Yes
        ) {
            Cleanup()
            UpdateStatus("Shutting down...")
            activity.finishAndRemoveTask()
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }

    /** Placeholder for the main-frame navigation callback; no runtime work is needed. */
    fun MainFrame_Navigated() {
    }

    /**
     * Recomputes toolbar state after a workflow requests navigation locking.
     * Licensed sensor buttons remain available; Summary stays non-interactive.
     */
    private fun CurrentSensorView_ViewLockStateChanged(locked: Boolean) {
        ToolButtonThermometerEnabled = true
        ToolButtonPulseOximeterEnabled = true
        ToolButtonStethoscopeEnabled = _medWandController?.HasValidStethoscope == true
        ToolButtonCameraEnabled =
            _medWandController?.CanUseCamera == true && _medWandController?.HasValidOtoscope == true
        ToolButtonEcgEnabled = true
        ToolButtonSummaryEnabled = false
        ToolButtonExitEnabled = true
    }

    /**
     * Routes SDK device errors to the active workflow so it can update its
     * action button state.
     */
    private fun MedWandController_MedWandDeviceError(deviceError: MedWandDeviceError) {
        _currentSensorView?.OnDeviceError(deviceError)
    }

    /** Device-state changes are observed by the SDK callback but do not alter UI state here. */
    private fun MedWandController_DeviceStateChanged() {
    }

    /**
     * Routes SDK reading-state text to the active workflow status area.
     */
    private fun MedWandController_ReadingStateChanged(readingState: ReadingState) {
        _currentSensorView?.OnReadingStateChanged(readingState)
    }

    /**
     * Routes real SDK readings for supported sensors to the currently active
     * workflow after normalizing the SDK sensor-type string.
     */
    private fun MedWandController_ReadingReceived(reading: MedWandReading) {
        val sensorType = MedWandSensorFromReading(reading) ?: return

        when (sensorType) {
            MedWandSensor.Thermometer -> _currentSensorView?.OnReadingReceived(reading)
            MedWandSensor.PulseOximeter -> _currentSensorView?.OnReadingReceived(reading)
            MedWandSensor.Ecg -> _currentSensorView?.OnReadingReceived(reading)
            else -> Unit
        }
    }

    /**
     * Converts the SDK reading sensor type into the enum used by application
     * workflow routing.
     */
    private fun MedWandSensorFromReading(reading: MedWandReading): MedWandSensor? {
        val sensorType = reading.SensorType.orEmpty().trim()
        return MedWandSensor.values().firstOrNull { it.name.equals(sensorType, ignoreCase = true) }
            ?: when (sensorType.lowercase()) {
                // The Android SDK emits "spo2" for Pulse Oximeter readings.
                "spo2" -> MedWandSensor.PulseOximeter
                // The Android SDK emits "ecg" for ECG readings.
                "ecg" -> MedWandSensor.Ecg
                else -> null
            }
    }

    /** Requests Android camera access before opening the Camera workflow. */
    private suspend fun EnsureCameraPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true

        val launcher = _cameraPermissionLauncher ?: return false
        val result = CompletableDeferred<Boolean>()
        _cameraPermissionResult = result
        launcher.launch(Manifest.permission.CAMERA)
        return result.await()
    }

    /** Requests Android microphone access before opening the Stethoscope workflow. */
    private suspend fun EnsureAudioPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true

        val launcher = _audioPermissionLauncher ?: return false
        val result = CompletableDeferred<Boolean>()
        _audioPermissionResult = result
        launcher.launch(Manifest.permission.RECORD_AUDIO)
        return result.await()
    }

    /**
     * Publishes a dialog request into Compose state and suspends until the user
     * selects one of the provided buttons.
     */
    private suspend fun MessageBox(
        message: String,
        title: String,
        buttons: List<MessageBoxResult>
    ): MessageBoxResult {
        val result = CompletableDeferred<MessageBoxResult>()
        MessageBox = MessageBoxRequest(message, title, buttons, result)
        val selected = result.await()
        MessageBox = null
        return selected
    }

    /**
     * Renders the shell content frame, showing placeholder device information
     * until a workflow view is active.
     */
    @Composable
    fun MainFrame() {
        val current = _currentSensorView
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ActionButtonDisabled)
        ) {
            if (FirmwareUpdateVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Firmware Update",
                        color = Color.Black,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = FirmwareUpdateState,
                        color = Color.Black,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = FirmwareUpdateProgress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "$FirmwareUpdateProgress%",
                        color = Color.Black,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = FirmwareUpdateMessage,
                        color = Color.Black,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (current == null) {
                Text(
                    text = PlaceholderInfo,
                    color = Color.Black,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Left,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(15.dp)
                )
            } else {
                current.Render()
            }
        }
    }
}

/**
 * Thermometer workflow view that forwards lifecycle, readings, errors, and
 * button clicks to its view model.
 */
private class ThermometerView(
    medWandController: MedWandController,
    locked: (Boolean) -> Unit
) : ISensorView {
    private val _viewModel = ThermometerViewModel(medWandController, locked)

    override val MedWandSensor: MedWandSensor = com.medwand.sdk_core.MedWandSensor.Thermometer
    override var ViewLockStateChanged: ((Boolean) -> Unit)? = null

    /** Initializes Thermometer display state when the view becomes active. */
    override fun Activate() {
        _viewModel.Initialize()
    }

    /** Thermometer deactivation has no view-local state to stop here. */
    override fun Deactivate() {
    }

    /** Forwards SDK reading-state changes into Thermometer status text. */
    override fun OnReadingStateChanged(readingState: ReadingState) =
        _viewModel.OnReadingStateChanged(readingState)

    /** Forwards Thermometer SDK readings into the view model display state. */
    override fun OnReadingReceived(reading: MedWandReading) =
        _viewModel.OnReadingReceived(reading)

    /** Forwards device error state so the action button can be enabled or disabled. */
    override fun OnDeviceError(error: MedWandDeviceError?) =
        _viewModel.OnDeviceError(error)

    /** No extra Thermometer view resources are owned beyond the view model. */
    override fun close() {
        _viewModel.close()
    }

    /** Renders the Thermometer content using the current view-model state. */
    @Composable
    override fun Render() {
        ThermometerView(_viewModel)
    }
}

/**
 * Holds Thermometer workflow state, formats object-temperature readings, and
 * calls the SDK start/stop operations for the Thermometer sensor.
 */
private class ThermometerViewModel(
    private val _controller: MedWandController,
    private val _setLocked: (Boolean) -> Unit
) : AutoCloseable {
    private var _reading: MedWandReading? = null

    var StatusMessage by mutableStateOf("Starting")
    var ButtonActionState by mutableStateOf(ActionState.Idle)
    var ButtonActionText by mutableStateOf("Start")
    var ButtonActionTag by mutableStateOf(ActionState.Idle.name)
    var TempObject by mutableStateOf("--")

    /**
     * Resets the displayed Thermometer reading and action state when the view
     * is activated.
     */
    fun Initialize() {
        _reading = MedWandReading()
        UpdateReadingText()
        SetAction(ActionState.Idle)
    }

    /** Updates the visible workflow status from the SDK reading state. */
    fun OnReadingStateChanged(state: ReadingState) {
        SetStatus(state.toString())
    }

    /** Stores the latest SDK Thermometer reading and refreshes TempObject. */
    fun OnReadingReceived(reading: MedWandReading) {
        _reading = reading
        UpdateReadingText()
    }

    /** Updates action availability from SDK device error presence. */
    fun OnDeviceError(error: MedWandDeviceError?) {
        try {
            SetAction(if (error == null) ActionState.Idle else ActionState.Disabled)
        } catch (outerEx: Exception) {
            println(outerEx.message)
        }
    }

    /** Dispatches the action button to Thermometer start or stop behavior. */
    fun OnActionButtonClick() {
        when (ButtonActionState) {
            ActionState.Idle -> StartSensor()
            ActionState.Busy -> StopSensor(fromTimeout = false)
            ActionState.Disabled -> Unit
        }
    }

    /** Starts the real Thermometer sensor through the SDK and enters Stop state on success. */
    private fun StartSensor() {
        try {
            SetAction(ActionState.Disabled)
            _setLocked(true)

            // Starts Thermometer readings through the SDK; readings arrive later
            // through MedWandController.OnReadingReceived.
            if (_controller.StartThermometer()) {
                SetAction(ActionState.Busy)
            } else {
                SetAction(ActionState.Idle)
                _setLocked(false)
            }
        } catch (outerEx: Exception) {
            println(outerEx.message)
            SetAction(ActionState.Disabled)
            _setLocked(false)
        }
    }

    /** Stops the active Thermometer sensor unless the stop was already handled by timeout. */
    private fun StopSensor(fromTimeout: Boolean) {
        try {
            SetAction(ActionState.Disabled)

            if (!fromTimeout) {
                // Stops the SDK's active sensor without requesting timeout handling.
                _controller.StopSensor()
            }

            SetAction(ActionState.Idle)
        } catch (outerEx: Exception) {
            println(outerEx.message)
            SetAction(ActionState.Idle)
        } finally {
            _setLocked(false)
        }
    }

    /** Formats the latest SDK object-temperature value for the main reading display. */
    private fun UpdateReadingText() {
        val reading = _reading ?: return

        fun FormatTemp(raw: String): String =
            if (raw.isEmpty() || raw == "Reading") "--" else "$raw F"

        TempObject = FormatTemp(reading.TempObject.orEmpty())
    }

    /** Updates the Thermometer workflow status label. */
    private fun SetStatus(value: String) {
        StatusMessage = value
    }

    /** Applies the action state to button text/color semantics and status text. */
    private fun SetAction(actionState: ActionState) {
        when (actionState) {
            ActionState.Idle -> {
                SetActionButtonIdle()
                _setLocked(false)
            }

            ActionState.Busy -> {
                _setLocked(true)
                SetActionButtonBusy()
            }

            ActionState.Disabled -> {
                SetActionButtonDisabled()
                _setLocked(false)
            }
        }

        SetStatus(_controller.ReadingState.toString())
    }

    /** Sets the action button to the active-reading Stop state. */
    private fun SetActionButtonBusy() {
        ButtonActionState = ActionState.Busy
        ButtonActionText = "Stop"
        ButtonActionTag = ActionState.Busy.name
    }

    /** Sets the action button to the idle Start state. */
    private fun SetActionButtonIdle() {
        ButtonActionState = ActionState.Idle
        ButtonActionText = "Start"
        ButtonActionTag = ActionState.Idle.name
    }

    /** Clears the action text while the button is disabled. */
    private fun SetActionButtonDisabled() {
        ButtonActionState = ActionState.Disabled
        ButtonActionText = ""
        ButtonActionTag = ActionState.Disabled.name
    }

    /** No additional Thermometer model resources are owned. */
    override fun close() {
    }
}

/**
 * Pulse Oximeter workflow view that forwards lifecycle, readings, errors, and
 * button clicks to its view model.
 */
private class PulseOximeterView(
    medWandController: MedWandController,
    locked: (Boolean) -> Unit
) : ISensorView {
    private val _medWandController = medWandController
    private val _viewModel = PulseOximeterViewModel(_medWandController, locked)

    override val MedWandSensor: MedWandSensor = com.medwand.sdk_core.MedWandSensor.PulseOximeter
    override var ViewLockStateChanged: ((Boolean) -> Unit)? = null

    /** Initializes Pulse Oximeter display state when the view becomes active. */
    override fun Activate() {
        _viewModel.Initialize()
    }

    /** Pulse Oximeter deactivation has no view-local state to stop here. */
    override fun Deactivate() {
    }

    /** Forwards SDK reading-state changes into Pulse Oximeter status text. */
    override fun OnReadingStateChanged(readingState: ReadingState) =
        _viewModel.OnReadingStateChanged(readingState)

    /** Forwards Pulse Oximeter SDK readings into the view model display state. */
    override fun OnReadingReceived(reading: MedWandReading) =
        _viewModel.OnReadingReceived(reading)

    /** Forwards device error state so the action button can be enabled or disabled. */
    override fun OnDeviceError(error: MedWandDeviceError?) =
        _viewModel.OnDeviceError(error)

    /** No extra Pulse Oximeter view resources are owned beyond the view model. */
    override fun close() {
        _viewModel.close()
    }

    /** Renders the Pulse Oximeter content using the current view-model state. */
    @Composable
    override fun Render() {
        PulseOximeterView(_viewModel)
    }
}

/**
 * Holds Pulse Oximeter workflow state, formats SpO2 and pulse-rate readings,
 * and calls the SDK start/stop operations for the Pulse Oximeter sensor.
 */
private class PulseOximeterViewModel(
    private val _controller: MedWandController,
    private val _setLocked: (Boolean) -> Unit
) : AutoCloseable {
    private var _reading: MedWandReading? = null

    var StatusMessage by mutableStateOf("Starting")
    var ButtonActionState by mutableStateOf(ActionState.Idle)
    var ButtonActionText by mutableStateOf("Start")
    var ButtonActionTag by mutableStateOf(ActionState.Idle.name)
    var SpO2 by mutableStateOf("SpO2 : --")
    var PulseRate by mutableStateOf("Pulse Rate : --")

    /**
     * Resets the reading object and action state when the Pulse Oximeter view
     * is activated.
     */
    fun Initialize() {
        _reading = MedWandReading().apply {
            TimeStamp = Instant.now()
            Status = ""
            Index = 1
            Count = 0
            SensorType = MedWandSensor.PulseOximeter.name
            TempAmbient = ""
            TempObject = ""
            PulseRate = null
            Spo2 = null
            EcgData = null
        }

        UpdateReadingText()
        SetAction(ActionState.Idle)
    }

    /** Updates the visible workflow status from the SDK reading state. */
    fun OnReadingStateChanged(state: ReadingState) {
        SetStatus(state.toString())
    }

    /** Stores the latest SDK Pulse Oximeter reading and refreshes both labels. */
    fun OnReadingReceived(reading: MedWandReading) {
        _reading = reading
        UpdateReadingText()
    }

    /** Updates action availability from SDK device error presence. */
    fun OnDeviceError(error: MedWandDeviceError?) {
        try {
            SetAction(if (error == null) ActionState.Idle else ActionState.Disabled)
        } catch (outerEx: Exception) {
            println(outerEx.message)
        }
    }

    /** Dispatches the action button to Pulse Oximeter start or stop behavior. */
    fun OnActionButtonClick() {
        when (ButtonActionState) {
            ActionState.Idle -> StartSensor()
            ActionState.Busy -> StopSensor(fromTimeout = false)
            ActionState.Disabled -> Unit
        }
    }

    /** Starts the real Pulse Oximeter sensor through the SDK and enters Stop state on success. */
    private fun StartSensor() {
        try {
            SetAction(ActionState.Disabled)
            _setLocked(true)

            // Starts SpO2 and pulse-rate readings through the SDK; values arrive
            // through MedWandController.OnReadingReceived.
            if (_controller.StartPulseOximeter()) {
                SetAction(ActionState.Busy)
            } else {
                SetAction(ActionState.Idle)
                _setLocked(false)
            }
        } catch (outerEx: Exception) {
            println(outerEx.message)
            SetAction(ActionState.Disabled)
            _setLocked(false)
        }
    }

    /** Stops the active Pulse Oximeter sensor unless the stop was already handled by timeout. */
    private fun StopSensor(fromTimeout: Boolean) {
        try {
            SetAction(ActionState.Disabled)

            if (!fromTimeout) {
                // Stops the SDK's active sensor without requesting timeout handling.
                _controller.StopSensor()
            }

            SetAction(ActionState.Idle)
        } catch (outerEx: Exception) {
            println(outerEx.message)
            SetAction(ActionState.Idle)
        } finally {
            _setLocked(false)
        }
    }

    /** Updates the SpO2 and pulse-rate labels from the latest SDK reading. */
    private fun UpdateReadingText() {
        val reading = _reading ?: return
        reading.Spo2?.takeIf { it.isNotBlank() && it != "--" }?.let { SpO2 = "SpO2 : $it" }
        reading.PulseRate?.takeIf { it.isNotBlank() && it != "--" }?.let { PulseRate = "PulseRate : $it" }
    }

    /** Updates the Pulse Oximeter workflow status label. */
    private fun SetStatus(value: String) {
        StatusMessage = value
    }

    /** Applies the action state to button text/color semantics and status text. */
    private fun SetAction(actionState: ActionState) {
        when (actionState) {
            ActionState.Idle -> {
                SetActionButtonIdle()
                _setLocked(false)
            }

            ActionState.Busy -> {
                _setLocked(true)
                SetActionButtonBusy()
            }

            ActionState.Disabled -> {
                SetActionButtonDisabled()
                _setLocked(false)
            }
        }

        SetStatus(_controller.ReadingState.toString())
    }

    /** Sets the action button to the active-reading Stop state. */
    private fun SetActionButtonBusy() {
        ButtonActionState = ActionState.Busy
        ButtonActionText = "Stop"
        ButtonActionTag = ActionState.Busy.name
    }

    /** Sets the action button to the idle Start state. */
    private fun SetActionButtonIdle() {
        ButtonActionState = ActionState.Idle
        ButtonActionText = "Start"
        ButtonActionTag = ActionState.Idle.name
    }

    /** Clears the action text while the button is disabled. */
    private fun SetActionButtonDisabled() {
        ButtonActionState = ActionState.Disabled
        ButtonActionText = ""
        ButtonActionTag = ActionState.Disabled.name
    }

    /** No additional Pulse Oximeter model resources are owned. */
    override fun close() {
    }
}


/** Stethoscope workflow: choose a mode, record, and save captured WAV output. */
private class StethoscopeView(
    private val _controller: MedWandController,
    private val _capturesFile: File,
    private val _setLocked: (Boolean) -> Unit
) : ISensorView {
    private var _previousRecordedFramesHandler: ((ByteArray) -> Unit)? = null
    private var _captured = 0
    private var _isActivated = false

    override val MedWandSensor: MedWandSensor = com.medwand.sdk_core.MedWandSensor.Stethoscope
    override var ViewLockStateChanged: ((Boolean) -> Unit)? = null

    var StatusMessage by mutableStateOf("Off : Ready [0 Captured]")
    var ButtonActionState by mutableStateOf(ActionState.Idle)
    var ButtonActionText by mutableStateOf("Start Recording")
    var StethoscopeMode by mutableStateOf(StethoscopeHelpers.MicrophoneModes.Off)

    override fun Activate() {
        if (!_isActivated) {
            _isActivated = true
            _controller.Stethoscope?.let { stethoscope ->
                _previousRecordedFramesHandler = stethoscope.RecordedFramesReady
                stethoscope.RecordedFramesReady = { bytes ->
                    _previousRecordedFramesHandler?.invoke(bytes)
                    OnRecordedFramesReady(bytes)
                }
            }
        }
        SetAction(ActionState.Idle)
        UpdateStatus()
    }

    override fun Deactivate() {
        if (ButtonActionState == ActionState.Busy) StopCapture()
        SetStethoscopeMode(StethoscopeHelpers.MicrophoneModes.Off)
        _controller.Stethoscope?.RecordedFramesReady = _previousRecordedFramesHandler
        _previousRecordedFramesHandler = null
        _isActivated = false
    }

    override fun OnReadingStateChanged(readingState: ReadingState) = UpdateStatus()
    override fun OnReadingReceived(reading: MedWandReading) = Unit
    override fun OnDeviceError(error: MedWandDeviceError?) = SetAction(if (error == null) ActionState.Idle else ActionState.Disabled)

    fun SetStethoscopeMode(mode: StethoscopeHelpers.MicrophoneModes) {
        if (ButtonActionState == ActionState.Busy) StopCapture()
        runCatching { _controller.SetStethoscopeMode(mode, null) }
            .onFailure { println(it.message) }
        StethoscopeMode = _controller.StethoscopeMode
        UpdateStatus()
    }

    fun OnActionButtonClick() {
        when (ButtonActionState) {
            ActionState.Idle -> StartCapture()
            ActionState.Busy -> StopCapture()
            ActionState.Disabled -> Unit
        }
    }

    private fun StartCapture() {
        if (_controller.StethoscopeMode == StethoscopeHelpers.MicrophoneModes.Off) {
            StatusMessage = "Select Heart, Lungs, or Bowel before recording."
            return
        }

        SetAction(ActionState.Disabled)
        runCatching { _controller.StartRecording() }
            .onSuccess {
                SetAction(ActionState.Busy)
            }
            .onFailure {
                println(it.message)
                SetAction(ActionState.Idle)
            }
    }

    private fun StopCapture() {
        SetAction(ActionState.Disabled)
        runCatching { _controller.StopRecording() }
            .onFailure { println(it.message) }
        SetAction(ActionState.Idle)
    }

    private fun SetAction(actionState: ActionState) {
        ButtonActionState = actionState
        ButtonActionText = when (actionState) {
            ActionState.Idle -> "Start Recording"
            ActionState.Busy -> "Stop Recording"
            ActionState.Disabled -> ""
        }
        _setLocked(actionState == ActionState.Busy)
        UpdateStatus()
    }

    private fun UpdateStatus() {
        val readingState = when (_controller.ReadingState) {
            ReadingState.Stopped -> "Ready"
            ReadingState.Starting,
            ReadingState.Started,
            ReadingState.Reading -> "On"
            else -> _controller.ReadingState.toString()
        }
        StatusMessage = "${_controller.StethoscopeMode} : $readingState [$_captured Captured]"
    }

    private fun OnRecordedFramesReady(bytes: ByteArray) {
        runCatching {
            _capturesFile.appendText(
                "[${Instant.now()}] ${_controller.StethoscopeModel} ${_controller.StethoscopeMode} -> ${bytes.size}\n"
            )
            _captured++
            UpdateStatus()
        }.onFailure { println(it.message) }
    }

    override fun close() = Deactivate()

    @Composable
    override fun Render() {
        StethoscopeView(this)
    }
}


/** Camera workflow with live Dermatoscope/Otoscope preview and still capture. */
private class CameraView(
    private val _controller: MedWandController,
    private val _activity: Activity,
    private val _capturesDirectory: File,
    private val _requestMedWandUsbPermission: suspend () -> Boolean,
    private val _setLocked: (Boolean) -> Unit
) : ISensorView {
    private val _scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var _previewJob: Job? = null
    private var _previewImageView: ImageView? = null
    private var _previousRecordedFrameHandler: ((ByteArray) -> Unit)? = null
    private var _previousLedIntensityHandler: ((Int) -> Unit)? = null
    private var _isActivated = false
    private var _previewSession = 0L
    private var _cameraDeviceKey: String? = null

    override val MedWandSensor: MedWandSensor = com.medwand.sdk_core.MedWandSensor.Otoscope
    override var ViewLockStateChanged: ((Boolean) -> Unit)? = null

    var HasFrame by mutableStateOf(false)
    var CameraMode by mutableStateOf(CameraHelper.CameraModes.Off)
    var StatusMessage by mutableStateOf("Off : Ready [0 Captured]")
    var CapturedCount by mutableStateOf(0)
    var IsStarting by mutableStateOf(false)
    var LedIntensity by mutableStateOf(0)
    var LedIntensityMax by mutableStateOf(0)
    var LedControlAvailable by mutableStateOf(false)
    var LedIntensityAdjustable by mutableStateOf(false)
    var FocusValue by mutableStateOf(0)
    var FocusValueMin by mutableStateOf(0)
    var FocusValueMax by mutableStateOf(0)
    var ManualFocusEnabled by mutableStateOf(false)
    var AutoFocusAvailable by mutableStateOf(false)
    var ManualFocusAvailable by mutableStateOf(false)
    var FocusControlAvailable by mutableStateOf(false)

    override fun Activate() {
        if (!_isActivated) {
            _isActivated = true
            _controller.Camera?.let { camera ->
                _previousRecordedFrameHandler = camera.RecordedFrameReady
                camera.RecordedFrameReady = { bytes ->
                    _previousRecordedFrameHandler?.invoke(bytes)
                    OnRecordedFrameReady(bytes)
                }
            }
            _previousLedIntensityHandler = _controller.OnLedIntensityChanged
            _controller.OnLedIntensityChanged = { intensity ->
                _previousLedIntensityHandler?.invoke(intensity)
                _activity.runOnUiThread {
                    LedIntensity = intensity.coerceIn(0, LedIntensityMax.coerceAtLeast(0))
                }
            }
            _controller.Camera?.FrameReady = { frameBytes -> OnFrameReady(frameBytes) }
        }

        CameraMode = _controller.CameraMode
        if (_controller.CameraIsMonitoring) UpdateCameraControls() else ResetCameraControls()
        UpdateStatus(if (_controller.CameraIsMonitoring) "On" else "Ready")
    }

    override fun Deactivate() {
        _isActivated = false
        _previewSession++
        _previewJob?.cancel()
        _previewJob = null
        IsStarting = false
        _setLocked(false)

        runCatching { _controller.StopSensor() }
            .onFailure { println(it.message) }

        _controller.Camera?.FrameReady = null
        _controller.Camera?.RecordedFrameReady = _previousRecordedFrameHandler
        _previousRecordedFrameHandler = null
        _controller.OnLedIntensityChanged = _previousLedIntensityHandler
        _previousLedIntensityHandler = null
        _previewImageView?.setImageDrawable(null)
        CameraMode = CameraHelper.CameraModes.Off
        HasFrame = false
        ResetCameraControls()
        UpdateStatus("Ready")
    }

    override fun OnReadingStateChanged(readingState: ReadingState) {
        if (CameraMode != CameraHelper.CameraModes.Off) {
            UpdateStatus(if (_controller.CameraIsMonitoring) "On" else readingState.toString())
        }
    }

    override fun OnReadingReceived(reading: MedWandReading) = Unit

    override fun OnDeviceError(error: MedWandDeviceError?) {
        if (error != null) {
            StatusMessage = "${CameraMode.displayName()} : Error - ${error.Exception.message ?: error.Code.toString()} [$CapturedCount Captured]"
        }
    }

    fun SelectMode(mode: CameraHelper.CameraModes) {
        if (!_isActivated || IsStarting) return
        if (mode == CameraHelper.CameraModes.Off) {
            StopPreview()
        } else {
            StartPreview(mode)
        }
    }

    fun Capture() {
        if (!_controller.CameraIsMonitoring || IsStarting) return
        runCatching { _controller.StartRecording() }
            .onFailure {
                StatusMessage = "${CameraMode.displayName()} : Capture failed - ${it.message.orEmpty()} [$CapturedCount Captured]"
            }
    }

    fun SetLedIntensity(value: Int) {
        if (!LedControlAvailable) return
        val target = value.coerceIn(0, LedIntensityMax)
        if (target == LedIntensity) return
        _scope.launch {
            runCatching { withContext(Dispatchers.IO) { _controller.CameraSetLedIntensity(target) } }
                .onFailure { ShowControlError("LED", it) }
        }
    }

    fun ToggleLed() = SetLedIntensity(if (LedIntensity > 0) 0 else LedIntensityMax)

    fun SetManualFocus(enabled: Boolean) {
        if (!_controller.CameraIsMonitoring) return
        if (enabled && !ManualFocusAvailable) return
        if (!enabled && !AutoFocusAvailable) return

        _scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    _controller.CameraSetFocusMode(
                        if (enabled) CameraHelper.FocusModes.Manual else CameraHelper.FocusModes.Auto,
                        true
                    )
                }
            }.onFailure { error ->
                ShowControlError("focus mode", error)
            }
            ManualFocusEnabled = _controller.CameraFocusModes == CameraHelper.FocusModes.Manual
        }
    }

    fun SetFocusValue(value: Int) {
        if (!ManualFocusAvailable || !ManualFocusEnabled || FocusValueMax < FocusValueMin) return
        val target = value.coerceIn(FocusValueMin, FocusValueMax)

        _scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { _controller.CameraSetFocusValue(target.toDouble()) }
            }.onFailure { error ->
                ShowControlError("manual focus", error)
            }.onSuccess {
                FocusValue = target
            }
        }
    }

    fun MoveOtoscope(horizontal: Int? = null, vertical: Int? = null) {
        if (CameraMode != CameraHelper.CameraModes.Otoscope || !_controller.CameraIsMonitoring) return
        runCatching {
            _controller.CameraMove(horizontal?.times(MOVE_STEP), vertical?.times(MOVE_STEP))
        }.onFailure { ShowControlError("move", it) }
    }

    fun ZoomOtoscope(increment: Int) {
        if (CameraMode != CameraHelper.CameraModes.Otoscope || !_controller.CameraIsMonitoring) return
        runCatching { _controller.CameraZoom(increment * ZOOM_STEP) }
            .onFailure { ShowControlError("zoom", it) }
    }

    fun RadiusOtoscope(increment: Int) {
        if (CameraMode != CameraHelper.CameraModes.Otoscope || !_controller.CameraIsMonitoring) return
        runCatching { _controller.CameraRadius(increment * RADIUS_STEP) }
            .onFailure { ShowControlError("radius", it) }
    }

    fun ResetOtoscope() {
        if (CameraMode != CameraHelper.CameraModes.Otoscope || !_controller.CameraIsMonitoring) return
        runCatching { _controller.CameraReset() }
            .onFailure { ShowControlError("reset", it) }
    }

    private fun UpdateCameraControls() {
        LedIntensityMax = _controller.CameraLedIntensityMax.coerceAtLeast(0)
        LedIntensity = _controller.LedIntensity.coerceIn(0, LedIntensityMax.coerceAtLeast(0))
        LedIntensityAdjustable = _controller.CameraLedIntensityAdjustable
        LedControlAvailable = _controller.CameraIsMonitoring && LedIntensityMax > 0

        val focusInfo = _controller.CameraFocusInfo
        FocusValueMin = focusInfo?.FocusMinimum ?: 0
        FocusValueMax = focusInfo?.FocusMaximum ?: 0
        if (FocusValueMax >= FocusValueMin) {
            FocusValue = FocusValue.coerceIn(FocusValueMin, FocusValueMax)
        }
        AutoFocusAvailable = focusInfo?.HasAutoFocus == true
        ManualFocusAvailable = focusInfo?.HasManualFocus == true
        ManualFocusEnabled = _controller.CameraFocusModes == CameraHelper.FocusModes.Manual
        FocusControlAvailable = _controller.CameraIsMonitoring && (AutoFocusAvailable || ManualFocusAvailable)
    }

    private fun ResetCameraControls() {
        LedIntensity = 0
        LedIntensityMax = 0
        LedControlAvailable = false
        LedIntensityAdjustable = false
        FocusValue = 0
        FocusValueMin = 0
        FocusValueMax = 0
        ManualFocusEnabled = false
        AutoFocusAvailable = false
        ManualFocusAvailable = false
        FocusControlAvailable = false
    }

    private fun ShowControlError(name: String, error: Throwable) {
        StatusMessage = "${CameraMode.displayName()} : $name failed - ${error.message.orEmpty()} [$CapturedCount Captured]"
    }

    fun AttachPreviewView(view: ImageView) {
        _previewImageView = view
    }

    fun DetachPreviewView(view: ImageView) {
        if (_previewImageView === view) {
            _previewImageView = null
        }
        view.setImageDrawable(null)
    }

    private fun StartPreview(mode: CameraHelper.CameraModes) {
        val previewView = _previewImageView ?: return
        val cameraDeviceKey = CurrentCameraDeviceKey()
        val session = ++_previewSession
        _previewJob?.cancel()
        HasFrame = false
        previewView.setImageDrawable(null)
        IsStarting = true
        CameraMode = mode
        UpdateStatus("Starting")
        _setLocked(true)

        _previewJob = _scope.launch {
            val started = runCatching {
                val cameraChanged = _cameraDeviceKey != null && cameraDeviceKey != _cameraDeviceKey
                if (cameraChanged) {
                    check(_requestMedWandUsbPermission()) {
                        "USB permission was not granted for the newly connected MedWand."
                    }
                }
                withContext(Dispatchers.IO) {
                    if (cameraChanged) {
                        _controller.Connect()
                        check(_controller.IsConnected) { "Could not reconnect to the MedWand after changing cameras." }
                        _controller.Initialize()
                        check(_controller.IsInitialized) { "Could not initialize the MedWand after changing cameras." }
                    }
                    _controller.SetCameraMode(previewView, mode)
                }
            }.getOrElse { error ->
                if (_isActivated && session == _previewSession) {
                    StatusMessage = "${mode.displayName()} : Error - ${error.message.orEmpty()} [$CapturedCount Captured]"
                }
                false
            }

            if (!_isActivated || session != _previewSession) {
                withContext(Dispatchers.IO) { runCatching { _controller.StopSensor() } }
                return@launch
            }

            IsStarting = false
            CameraMode = if (started) _controller.CameraMode else CameraHelper.CameraModes.Off
            if (started) {
                _cameraDeviceKey = cameraDeviceKey
                UpdateCameraControls()
                if (AutoFocusAvailable) {
                    withContext(Dispatchers.IO) {
                        _controller.CameraSetFocusMode(CameraHelper.FocusModes.Auto, true)
                    }
                    ManualFocusEnabled = _controller.CameraFocusModes == CameraHelper.FocusModes.Manual
                }
                UpdateStatus("On")
            } else {
                ResetCameraControls()
                StatusMessage = "${mode.displayName()} : Error - Preview did not start [$CapturedCount Captured]"
                _setLocked(false)
            }
        }
    }

    private fun CurrentCameraDeviceKey(): String? {
        val usbManager = _activity.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values
            .filter { device ->
                (0 until device.interfaceCount).any { index ->
                    device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                }
            }
            .maxByOrNull { device ->
                listOfNotNull(device.productName, device.manufacturerName)
                    .count { it.contains("medwand", ignoreCase = true) || it.contains("camera", ignoreCase = true) }
            }
            ?.let { "${it.deviceId}:${it.deviceName}:${it.vendorId}:${it.productId}:${it.productName.orEmpty()}" }
    }

    private fun StopPreview() {
        val session = ++_previewSession
        _previewJob?.cancel()
        IsStarting = true
        UpdateStatus("Stopping")

        _previewJob = _scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { _controller.StopSensor() }
            }
            if (!_isActivated || session != _previewSession) return@launch
            IsStarting = false
            CameraMode = CameraHelper.CameraModes.Off
            HasFrame = false
            _previewImageView?.setImageDrawable(null)
            ResetCameraControls()
            _setLocked(false)
            UpdateStatus("Ready")
        }
    }

    private fun OnFrameReady(frameBytes: ByteArray) {
        if (frameBytes.isEmpty() || !_isActivated || !_controller.CameraIsMonitoring) return
        val session = _previewSession
        _activity.runOnUiThread {
            if (_isActivated && session == _previewSession && _controller.CameraIsMonitoring) {
                if (!HasFrame) UpdateCameraControls()
                HasFrame = true
            }
        }
    }

    private fun OnRecordedFrameReady(frameBytes: ByteArray) {
        val modeAtCapture = CameraMode
        _scope.launch(Dispatchers.Default) {
            try {
                val encodedImage = _controller.CameraBmpFromCapture(frameBytes).orEmpty()
                val payload = encodedImage.substringAfter("base64,", encodedImage)
                val png = if (payload.isNotBlank()) Base64.getDecoder().decode(payload) else ByteArray(0)
                if (png.isEmpty()) throw Exception("The SDK returned an empty camera frame.")

                _capturesDirectory.mkdirs()
                val file = File(
                    _capturesDirectory,
                    "${modeAtCapture.name.lowercase()}-${Instant.now().toEpochMilli()}.png"
                )
                file.writeBytes(png)

                withContext(Dispatchers.Main.immediate) {
                    CapturedCount++
                    UpdateStatus(if (_controller.CameraIsMonitoring) "On" else "Ready")
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    StatusMessage = "${modeAtCapture.displayName()} : Capture failed - ${error.message.orEmpty()} [$CapturedCount Captured]"
                }
            }
        }
    }

    private fun UpdateStatus(state: String) {
        StatusMessage = "${CameraMode.displayName()} : $state [$CapturedCount Captured]"
    }

    private companion object {
        const val MOVE_STEP = 5
        const val ZOOM_STEP = 10
        const val RADIUS_STEP = 10
    }

    override fun close() {
        Deactivate()
        _scope.cancel()
    }

    @Composable
    override fun Render() {
        CameraView(this)
    }
}

private fun CameraHelper.CameraModes.displayName(): String =
    when (this) {
        CameraHelper.CameraModes.Off -> "Off"
        CameraHelper.CameraModes.Dermatoscope -> "Dermatoscope"
        CameraHelper.CameraModes.Otoscope -> "Otoscope"
    }

/**
 * ECG workflow view that owns the SDK render target and forwards lifecycle,
 * readings, errors, and recording button clicks to its view model.
 */
private class EcgView(
    medWandController: MedWandController,
    capturesFile: File,
    locked: (Boolean) -> Unit
) : ISensorView {
    private val _medWandController = medWandController
    private val _viewModel = EcgViewModel(_medWandController, capturesFile, locked)

    override val MedWandSensor: MedWandSensor = com.medwand.sdk_core.MedWandSensor.Ecg
    override var ViewLockStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Activates ECG monitoring. Recording is controlled separately by the
     * action button through StartCapture and StopCapture.
     */
    override fun Activate() {
        _viewModel.Activate()
        _viewModel.StartSensor()
    }

    /** Stops ECG monitoring and recorded-strip callbacks when the view changes. */
    override fun Deactivate() {
        _viewModel.Deactivate()
    }

    /** Forwards SDK reading-state changes into ECG captured-count status text. */
    override fun OnReadingStateChanged(readingState: ReadingState) =
        _viewModel.OnReadingStateChanged(readingState)

    /** Stores ECG SDK readings without drawing ECG frames in application code. */
    override fun OnReadingReceived(reading: MedWandReading) =
        _viewModel.OnReadingReceived(reading)

    /** Forwards device error state so the recording action can be enabled or disabled. */
    override fun OnDeviceError(error: MedWandDeviceError?) =
        _viewModel.OnDeviceError(error)

    fun AttachRenderView(view: ImageView) {
        _medWandController.Configure(view)
    }

    fun DetachRenderView(view: ImageView) {
        view.setImageDrawable(null)
        _medWandController.Configure(null)
    }

    override fun close() {
        _viewModel.close()
    }

    @Composable
    override fun Render() {
        EcgView(_viewModel, this)
    }
}

/**
 * Holds ECG monitoring and recording state, keeps capture count status, and
 * calls the SDK's ECG, recording, and capture output operations directly.
 */
private class EcgViewModel(
    private val _medWandController: MedWandController,
    private val _capturesFile: File,
    private val _setLocked: (Boolean) -> Unit
) : AutoCloseable {
    private var _reading: MedWandReading? = null
    private var _isActivated = false
    private var _captured = 0

    var StatusMessage by mutableStateOf("Starting")
    var ButtonActionState by mutableStateOf(ActionState.Idle)
    var ButtonActionText by mutableStateOf("Start Recording")
    var ButtonActionTag by mutableStateOf(ActionState.Idle.name)

    /**
     * Prepares ECG state, subscribes to recorded-strip output, and marks the
     * workflow as monitoring.
     */
    fun Activate() {
        if (!_isActivated) {
            _isActivated = true
            val ecg = _medWandController.Ecg
            if (ecg != null) {
                // The SDK invokes this callback only when a real recorded strip
                // is ready; the handler records that SDK output.
                ecg.RecordedStripReady = { bytes -> Ecg_RecordedStripReady(bytes) }
            }
        }

        _reading = MedWandReading().apply {
            TimeStamp = Instant.now()
            Status = ""
            Index = 1
            Count = 0
            SensorType = MedWandSensor.Ecg.name
            TempAmbient = ""
            TempObject = ""
            PulseRate = null
            Spo2 = null
            EcgData = null
        }
        SetAction(ActionState.Idle)
        SetStatus("Monitoring")
    }

    /**
     * Stops ECG monitoring, removes the recorded-strip callback, and updates
     * the captured-count status.
     */
    fun Deactivate() {
        StopSensor()
        val ecg = _medWandController.Ecg
        if (ecg == null) {
            return
        }
        ecg.RecordedStripReady = null
        SetStatus("Not Monitoring")
    }

    /** Updates the ECG status label with the SDK state and current capture count. */
    fun OnReadingStateChanged(state: ReadingState) {
        SetStatus(state.toString())
    }

    /** Stores the latest SDK ECG reading; drawing remains owned by the SDK render target. */
    fun OnReadingReceived(reading: MedWandReading) {
        _reading = reading
    }

    /** Updates recording action availability from SDK device error presence. */
    fun OnDeviceError(error: MedWandDeviceError?) {
        try {
            SetAction(if (error == null) ActionState.Idle else ActionState.Disabled)
        } catch (outerEx: Exception) {
            println(outerEx.message)
        }
    }

    /**
     * Dispatches the recording action button. Idle starts recording, Busy stops
     * recording, and Disabled ignores the tap.
     */
    fun OnActionButtonClick() {
        when (ButtonActionState) {
            ActionState.Idle -> StartCapture()
            ActionState.Busy -> StopCapture()
            ActionState.Disabled -> Unit
        }
    }

    /**
     * Starts ECG monitoring through the SDK. Monitoring is separate from ECG
     * recording, which is controlled by StartCapture and StopCapture.
     */
    fun StartSensor() {
        try {
            SetAction(ActionState.Disabled)
            _setLocked(true)

            // Starts the SDK ECG monitoring stream for the configured ImageView.
            if (_medWandController.StartEcg()) {
                SetAction(ActionState.Idle)
            } else {
                SetAction(ActionState.Disabled)
                _setLocked(false)
            }
        } catch (outerEx: Exception) {
            println(outerEx.message)
            SetAction(ActionState.Disabled)
            _setLocked(false)
        }
    }

    /** Stops the active ECG monitoring sensor through the SDK. */
    private fun StopSensor() {
        try {
            SetAction(ActionState.Disabled)
            // Stops the SDK's active sensor without requesting timeout handling.
            _medWandController.StopSensor()
            SetAction(ActionState.Idle)
        } catch (outerEx: Exception) {
            println(outerEx.message)
            SetAction(ActionState.Idle)
        } finally {
            _setLocked(false)
        }
    }

    /** Formats the ECG status with the current captured-strip count. */
    private fun SetStatus(value: String) {
        StatusMessage = "$value  [$_captured Captured]"
    }

    /** Applies recording action state and refreshes the status from SDK reading state. */
    private fun SetAction(actionState: ActionState) {
        when (actionState) {
            ActionState.Idle -> {
                SetActionButtonIdle()
                _setLocked(false)
            }

            ActionState.Busy -> {
                _setLocked(true)
                SetActionButtonBusy()
            }

            ActionState.Disabled -> {
                SetActionButtonDisabled()
                _setLocked(false)
            }
        }

        SetStatus(_medWandController.ReadingState.toString())
    }

    /** Sets the action button to the active-recording Stop Recording state. */
    private fun SetActionButtonBusy() {
        ButtonActionState = ActionState.Busy
        ButtonActionText = "Stop Recording"
        ButtonActionTag = ActionState.Busy.name
    }

    /** Sets the action button to the idle Start Recording state. */
    private fun SetActionButtonIdle() {
        ButtonActionState = ActionState.Idle
        ButtonActionText = "Start Recording"
        ButtonActionTag = ActionState.Idle.name
    }

    /** Clears the recording action text while the button is disabled. */
    private fun SetActionButtonDisabled() {
        ButtonActionState = ActionState.Disabled
        ButtonActionText = ""
        ButtonActionTag = ActionState.Disabled.name
    }

    /** Starts a real ECG recording through the SDK without restarting monitoring. */
    private fun StartCapture() {
        SetAction(ActionState.Disabled)
        _medWandController.StartRecording()
        SetAction(ActionState.Busy)
    }

    /** Stops the active ECG recording through the SDK without stopping monitoring. */
    private fun StopCapture() {
        SetAction(ActionState.Disabled)
        _medWandController.StopRecording()
        SetAction(ActionState.Idle)
    }

    /**
     * Handles real SDK recorded-strip bytes, requests the SDK image output, and
     * appends the result to the app-private captures file.
     */
    private fun Ecg_RecordedStripReady(bytes: ByteArray) {
        _capturesFile.appendText("[${Instant.now()}] -> ${_medWandController.EcgBmpFromCapture(bytes)}\n")
        _captured++
    }

    /** No additional ECG model resources are owned outside activation state. */
    override fun close() {
    }
}

/**
 * Root Compose surface for the shell, initialization side effect, dialog host,
 * content frame, toolbar, and status bar.
 */
@Composable
private fun MedWandSdkSampleApplication(MainWindow: MainWindow) {
    val activity = LocalContext.current as Activity

    if (MainWindow.StartupNoticeAccepted) {
        // Startup runs once after the beta notice is acknowledged so the shell
        // can publish status and dialog state into Compose.
        LaunchedEffect(Unit) {
            MainWindow.InitializeSafe()
        }
    }

    // The app keeps the Android Back button inert; Exit is handled by the
    // toolbar confirmation path.
    BackHandler {
    }

    if (MainWindow.StartupNoticeAccepted) {
        if (MainWindow.FirmwareUpdateVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .background(ActionButtonDisabled)
            ) {
                MainWindow.MainFrame()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .background(ControlDarkBrush)
            ) {
                ToolBar(MainWindow)

                Box(modifier = Modifier.weight(1f)) {
                    MainWindow.MainFrame()
                }

                StatusBar(MainWindow.StatusMessage)
            }
        }
    } else {
        StartupNotice(MainWindow::ContinueStartupNotice)
    }

    // Compose renders the current message-box request while the caller awaits
    // the selected result.
    MainWindow.MessageBox?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(request.title) },
            text = { Text(request.message) },
            confirmButton = {
                val primary = request.buttons.first()
                TextButton(onClick = { request.result.complete(primary) }) {
                    Text(primary.buttonText())
                }
            },
            dismissButton = request.buttons.getOrNull(1)?.let { secondary ->
                {
                    TextButton(onClick = { request.result.complete(secondary) }) {
                        Text(secondary.buttonText())
                    }
                }
            }
        )
    }
}

/**
 * Startup beta notice shown before any SDK initialization begins.
 */
@Composable
private fun StartupNotice(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(ControlDarkBrush)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = StartupNoticeMessage,
            color = ControlTextBrush,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(
                containerColor = ActionButtonIdle,
                contentColor = ButtonHighlightBrush
            ),
            modifier = Modifier
                .width(220.dp)
                .height(56.dp)
        ) {
            Text(
                text = "Continue",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Top toolbar with logo, workflow buttons, hidden Summary slot, and Exit.
 */
@Composable
private fun ToolBar(MainWindow: MainWindow) {
    val coroutineScope = rememberCoroutineScope()
    val toolbarFullWidth = 1335.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(ControlBrush)
    ) {
        // Scales the fixed toolbar strip so the Exit button remains visible on
        // narrower Android viewports.
        val toolbarScale = minOf(1f, maxWidth.value / toolbarFullWidth.value)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(122.dp * toolbarScale),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(311.dp * toolbarScale)
                    .height(119.dp * toolbarScale)
                    .padding(top = 1.dp * toolbarScale, bottom = 2.dp * toolbarScale)
            )
            ImageOnlyButton(
                resourceId = R.drawable.temperature_hover,
                enabled = MainWindow.ToolButtonThermometerEnabled,
                scale = toolbarScale,
                onClick = MainWindow::Thermometer_Click
            )
            ImageOnlyButton(
                resourceId = R.drawable.spo2_hover,
                enabled = MainWindow.ToolButtonPulseOximeterEnabled,
                scale = toolbarScale,
                onClick = MainWindow::PulseOximeter_Click
            )
            ImageOnlyButton(
                resourceId = R.drawable.stethoscope_hover,
                enabled = MainWindow.ToolButtonStethoscopeEnabled,
                scale = toolbarScale,
                onClick = { coroutineScope.launch { MainWindow.Stethoscope_Click() } }
            )
            ImageOnlyButton(
                resourceId = R.drawable.camera_hover,
                enabled = MainWindow.ToolButtonCameraEnabled,
                scale = toolbarScale,
                onClick = { coroutineScope.launch { MainWindow.Camera_Click() } }
            )
            ImageOnlyButton(
                resourceId = R.drawable.ecg_hover,
                enabled = MainWindow.ToolButtonEcgEnabled,
                scale = toolbarScale,
                onClick = MainWindow::Ecg_Click
            )
            Spacer(
                modifier = Modifier
                    .padding(start = 15.dp * toolbarScale)
                    .width(1.dp * toolbarScale)
                    .fillMaxHeight()
                    .background(Color.Gray)
            )
            // Summary remains in the toolbar layout but is fully transparent and
            // non-interactive in the current application.
            Box(
                modifier = Modifier
                    .size(width = 144.dp * toolbarScale, height = 119.dp * toolbarScale)
                    .graphicsLayer(alpha = 0f)
            ) {
                Image(
                    painter = painterResource(R.drawable.summary_on),
                    contentDescription = null
                )
            }
            ImageOnlyButton(
                resourceId = R.drawable.exit_off,
                enabled = MainWindow.ToolButtonExitEnabled,
                scale = toolbarScale,
                onClick = {
                    coroutineScope.launch {
                        MainWindow.Exit_Click()
                    }
                }
            )
        }
    }
}

/**
 * Image-backed toolbar button that preserves fixed toolbar sizing and shows
 * disabled actions with reduced alpha.
 */
@Composable
private fun ImageOnlyButton(resourceId: Int, enabled: Boolean, scale: Float = 1f, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        modifier = Modifier
            .width(144.dp * scale)
            .height(119.dp * scale)
            .padding(top = 1.dp * scale, bottom = 2.dp * scale)
    ) {
        Image(
            painter = painterResource(resourceId),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = if (enabled) 1f else 0.35f)
        )
    }
}

/**
 * Bottom shell status region bound to MainWindow.StatusMessage.
 */
@Composable
private fun StatusBar(StatusMessage: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(ControlDarkDarkBrush),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = StatusMessage,
            color = ControlBrush,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

/**
 * Thermometer content region with title, single object-temperature display,
 * workflow status, and Start/Stop action button.
 */
@Composable
private fun ThermometerView(_viewModel: ThermometerViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Workflow title bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(HighlightBrush),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "Thermometer",
                color = ButtonHighlightBrush,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        // Main reading region for object temperature.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ControlDarkDarkBrush),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = _viewModel.TempObject,
                color = ControlTextHighlightBrush,
                fontSize = 64.sp,
                textAlign = TextAlign.Center
            )
        }
        // Workflow status strip above the action button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(ButtonFaceBrush),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = _viewModel.StatusMessage,
                color = ControlTextBrush,
                fontSize = 16.sp
            )
        }
        // Start/Stop action button whose color follows ButtonActionState.
        Button(
            onClick = _viewModel::OnActionButtonClick,
            enabled = _viewModel.ButtonActionState != ActionState.Disabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = when (_viewModel.ButtonActionState) {
                    ActionState.Idle -> ActionButtonIdle
                    ActionState.Busy -> ActionButtonBusy
                    ActionState.Disabled -> ActionButtonDisabled
                },
                disabledContainerColor = ActionButtonDisabled
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = _viewModel.ButtonActionText,
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Pulse Oximeter content region with title, SpO2 and pulse-rate readings,
 * workflow status, and Start/Stop action button.
 */
@Composable
private fun PulseOximeterView(_viewModel: PulseOximeterViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Workflow title bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(HighlightBrush),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "Pulse Oximeter",
                color = ButtonHighlightBrush,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        // Two stacked reading regions separated by the existing dark divider.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ControlDarkDarkBrush)
        ) {
            ReadingBorder(
                text = _viewModel.SpO2,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(ControlDarkDarkBrush)
            )
            ReadingBorder(
                text = _viewModel.PulseRate,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
        // Workflow status strip above the action button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(ButtonFaceBrush),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = _viewModel.StatusMessage,
                color = ControlTextBrush,
                fontSize = 16.sp
            )
        }
        // Start/Stop action button whose color follows ButtonActionState.
        Button(
            onClick = _viewModel::OnActionButtonClick,
            enabled = _viewModel.ButtonActionState != ActionState.Disabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = when (_viewModel.ButtonActionState) {
                    ActionState.Idle -> ActionButtonIdle
                    ActionState.Busy -> ActionButtonBusy
                    ActionState.Disabled -> ActionButtonDisabled
                },
                disabledContainerColor = ActionButtonDisabled
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = _viewModel.ButtonActionText,
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}


/** Stethoscope content region with mode buttons, status, and recording action. */
@Composable
private fun StethoscopeView(view: StethoscopeView) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(HighlightBrush),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "Stethoscope",
                color = ButtonHighlightBrush,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ControlDarkDarkBrush)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Mode",
                color = ControlTextHighlightBrush,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StethoscopeModeButton("Off", StethoscopeHelpers.MicrophoneModes.Off, view)
                StethoscopeModeButton("Heart", StethoscopeHelpers.MicrophoneModes.Heart, view)
                StethoscopeModeButton("Lungs", StethoscopeHelpers.MicrophoneModes.Lungs, view)
                StethoscopeModeButton("Bowel", StethoscopeHelpers.MicrophoneModes.Bowel, view)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(ButtonFaceBrush),
            contentAlignment = Alignment.Center
        ) {
            Text(text = view.StatusMessage, color = ControlTextBrush, fontSize = 16.sp)
        }

        Button(
            onClick = view::OnActionButtonClick,
            enabled = view.ButtonActionState != ActionState.Disabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = when (view.ButtonActionState) {
                    ActionState.Idle -> ActionButtonIdle
                    ActionState.Busy -> ActionButtonBusy
                    ActionState.Disabled -> ActionButtonDisabled
                },
                disabledContainerColor = ActionButtonDisabled
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(text = view.ButtonActionText, color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun StethoscopeModeButton(
    label: String,
    mode: StethoscopeHelpers.MicrophoneModes,
    view: StethoscopeView
) {
    Button(
        onClick = { view.SetStethoscopeMode(mode) },
        enabled = view.ButtonActionState != ActionState.Disabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (view.StethoscopeMode == mode) ButtonFaceBrush else ControlBrush,
            contentColor = ControlTextBrush,
            disabledContainerColor = ActionButtonDisabled
        )
    ) {
        Text(text = label, fontSize = 14.sp)
    }
}


/** Camera content region closely matching the desktop Otoscope/Dermatoscope view. */
@Composable
private fun CameraView(view: CameraView) {
    val context = LocalContext.current
    val previewView = remember(context) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }

    DisposableEffect(view, previewView) {
        view.AttachPreviewView(previewView)
        onDispose { view.DetachPreviewView(previewView) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(HighlightBrush),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "Camera",
                color = ButtonHighlightBrush,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            CameraControls(view)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                if (!view.HasFrame) {
                    Text(
                        text = if (view.IsStarting) "Starting camera preview..." else "Select Dermatoscope or Otoscope",
                        color = ControlDarkBrush,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .width(150.dp)
                    .fillMaxHeight()
                    .background(ControlDarkBrush)
                    .padding(horizontal = 7.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(HighlightBrush),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Modes",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(7.dp))
                CameraModeButton(CameraHelper.CameraModes.Off, view)
                Spacer(modifier = Modifier.height(7.dp))
                CameraModeButton(CameraHelper.CameraModes.Dermatoscope, view)
                Spacer(modifier = Modifier.height(7.dp))
                CameraModeButton(CameraHelper.CameraModes.Otoscope, view)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(ButtonFaceBrush),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = view.StatusMessage,
                color = ControlTextBrush,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }

        Button(
            onClick = view::Capture,
            enabled = !view.IsStarting && view.CameraMode != CameraHelper.CameraModes.Off && view.HasFrame,
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = ActionButtonIdle,
                disabledContainerColor = ActionButtonDisabled
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(text = "Capture", color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
private fun CameraControls(view: CameraView) {
    val enabled = !view.IsStarting && view.CameraMode != CameraHelper.CameraModes.Off

    Column(
        modifier = Modifier
            .width(150.dp)
            .fillMaxHeight()
            .background(ButtonFaceBrush)
            .verticalScroll(rememberScrollState())
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Controls", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))

        if (view.LedControlAvailable) {
            Text("White LED: ${view.LedIntensity}", fontSize = 12.sp)
            if (view.LedIntensityAdjustable) {
                var ledSliderValue by remember(view.LedIntensity, view.LedIntensityMax) {
                    mutableStateOf(view.LedIntensity.toFloat())
                }
                Slider(
                    value = ledSliderValue,
                    onValueChange = { ledSliderValue = it },
                    onValueChangeFinished = {
                        view.SetLedIntensity(ledSliderValue.roundToInt())
                    },
                    valueRange = 0f..view.LedIntensityMax.coerceAtLeast(1).toFloat(),
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                CameraControlButton(if (view.LedIntensity > 0) "Turn off" else "Turn on", enabled) {
                    view.ToggleLed()
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (view.FocusControlAvailable) {
            Text("Focus: ${if (view.ManualFocusEnabled) "Manual" else "Auto"}", fontSize = 12.sp)

            if (view.AutoFocusAvailable && view.ManualFocusAvailable) {
                CameraControlButton(
                    if (view.ManualFocusEnabled) "Use Auto" else "Use Manual",
                    enabled
                ) {
                    view.SetManualFocus(!view.ManualFocusEnabled)
                }
            } else if (view.AutoFocusAvailable) {
                Text("Auto focus", fontSize = 11.sp)
            }

            if (view.ManualFocusAvailable) {
                var focusSliderValue by remember(view.FocusValue, view.FocusValueMin, view.FocusValueMax) {
                    mutableStateOf(view.FocusValue.toFloat())
                }
                Text("Manual value: ${focusSliderValue.roundToInt()}", fontSize = 11.sp)
                Slider(
                    value = focusSliderValue,
                    onValueChange = { focusSliderValue = it },
                    onValueChangeFinished = {
                        view.SetFocusValue(focusSliderValue.roundToInt())
                    },
                    valueRange = view.FocusValueMin.toFloat()..view.FocusValueMax.coerceAtLeast(view.FocusValueMin + 1).toFloat(),
                    enabled = enabled && view.ManualFocusEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (view.CameraMode == CameraHelper.CameraModes.Otoscope) {
            Text("Move", fontSize = 12.sp)
            CameraControlButton("↑", enabled) { view.MoveOtoscope(vertical = -1) }
            Row {
                CameraControlButton("←", enabled) { view.MoveOtoscope(horizontal = -1) }
                Spacer(Modifier.width(4.dp))
                CameraControlButton("→", enabled) { view.MoveOtoscope(horizontal = 1) }
            }
            CameraControlButton("↓", enabled) { view.MoveOtoscope(vertical = 1) }
            Spacer(Modifier.height(6.dp))
            Text("Zoom", fontSize = 12.sp)
            Row {
                CameraControlButton("−", enabled) { view.ZoomOtoscope(-1) }
                Spacer(Modifier.width(4.dp))
                CameraControlButton("+", enabled) { view.ZoomOtoscope(1) }
            }
            Spacer(Modifier.height(6.dp))
            Text("Radius", fontSize = 12.sp)
            Row {
                CameraControlButton("−", enabled) { view.RadiusOtoscope(-1) }
                Spacer(Modifier.width(4.dp))
                CameraControlButton("+", enabled) { view.RadiusOtoscope(1) }
            }
            Spacer(Modifier.height(6.dp))
            CameraControlButton("Reset", enabled) { view.ResetOtoscope() }
        }
    }
}

@Composable
private fun CameraControlButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RectangleShape,
        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp)
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun CameraModeButton(mode: CameraHelper.CameraModes, view: CameraView) {
    val selected = view.CameraMode == mode
    Button(
        onClick = { view.SelectMode(mode) },
        enabled = !view.IsStarting,
        shape = RectangleShape,
        contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = HighlightBrush,
            disabledContainerColor = Color(0xFFE5E5E5),
            disabledContentColor = ControlDarkDarkBrush
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(91.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.Red else Color.White
            )
    ) {
        val iconResource = when (mode) {
            CameraHelper.CameraModes.Off -> R.drawable.none_hover
            CameraHelper.CameraModes.Dermatoscope -> R.drawable.dermatoscope_hover
            CameraHelper.CameraModes.Otoscope -> R.drawable.otoscope_hover
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Image(
                painter = painterResource(iconResource),
                contentDescription = "${mode.displayName()} camera mode",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(56.dp)
                    .height(46.dp)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = mode.displayName(),
                color = ControlTextBrush,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * ECG content region with title, SDK-controlled ImageView, captured-count
 * status, and recording action button.
 */
@Composable
private fun EcgView(_viewModel: EcgViewModel, view: EcgView) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Workflow title bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(HighlightBrush),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "ECG",
                color = ButtonHighlightBrush,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        val context = LocalContext.current
        val ecgImageView = remember(context) {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        }
        DisposableEffect(view, ecgImageView) {
            view.AttachRenderView(ecgImageView)
            onDispose { view.DetachRenderView(ecgImageView) }
        }
        AndroidView(
            factory = { ecgImageView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ControlDarkDarkBrush)
        )
        // Workflow status strip includes current SDK state and capture count.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(ButtonFaceBrush),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = _viewModel.StatusMessage,
                color = ControlTextBrush,
                fontSize = 16.sp
            )
        }
        // Recording action button. Start/Stop Recording does not start or stop
        // ECG monitoring.
        Button(
            onClick = _viewModel::OnActionButtonClick,
            enabled = _viewModel.ButtonActionState != ActionState.Disabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = when (_viewModel.ButtonActionState) {
                    ActionState.Idle -> ActionButtonIdle
                    ActionState.Busy -> ActionButtonBusy
                    ActionState.Disabled -> ActionButtonDisabled
                },
                disabledContainerColor = ActionButtonDisabled
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text(
                text = _viewModel.ButtonActionText,
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Shared large-reading display block used by workflows with centered text.
 */
@Composable
private fun ReadingBorder(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(ControlDarkDarkBrush),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = ControlTextHighlightBrush,
            fontSize = 64.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Maps dialog result values to the button labels rendered in AlertDialog.
 */
private fun MessageBoxResult.buttonText(): String =
    when (this) {
        MessageBoxResult.OK -> "OK"
        MessageBoxResult.Cancel -> "Cancel"
        MessageBoxResult.Yes -> "Yes"
        MessageBoxResult.No -> "No"
        MessageBoxResult.StartFirmwareUpdate -> "Start firmware update"
        MessageBoxResult.Exit -> "Exit"
    }

/**
 * Reads the USB device extra using the type-safe Android 13+ API when present.
 */
@Suppress("DEPRECATION")
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

private val ControlTextBrush = Color.Black
private val ControlTextHighlightBrush = Color.White
private val ControlBrush = Color(0xFFF0F0F0)
private val ControlDarkBrush = Color(0xFFA0A0A0)
private val ControlDarkDarkBrush = Color(0xFF404040)
private val HighlightBrush = Color(0xFF0078D7)
private val ButtonHighlightBrush = Color.White
private val ButtonFaceBrush = Color.White
private val ActionButtonIdle = Color(0xFF00C000)
private val ActionButtonBusy = Color(0xFFC00000)
private val ActionButtonDisabled = Color(0xFF808080)
