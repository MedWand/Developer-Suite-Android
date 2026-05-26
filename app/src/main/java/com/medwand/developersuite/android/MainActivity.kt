package com.medwand.developersuite.android

import android.app.Activity
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medwand.sdk_core.Core.EnumReadingState
import com.medwand.sdk_core.Core.EnumSensor
import com.medwand.sdk_core.Core.MedWandDeviceError
import com.medwand.sdk_core.Core.MedWandReading
import com.medwand.sdk_core.MedWandController
import com.medwand.sdk_core.Modules.EcgRenderTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
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
    const val MwSdkLicense = """"""
    const val MwSdkPublicKey = """"""
}

/** Private broadcast action used to receive the Android USB permission result. */
private const val ACTION_USB_PERMISSION = "com.medwand.developersuite.android.USB_PERMISSION"

private const val StartupNoticeMessage =
    "This is a BETA only sample application and SDK. This is not intended for use in production and should only be used for initial development work. The Stethoscope and Camera are not functional in this version. You will still need to request a license through your sales representative."

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
    val MedWandSensor: EnumSensor

    /** Callback used by workflow state changes to lock or unlock navigation. */
    var ViewLockStateChanged: ((Boolean) -> Unit)?

    /** Prepares the workflow when it becomes the active content view. */
    fun Activate()

    /** Stops or releases workflow runtime state before another view is shown. */
    fun Deactivate()

    /** Receives the SDK reading-state changes routed by the shell. */
    fun OnReadingStateChanged(readingState: EnumReadingState)

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

/**
 * Owns the visible shell state, direct MedWand SDK controller lifecycle, toolbar
 * navigation, status text, and active sensor workflow.
 */
private class MainWindow(private val activity: Activity) {
    private var _medWandController: MedWandController? = null
    private var _thermometerView: ThermometerView? = null
    private var _pulseOximeterView: PulseOximeterView? = null
    private var _ecgView: EcgView? = null
    private var _currentSensorView: ISensorView? by mutableStateOf(null)

    private val GeneralStatus: String
        get() = "Device: ${_medWandController?.comPort}/${_medWandController?.vendorId}/${_medWandController?.productId} | ${_medWandController?.udi} | ${_medWandController?.generation} v${_medWandController?.firmwareVersion}"

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
        ConnectMedWand()
        InitializeMedWand()
        InitializeUserInterface()
    }

    /**
     * Constructs the SDK controller, validates license inputs, requests Android
     * USB access when needed, and connects to the physical MedWand device.
     */
    private suspend fun ConnectMedWand() {
        if (Settings.MwSdkLicense.isEmpty() || Settings.MwSdkPublicKey.isEmpty()) {
            throw Exception("No valid license information")
        }

        val usbManager = activity.getSystemService(Activity.USB_SERVICE) as UsbManager
        _medWandController = _medWandController ?: MedWandController(usbManager)
        _medWandController?.onLicenseError = { state ->
            println("LicenseError: $state")
        }
        // Builds the SDK controller license state before any connection attempt.
        _medWandController?.construct(Settings.MwSdkLicense, Settings.MwSdkPublicKey)
        if (_medWandController?.isLicenseValid != true) {
            throw Exception("No valid license")
        }

        UpdateStatus("Connecting to MedWand.")
        // Android requires explicit USB permission before the SDK can open the
        // attached USB device during connect().
        if (!RequestMedWandUsbPermission(usbManager)) {
            throw Exception("MedWand USB permission denied.")
        }

        var done = false
        do {
            try {
                // Direct SDK connection attempt; retry/cancel is driven by the
                // visible message-box result when the controller is not connected.
                _medWandController?.connect()
                if (_medWandController?.isConnected != true) {
                    val resultDialog = MessageBox(
                        "MedWand not found. PLease connect your MedWand and try again.",
                        "MedWand Not Found",
                        listOf(MessageBoxResult.OK, MessageBoxResult.Cancel)
                    )
                    if (resultDialog == MessageBoxResult.Cancel) {
                        break
                    }
                } else {
                    done = true
                }
            } catch (outerEx: Exception) {
                println(outerEx)
            }
        } while (!done)

        if (done) {
            // Device and state callbacks are only attached after a successful
            // connection so they route real SDK events into the active workflow.
            _medWandController?.onDeviceError = { MedWandController_MedWandDeviceError(it) }
            _medWandController?.onDeviceStateChanged = { MedWandController_DeviceStateChanged() }
        } else {
            _medWandController?.onLicenseError = {}
            _medWandController = null
            throw Exception("No MedWand Connected!")
        }
    }

    /**
     * Requests runtime permission for the discovered MedWand USB device and
     * suspends until Android broadcasts the grant or denial.
     */
    private suspend fun RequestMedWandUsbPermission(usbManager: UsbManager): Boolean {
        val medWandDevice = FindMedWandUsbDevice(usbManager) ?: return true
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
            // Android 13+ requires an explicit receiver export flag for dynamic
            // registration of this app-private permission result receiver.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                activity.registerReceiver(receiver, filter)
            }
            registered = true
            // Opens the platform permission dialog for the USB device selected
            // by FindMedWandUsbDevice.
            usbManager.requestPermission(medWandDevice, permissionIntent)
            return result.await()
        } finally {
            if (registered) {
                runCatching { activity.unregisterReceiver(receiver) }
            }
        }
    }

    /**
     * Locates the attached MedWand USB device from Android's current USB device
     * list using the known product id first, then visible device metadata.
     */
    private fun FindMedWandUsbDevice(usbManager: UsbManager): UsbDevice? {
        val devices = usbManager.deviceList.values
        return devices.firstOrNull { it.productId == 60 }
            ?: devices.firstOrNull { device ->
                listOfNotNull(device.manufacturerName, device.productName, device.deviceName)
                    .any { it.contains("medwand", ignoreCase = true) || it.contains("med wand", ignoreCase = true) }
            }
    }

    /**
     * Initializes the connected controller and attaches SDK reading callbacks
     * used by the active sensor view.
     */
    private fun InitializeMedWand() {
        UpdateStatus("Initializing MedWand")

        if (_medWandController?.isConnected != true) {
            throw Exception("MedWand not connected.")
        }

        // Initializes the physical MedWand through the SDK before any workflow
        // view can be enabled.
        _medWandController?.initialize()

        if (_medWandController?.isInitialized != true) {
            throw Exception("MedWand not initialized.")
        }

        // Reading callbacks are routed by sensor type to the current workflow.
        _medWandController?.onReadingStateChanged = { MedWandController_ReadingStateChanged(it) }
        _medWandController?.onReadingReceived = { MedWandController_ReadingReceived(it) }
    }

    /**
     * Creates workflow views after SDK initialization and enables only the
     * workflows currently available in this application.
     */
    private fun InitializeUserInterface() {
        if (_medWandController?.isInitialized != true) {
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
        _ecgView = EcgView(
            requireNotNull(_medWandController),
            capturesFile = File(activity.filesDir, "captures.txt"),
            locked = { CurrentSensorView_ViewLockStateChanged(locked = it) }
        )
        // Connects the ECG SDK render target to the controller so ECG frames
        // are delivered through EcgGridContainer.render(imageBytes).
        _medWandController?.configure(_ecgView?.GridContainer)

        DeviceInformation()

        ToolButtonThermometerEnabled = true
        ToolButtonPulseOximeterEnabled = true
        // Camera and Stethoscope are shown in the toolbar but are disabled in
        // this application, so their click handlers do not open workflows.
        ToolButtonStethoscopeEnabled = false
        ToolButtonCameraEnabled = false
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
        if (_medWandController?.isConnected != true) {
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
            ComPort: ${_medWandController?.comPort}
            VendorId: ${_medWandController?.vendorId}
            ProductId: ${_medWandController?.productId}
            DeviceId: ${_medWandController?.deviceId}
            UDI: ${_medWandController?.udi}
            DeviceState: ${_medWandController?.deviceState}
            IsConnected: ${_medWandController?.isConnected}
            IsInitialized: ${_medWandController?.isInitialized}
            IsBootloaderMode: ${_medWandController?.isBootloaderMode(false)}
            Firmware: ${_medWandController?.firmwareVersion}
            Generation: ${_medWandController?.generation}
            Camera: ${_medWandController?.cameraModel}
        """.trimIndent()
    }

    /**
     * Updates toolbar enabled state while preserving disabled Camera and
     * Stethoscope affordances.
     */
    private fun SetNavigation(enabled: Boolean, exitEnabled: Boolean) {
        ToolButtonThermometerEnabled = enabled
        ToolButtonPulseOximeterEnabled = enabled
        ToolButtonStethoscopeEnabled = false
        ToolButtonCameraEnabled = false
        ToolButtonEcgEnabled = enabled
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
        _ecgView?.close()
        _thermometerView = null
        _pulseOximeterView = null
        _ecgView = null

        // StopSensor is called during cleanup so any currently active sensor is
        // stopped before callbacks and the controller are released.
        _medWandController?.stopSensor(false)
        _medWandController?.onLicenseError = {}
        _medWandController?.onDeviceError = {}
        _medWandController?.onDeviceStateChanged = {}
        _medWandController?.onReadingStateChanged = {}
        _medWandController?.onReadingReceived = {}
        _medWandController?.close()
        _medWandController = null
    }

    /** Shows the Thermometer workflow when the toolbar action is enabled. */
    fun Thermometer_Click() {
        _thermometerView?.let { ShowView(it) }
    }

    /** Shows the Pulse Oximeter workflow when the toolbar action is enabled. */
    fun PulseOximeter_Click() {
        _pulseOximeterView?.let { ShowView(it) }
    }

    /** Stethoscope is visible in the toolbar but disabled in this application. */
    fun Stethoscope_Click() {
    }

    /** Camera is visible in the toolbar but disabled in this application. */
    fun Camera_Click() {
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
     * Camera, Stethoscope, and Summary remain non-interactive.
     */
    private fun CurrentSensorView_ViewLockStateChanged(locked: Boolean) {
        ToolButtonThermometerEnabled = true
        ToolButtonPulseOximeterEnabled = true
        ToolButtonStethoscopeEnabled = false
        ToolButtonCameraEnabled = false
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
    private fun MedWandController_ReadingStateChanged(readingState: EnumReadingState) {
        _currentSensorView?.OnReadingStateChanged(readingState)
    }

    /**
     * Routes real SDK readings for supported sensors to the currently active
     * workflow after normalizing the SDK sensor-type string.
     */
    private fun MedWandController_ReadingReceived(reading: MedWandReading) {
        val sensorType = MedWandSensorFromReading(reading) ?: return

        when (sensorType) {
            EnumSensor.Thermometer -> _currentSensorView?.OnReadingReceived(reading)
            EnumSensor.PulseOximeter -> _currentSensorView?.OnReadingReceived(reading)
            EnumSensor.Ecg -> _currentSensorView?.OnReadingReceived(reading)
            else -> Unit
        }
    }

    /**
     * Converts the SDK reading sensor type into the enum used by application
     * workflow routing.
     */
    private fun MedWandSensorFromReading(reading: MedWandReading): EnumSensor? {
        val sensorType = reading.sensorType.orEmpty().trim()
        return EnumSensor.values().firstOrNull { it.name.equals(sensorType, ignoreCase = true) }
            ?: when (sensorType.lowercase()) {
                // The Android SDK emits "spo2" for Pulse Oximeter readings.
                "spo2" -> EnumSensor.PulseOximeter
                // The Android SDK emits "ecg" for ECG readings.
                "ecg" -> EnumSensor.Ecg
                else -> null
            }
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
            if (current == null) {
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

    override val MedWandSensor: EnumSensor = EnumSensor.Thermometer
    override var ViewLockStateChanged: ((Boolean) -> Unit)? = null

    /** Initializes Thermometer display state when the view becomes active. */
    override fun Activate() {
        _viewModel.Initialize()
    }

    /** Thermometer deactivation has no view-local state to stop here. */
    override fun Deactivate() {
    }

    /** Forwards SDK reading-state changes into Thermometer status text. */
    override fun OnReadingStateChanged(readingState: EnumReadingState) =
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
    fun OnReadingStateChanged(state: EnumReadingState) {
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
            // through MedWandController.onReadingReceived.
            if (_controller.startThermometer()) {
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
                _controller.stopSensor(false)
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

        TempObject = FormatTemp(reading.tempObject.orEmpty())
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

        SetStatus(_controller.readingState.toString())
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

    override val MedWandSensor: EnumSensor = EnumSensor.PulseOximeter
    override var ViewLockStateChanged: ((Boolean) -> Unit)? = null

    /** Initializes Pulse Oximeter display state when the view becomes active. */
    override fun Activate() {
        _viewModel.Initialize()
    }

    /** Pulse Oximeter deactivation has no view-local state to stop here. */
    override fun Deactivate() {
    }

    /** Forwards SDK reading-state changes into Pulse Oximeter status text. */
    override fun OnReadingStateChanged(readingState: EnumReadingState) =
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
            timeStamp = Instant.now()
            status = ""
            index = 1
            count = 0
            sensorType = EnumSensor.PulseOximeter.name
            tempAmbient = ""
            tempObject = ""
            pulseRate = null
            spo2 = null
            ecgData = null
        }

        UpdateReadingText()
        SetAction(ActionState.Idle)
    }

    /** Updates the visible workflow status from the SDK reading state. */
    fun OnReadingStateChanged(state: EnumReadingState) {
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
            // through MedWandController.onReadingReceived.
            if (_controller.startPulseOximeter()) {
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
                _controller.stopSensor(false)
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
        SpO2 = "SpO2 : ${reading.spo2.orEmpty()}"
        PulseRate = "PulseRate : ${reading.pulseRate.orEmpty()}"
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

        SetStatus(_controller.readingState.toString())
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

/**
 * SDK render target used as the ECG content container. The SDK supplies image
 * bytes through render(), and Compose displays the latest decoded frame.
 */
private class EcgGridContainer : EcgRenderTarget {
    private val _mainHandler = Handler(Looper.getMainLooper())

    var Frame by mutableStateOf<Bitmap?>(null)
    private var _width by mutableStateOf(1200)
    private var _height by mutableStateOf(481)

    override val width: Int
        get() = _width.coerceAtLeast(1)

    override val height: Int
        get() = _height.coerceAtLeast(120)

    /**
     * Receives SDK-rendered ECG image bytes and publishes the decoded bitmap on
     * the main thread for Compose rendering.
     */
    override fun render(imageBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return
        _mainHandler.post {
            Frame = bitmap
        }
    }

    /** Stores the current Compose container size for SDK frame rendering. */
    fun Resized(width: Int, height: Int) {
        _width = width.coerceAtLeast(1)
        _height = height.coerceAtLeast(120)
    }
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

    val GridContainer = EcgGridContainer()

    override val MedWandSensor: EnumSensor = EnumSensor.Ecg
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
    override fun OnReadingStateChanged(readingState: EnumReadingState) =
        _viewModel.OnReadingStateChanged(readingState)

    /** Stores ECG SDK readings without drawing ECG frames in application code. */
    override fun OnReadingReceived(reading: MedWandReading) =
        _viewModel.OnReadingReceived(reading)

    /** Forwards device error state so the recording action can be enabled or disabled. */
    override fun OnDeviceError(error: MedWandDeviceError?) =
        _viewModel.OnDeviceError(error)

    /** No extra ECG view resources are owned beyond the view model and render target. */
    override fun close() {
        _viewModel.close()
    }

    /** Renders ECG status, SDK-controlled grid output, and recording action. */
    @Composable
    override fun Render() {
        EcgView(_viewModel, GridContainer)
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
            val ecg = _medWandController.ecg
            if (ecg != null) {
                // The SDK invokes this callback only when a real recorded strip
                // is ready; the handler records that SDK output.
                ecg.onRecordedStripReady = { bytes -> Ecg_RecordedStripReady(bytes) }
            }
        }

        _reading = MedWandReading().apply {
            timeStamp = Instant.now()
            status = ""
            index = 1
            count = 0
            sensorType = EnumSensor.Ecg.name
            tempAmbient = ""
            tempObject = ""
            pulseRate = null
            spo2 = null
            ecgData = null
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
        val ecg = _medWandController.ecg
        if (ecg == null) {
            return
        }
        ecg.onRecordedStripReady = null
        SetStatus("Not Monitoring")
    }

    /** Updates the ECG status label with the SDK state and current capture count. */
    fun OnReadingStateChanged(state: EnumReadingState) {
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

            // Starts the SDK ECG monitoring stream for the configured
            // GridContainer render target.
            if (_medWandController.startEcg()) {
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
            _medWandController.stopSensor(false)
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

        SetStatus(_medWandController.readingState.toString())
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
        _medWandController.startRecording()
        SetAction(ActionState.Busy)
    }

    /** Stops the active ECG recording through the SDK without stopping monitoring. */
    private fun StopCapture() {
        SetAction(ActionState.Disabled)
        _medWandController.stopRecording()
        SetAction(ActionState.Idle)
    }

    /**
     * Handles real SDK recorded-strip bytes, requests the SDK image output, and
     * appends the result to the app-private captures file.
     */
    private fun Ecg_RecordedStripReady(bytes: ByteArray) {
        _capturesFile.appendText("[${Instant.now()}] -> ${_medWandController.ecgBmpFromCapture(bytes)}\n")
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
        // Shell chrome: top toolbar, center content frame, and bottom status bar.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .background(ControlDarkBrush)
        ) {
            ToolBar(MainWindow)
            // MainFrame owns the placeholder/workflow content region.
            Box(modifier = Modifier.weight(1f)) {
                MainWindow.MainFrame()
            }
            StatusBar(MainWindow.StatusMessage)
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
                onClick = MainWindow::Stethoscope_Click
            )
            ImageOnlyButton(
                resourceId = R.drawable.camera_hover,
                enabled = MainWindow.ToolButtonCameraEnabled,
                scale = toolbarScale,
                onClick = MainWindow::Camera_Click
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

/**
 * ECG content region with title, SDK-controlled GridContainer, captured-count
 * status, and recording action button.
 */
@Composable
private fun EcgView(_viewModel: EcgViewModel, GridContainer: EcgGridContainer) {
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
        // SDK-controlled ECG display container; the app only displays the
        // latest bitmap frame provided through EcgGridContainer.render().
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ControlDarkDarkBrush)
                .onSizeChanged { size -> GridContainer.Resized(size.width, size.height) },
            contentAlignment = Alignment.Center
        ) {
            GridContainer.Frame?.let { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
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
