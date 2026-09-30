package com.remag.aines

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.abs
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remag.aines.emulator.Controller
import com.remag.aines.emulator.NesMachine
import com.remag.aines.save.SaveState
import com.remag.aines.ui.theme.AINESTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("aines_settings", Context.MODE_PRIVATE)

    companion object {
        // Bindable non-NES actions for the save/load buttons. Negative ids can never collide with
        // a Controller.BUTTON_* bit mask, and unlike the NES buttons they have no default binding.
        const val ACTION_SAVE_STATE = -1001
        const val ACTION_LOAD_STATE = -1002
    }

    var currentRomName by mutableStateOf("Super Mario Bros.nes")
    var nesMachine by mutableStateOf<NesMachine?>(null)
    var isRunning by mutableStateOf(true)

    // Identity of the ROM the running machine was built from; save files are keyed by it, so a
    // save can only ever be restored into the exact game that produced it.
    var currentRomIdentity by mutableStateOf(0L)
        private set

    // One-shot message the UI shows as a toast ("State saved", "No save for this game", ...).
    var statusMessage by mutableStateOf<String?>(null)

    // Serialises save/restore against the emulation loop: the loop holds it while stepping a
    // frame, so snapshots are always taken and applied at a frame boundary, never mid-raster.
    private val machineMutex = Mutex()

    suspend fun <T> withMachineLock(block: suspend () -> T): T = machineMutex.withLock { block() }
    
    var showFps by mutableStateOf(prefs.getBoolean("show_fps", true))
        private set

    fun updateShowFps(value: Boolean) {
        showFps = value
        prefs.edit().putBoolean("show_fps", value).apply()
    }

    var showSettingsDialog by mutableStateOf(false)
    var showRemapDialog by mutableStateOf(false)
    var showCustomRomWarning by mutableStateOf(false)

    // Remap state: NES Button constant -> Physical KeyCode (saved in SharedPreferences)
    val buttonToKeyMap = mutableStateMapOf<Int, Int>().apply {
        val defaultMap = mapOf(
            Controller.BUTTON_A to KeyEvent.KEYCODE_BUTTON_A,
            Controller.BUTTON_B to KeyEvent.KEYCODE_BUTTON_B,
            Controller.BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
            Controller.BUTTON_START to KeyEvent.KEYCODE_BUTTON_START,
            Controller.BUTTON_UP to KeyEvent.KEYCODE_DPAD_UP,
            Controller.BUTTON_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            Controller.BUTTON_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            Controller.BUTTON_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT
        )
        for ((btn, defaultCode) in defaultMap) {
            put(btn, prefs.getInt("key_map_$btn", defaultCode))
        }
    }

    var waitingForNesButton: Int? by mutableStateOf(null)

    fun saveKeyMapping(nesBtn: Int, keyCode: Int) {
        buttonToKeyMap[nesBtn] = keyCode
        prefs.edit().putInt("key_map_$nesBtn", keyCode).apply()
    }

    // Save/load action bindings: NES-button-free ids with NO default key (0 = unbound), persisted
    // in the same SharedPreferences as the controller remap.
    val actionToKeyMap = mutableStateMapOf<Int, Int>().apply {
        put(ACTION_SAVE_STATE, prefs.getInt("key_map_$ACTION_SAVE_STATE", 0))
        put(ACTION_LOAD_STATE, prefs.getInt("key_map_$ACTION_LOAD_STATE", 0))
    }

    fun saveActionKeyMapping(action: Int, keyCode: Int) {
        actionToKeyMap[action] = keyCode
        prefs.edit().putInt("key_map_$action", keyCode).apply()
    }

    fun loadRom(romName: String) {
        val romBytes = try {
            getApplication<Application>().assets.open(romName).readBytes()
        } catch (e: Exception) {
            ByteArray(40960).apply {
                this[0] = 0x4E; this[1] = 0x45; this[2] = 0x53; this[3] = 0x1A
                this[4] = 1
                this[5] = 1
            }
        }
        currentRomIdentity = SaveState.romIdentity(romBytes)
        currentRomName = romName
        nesMachine = NesMachine(romBytes)
    }

    fun loadCustomRom(romName: String, romBytes: ByteArray) {
        currentRomIdentity = SaveState.romIdentity(romBytes)
        currentRomName = romName
        nesMachine = NesMachine(romBytes)
    }

    fun getOrCreateNesMachine(): NesMachine {
        if (nesMachine == null) {
            // ALWAYS start with Super Mario Bros on initial launch
            loadRom("Super Mario Bros.nes")
        }
        return nesMachine!!
    }

    fun toggleRom() {
        val nextRom = if (currentRomName == "Super Mario Bros.nes") {
            "AccuracyCoin.nes"
        } else {
            "Super Mario Bros.nes"
        }
        loadRom(nextRom)
    }

    fun handlePhysicalKey(keyCode: Int, pressed: Boolean): Boolean {
        val waiting = waitingForNesButton
        if (waiting != null && pressed) {
            if (waiting < 0) saveActionKeyMapping(waiting, keyCode) else saveKeyMapping(waiting, keyCode)
            waitingForNesButton = null
            return true
        }

        // Save/load action bindings first; unbound actions leave keyCode 0, which no real key
        // reports, so they are simply never triggered.
        if (keyCode == actionToKeyMap[ACTION_SAVE_STATE]) {
            if (pressed) saveState()
            return true
        }
        if (keyCode == actionToKeyMap[ACTION_LOAD_STATE]) {
            if (pressed) loadState()
            return true
        }

        for ((nesBtn, boundCode) in buttonToKeyMap) {
            if (keyCode == boundCode || isFallbackKeyMatch(keyCode, nesBtn)) {
                nesMachine?.pressButton(nesBtn, pressed)
                return true
            }
        }
        return false
    }

    private fun isFallbackKeyMatch(keyCode: Int, nesBtn: Int): Boolean {
        return when (nesBtn) {
            Controller.BUTTON_A -> keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_SPACE
            Controller.BUTTON_B -> keyCode == KeyEvent.KEYCODE_ESCAPE
            Controller.BUTTON_SELECT -> keyCode == KeyEvent.KEYCODE_TAB
            Controller.BUTTON_START -> keyCode == KeyEvent.KEYCODE_MENU
            Controller.BUTTON_UP -> keyCode == KeyEvent.KEYCODE_W
            Controller.BUTTON_DOWN -> keyCode == KeyEvent.KEYCODE_S
            Controller.BUTTON_LEFT -> keyCode == KeyEvent.KEYCODE_A
            Controller.BUTTON_RIGHT -> keyCode == KeyEvent.KEYCODE_D
            else -> false
        }
    }

    // ------------------------------------------------------------ save states

    fun saveState() {
        val machine = nesMachine ?: return
        viewModelScope.launch {
            // Wait for the emulation loop to finish the frame it is stepping; the snapshot and the
            // disk write then happen with the machine safely at a frame boundary.
            withMachineLock {
                withContext(Dispatchers.IO) {
                    val ok = SaveState.save(
                        getApplication(), machine, currentRomIdentity, currentRomName
                    )
                    statusMessage = if (ok) "Saved ${currentRomName}" else "Save failed"
                }
            }
        }
    }

    fun loadState() {
        val machine = nesMachine ?: return
        viewModelScope.launch {
            withMachineLock {
                val result = withContext(Dispatchers.IO) {
                    // load() returns null unless a save exists AND belongs to this exact ROM, so a
                    // save made in another game can never be restored here.
                    val data = SaveState.load(getApplication(), currentRomIdentity)
                    data to (data != null && SaveState.restore(machine, data))
                }
                statusMessage = when {
                    result.second -> "Loaded ${currentRomName}"
                    result.first == null -> "No save for this game"
                    else -> "Save could not be restored"
                }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val openRomLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    val fileName = uri.lastPathSegment ?: "Custom ROM"
                    viewModel.loadCustomRom(fileName, bytes)
                }
            } catch (e: Exception) {
                // Ignore load errors
            }
        }
    }

    fun launchRomPicker() {
        try {
            openRomLauncher.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            // Fallback
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.getOrCreateNesMachine()

        setContent {
            AINESTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EmulatorScreen(
                        viewModel = viewModel,
                        onLaunchRomPicker = { launchRomPicker() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.isRunning = false
    }

    override fun onResume() {
        super.onResume()
        viewModel.isRunning = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.handlePhysicalKey(keyCode, true)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.handlePhysicalKey(keyCode, false)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && (event.source and InputDevice.SOURCE_JOYSTICK) != 0 &&
            (event.source and InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            
            val xAxis = event.getAxisValue(MotionEvent.AXIS_X)
            val yAxis = event.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            val effectiveX = if (abs(xAxis) > abs(hatX)) xAxis else hatX
            val effectiveY = if (abs(yAxis) > abs(hatY)) yAxis else hatY

            val machine = viewModel.nesMachine
            if (machine != null) {
                machine.pressButton(Controller.BUTTON_LEFT, effectiveX < -0.5f)
                machine.pressButton(Controller.BUTTON_RIGHT, effectiveX > 0.5f)
                machine.pressButton(Controller.BUTTON_UP, effectiveY < -0.5f)
                machine.pressButton(Controller.BUTTON_DOWN, effectiveY > 0.5f)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }
}

@Composable
fun EmulatorScreen(
    viewModel: MainViewModel,
    onLaunchRomPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nesMachine = viewModel.nesMachine ?: viewModel.getOrCreateNesMachine()
    val showFps = viewModel.showFps
    val isRunning = viewModel.isRunning

    var currentBitmap by remember(nesMachine) { mutableStateOf<ImageBitmap?>(null) }
    var fpsDisplay by remember { mutableFloatStateOf(0f) }

    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(viewModel.statusMessage) {
        viewModel.statusMessage?.let {
            Toast.makeText(appContext, it, Toast.LENGTH_SHORT).show()
            viewModel.statusMessage = null
        }
    }

    LaunchedEffect(nesMachine, isRunning) {
        if (!isRunning) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val sampleRate = 44100
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, 735 * 4 * 2)

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.play()

            // A Bitmap handed to Compose must never be written to again: the UI thread can be
            // drawing it at any moment, and overwriting it in place is what shows up as a
            // horizontal tear across the picture. Each frame therefore gets its own Bitmap.
            val audioReadBuffer = ShortArray(2048)

            var frameCount = 0
            var lastFpsTimestamp = System.currentTimeMillis()

            try {
                while (isActive && viewModel.isRunning) {
                    val frameStartTime = System.currentTimeMillis()

                    // The mutex is what keeps save/restore honest: holding it for exactly one
                    // stepped frame means a pending snapshot can only run between frames.
                    viewModel.withMachineLock {
                        nesMachine.stepFrame()
                    }
                    val frame = nesMachine.ppu.frameBuffer.copyOf()
                    currentBitmap =
                        Bitmap.createBitmap(frame, 256, 240, Bitmap.Config.ARGB_8888).asImageBitmap()

                    val available = nesMachine.apu.audioQueue.available()
                    if (available > 0) {
                        val readCount = nesMachine.apu.audioQueue.read(audioReadBuffer, 0, minOf(available, audioReadBuffer.size))
                        audioTrack.write(audioReadBuffer, 0, readCount, AudioTrack.WRITE_NON_BLOCKING)
                    }

                    frameCount++
                    val now = System.currentTimeMillis()
                    val fpsElapsed = now - lastFpsTimestamp
                    if (fpsElapsed >= 1000) {
                        fpsDisplay = (frameCount * 1000f) / fpsElapsed
                        frameCount = 0
                        lastFpsTimestamp = now
                    }

                    val elapsed = System.currentTimeMillis() - frameStartTime
                    val sleepTime = 16L - elapsed
                    if (sleepTime > 0) {
                        delay(sleepTime)
                    } else {
                        yield()
                    }
                }
            } finally {
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }

    if (viewModel.showSettingsDialog) {
        SettingsDialog(
            viewModel = viewModel,
            onLoadCustomRomClick = {
                viewModel.showSettingsDialog = false
                viewModel.showCustomRomWarning = true
            },
            onDismiss = { viewModel.showSettingsDialog = false }
        )
    }

    if (viewModel.showCustomRomWarning) {
        CustomRomWarningDialog(
            onConfirmLoad = onLaunchRomPicker,
            onDismiss = { viewModel.showCustomRomWarning = false }
        )
    }

    if (viewModel.showRemapDialog) {
        RemapControllerDialog(viewModel = viewModel, onDismiss = { viewModel.showRemapDialog = false })
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // Horizontal / Landscape Layout
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: NES Screen (Maintain 4:3 / 256x240 aspect ratio)
            NesDisplayCanvas(
                bitmap = currentBitmap,
                fpsDisplay = fpsDisplay,
                showFps = showFps,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(256f / 240f)
                    .background(Color.DarkGray)
                    .padding(4.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Right: Landscape NES Controller Layout with Settings button
            LandscapeControllerPad(
                nesMachine = nesMachine,
                onOpenSettings = { viewModel.showSettingsDialog = true },
                modifier = Modifier.fillMaxHeight().weight(1f)
            )
        }
    } else {
        // Vertical / Portrait Layout (Screen on top, controls under it)
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: NES Screen (Maintain 4:3 / 256x240 aspect ratio)
            NesDisplayCanvas(
                bitmap = currentBitmap,
                fpsDisplay = fpsDisplay,
                showFps = showFps,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(256f / 240f)
                    .background(Color.DarkGray)
                    .padding(4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom: Portrait NES Controller Layout under the game screen with Settings button
            PortraitControllerPad(
                nesMachine = nesMachine,
                onOpenSettings = { viewModel.showSettingsDialog = true },
                onSaveState = { viewModel.saveState() },
                onLoadState = { viewModel.loadState() },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
fun SettingsDialog(
    viewModel: MainViewModel,
    onLoadCustomRomClick: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emulator Settings") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Switch ROM
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ROM: ${viewModel.currentRomName}")
                    Button(onClick = { viewModel.toggleRom() }) {
                        Text("Switch Built-in")
                    }
                }

                // Load Custom ROM
                Button(
                    onClick = onLoadCustomRomClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Load Custom ROM (.nes)")
                }

                // FPS Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show FPS Counter")
                    Switch(
                        checked = viewModel.showFps,
                        onCheckedChange = { viewModel.updateShowFps(it) }
                    )
                }

                // Remap Controller Button
                Button(
                    onClick = {
                        viewModel.showSettingsDialog = false
                        viewModel.showRemapDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Remap Bluetooth Controller")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun CustomRomWarningDialog(
    onConfirmLoad: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load Custom ROM") },
        text = {
            Text("Warning: This emulator is basic and primarily supports standard NROM (mapper 0) games. Loading other mappers or complex games will likely not work or cause crashes. Proceed?")
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onConfirmLoad()
            }) {
                Text("Proceed")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun RemapControllerDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val waiting = viewModel.waitingForNesButton
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(waiting) {
        if (waiting != null) {
            focusRequester.requestFocus()
        }
    }

    if (waiting != null) {
        AlertDialog(
            onDismissRequest = { viewModel.waitingForNesButton = null },
            title = { Text("Press any button") },
            text = { Text("Press any controller button or keyboard key to assign to ${getNesButtonName(waiting)}...") },
            confirmButton = {
                TextButton(onClick = { viewModel.waitingForNesButton = null }) {
                    Text("Cancel")
                }
            },
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        val code = keyEvent.nativeKeyEvent.keyCode
                        viewModel.saveKeyMapping(waiting, code)
                        viewModel.waitingForNesButton = null
                        true
                    } else {
                        false
                    }
                }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remap Controller") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Tap 'Remap', then press any physical controller or keyboard button.", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(4.dp))

                val buttons = listOf(
                    Controller.BUTTON_A to "A Button",
                    Controller.BUTTON_B to "B Button",
                    Controller.BUTTON_SELECT to "Select",
                    Controller.BUTTON_START to "Start",
                    Controller.BUTTON_UP to "D-Pad Up",
                    Controller.BUTTON_DOWN to "D-Pad Down",
                    Controller.BUTTON_LEFT to "D-Pad Left",
                    Controller.BUTTON_RIGHT to "D-Pad Right"
                )

                for ((btnCode, btnName) in buttons) {
                    val assignedCode = viewModel.buttonToKeyMap[btnCode] ?: 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$btnName (Key: $assignedCode)")
                        Button(onClick = { viewModel.waitingForNesButton = btnCode }) {
                            Text("Remap")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text("Emulator actions (no default binding)", fontSize = 12.sp, color = Color.Gray)

                // Save/load action bindings live in their own map and start unbound; picking key
                // code 0 / "None" clears the binding again.
                for ((actionCode, actionName) in listOf(
                    MainViewModel.ACTION_SAVE_STATE to "Save State",
                    MainViewModel.ACTION_LOAD_STATE to "Load State"
                )) {
                    val assignedCode = viewModel.actionToKeyMap[actionCode] ?: 0
                    val label = if (assignedCode == 0) "None" else "Key: $assignedCode"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$actionName ($label)")
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { viewModel.waitingForNesButton = actionCode }) {
                                Text("Bind")
                            }
                            if (assignedCode != 0) {
                                TextButton(onClick = {
                                    viewModel.saveActionKeyMapping(actionCode, 0)
                                }) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

fun getNesButtonName(btn: Int): String {
    return when (btn) {
        Controller.BUTTON_A -> "A Button"
        Controller.BUTTON_B -> "B Button"
        Controller.BUTTON_SELECT -> "Select"
        Controller.BUTTON_START -> "Start"
        Controller.BUTTON_UP -> "D-Pad Up"
        Controller.BUTTON_DOWN -> "D-Pad Down"
        Controller.BUTTON_LEFT -> "D-Pad Left"
        Controller.BUTTON_RIGHT -> "D-Pad Right"
        MainViewModel.ACTION_SAVE_STATE -> "Save State"
        MainViewModel.ACTION_LOAD_STATE -> "Load State"
        else -> "Button"
    }
}

@Composable
fun NesDisplayCanvas(
    bitmap: ImageBitmap?,
    fpsDisplay: Float,
    showFps: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize()
        ) {
            if (bitmap != null) {
                drawImage(
                    image = bitmap,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            }
        }

        // FPS Overlay
        if (showFps) {
            Text(
                text = "FPS: ${"%.1f".format(fpsDisplay)}",
                color = Color.Yellow,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

@Composable
fun DirectionArrow(direction: Direction, modifier: Modifier = Modifier, color: Color = Color.Black) {
    Canvas(modifier = modifier.size(16.dp)) {
        val path = Path()
        val w = size.width
        val h = size.height
        when (direction) {
            Direction.UP -> {
                path.moveTo(w / 2f, 0f)
                path.lineTo(w, h)
                path.lineTo(0f, h)
            }
            Direction.DOWN -> {
                path.moveTo(0f, 0f)
                path.lineTo(w, 0f)
                path.lineTo(w / 2f, h)
            }
            Direction.LEFT -> {
                path.moveTo(w, 0f)
                path.lineTo(0f, h / 2f)
                path.lineTo(w, h)
            }
            Direction.RIGHT -> {
                path.moveTo(0f, 0f)
                path.lineTo(w, h / 2f)
                path.lineTo(0f, h)
            }
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
fun NesButton(
    text: String? = null,
    iconDirection: Direction? = null,
    shape: Shape = CircleShape,
    modifier: Modifier = Modifier,
    onPressChanged: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    val currentOnPressChanged by rememberUpdatedState(onPressChanged)

    Box(
        modifier = modifier
            .background(if (isPressed) Color.Gray else Color.LightGray, shape = shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!isPressed) {
                        isPressed = true
                        currentOnPressChanged(true)
                    }
                    val pointerId = down.id
                    var upOrCancel = false
                    while (!upOrCancel) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            upOrCancel = true
                        }
                    }
                    if (isPressed) {
                        isPressed = false
                        currentOnPressChanged(false)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (text != null) {
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPressed) Color.White else Color.Black
            )
        } else if (iconDirection != null) {
            DirectionArrow(
                direction = iconDirection,
                color = if (isPressed) Color.White else Color.Black
            )
        }
    }
}

@Composable
fun LandscapeControllerPad(
    nesMachine: NesMachine,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF222222))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // D-Pad on the left side of controls
            Box(
                modifier = Modifier.size(160.dp),
                contentAlignment = Alignment.Center
            ) {
                // Center junction
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .background(Color.LightGray)
                )

                // Up
                NesButton(
                    iconDirection = Direction.UP,
                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .size(width = 50.dp, height = 55.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_UP, pressed) }

                // Down
                NesButton(
                    iconDirection = Direction.DOWN,
                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(width = 50.dp, height = 55.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_DOWN, pressed) }

                // Left
                NesButton(
                    iconDirection = Direction.LEFT,
                    shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(width = 55.dp, height = 50.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_LEFT, pressed) }

                // Right
                NesButton(
                    iconDirection = Direction.RIGHT,
                    shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(width = 55.dp, height = 50.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_RIGHT, pressed) }
            }

            // Center: Select & Start
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                NesButton(
                    text = "SELECT",
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .width(75.dp)
                        .height(32.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_SELECT, pressed) }

                Spacer(modifier = Modifier.height(16.dp))

                NesButton(
                    text = "START",
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .width(75.dp)
                        .height(32.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_START, pressed) }
            }

            // Right: B and A Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Button B
                NesButton(
                    text = "B",
                    shape = CircleShape,
                    modifier = Modifier.size(60.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_B, pressed) }

                // Button A
                NesButton(
                    text = "A",
                    shape = CircleShape,
                    modifier = Modifier.size(60.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_A, pressed) }
            }
        }

        // Settings Button in Top-Right corner of controller area
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(40.dp)
        ) {
            Text(text = "⚙", color = Color.White, fontSize = 22.sp)
        }
    }
}

@Composable
fun PortraitControllerPad(
    nesMachine: NesMachine,
    onOpenSettings: () -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF222222), shape = RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            // Upper controls: D-Pad on left, Action buttons (B & A) on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // D-Pad
                Box(
                    modifier = Modifier.size(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color.LightGray)
                    )

                    // Up
                    NesButton(
                        iconDirection = Direction.UP,
                        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(width = 46.dp, height = 52.dp)
                    ) { pressed -> nesMachine.pressButton(Controller.BUTTON_UP, pressed) }

                    // Down
                    NesButton(
                        iconDirection = Direction.DOWN,
                        shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .size(width = 46.dp, height = 52.dp)
                    ) { pressed -> nesMachine.pressButton(Controller.BUTTON_DOWN, pressed) }

                    // Left
                    NesButton(
                        iconDirection = Direction.LEFT,
                        shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(width = 52.dp, height = 46.dp)
                    ) { pressed -> nesMachine.pressButton(Controller.BUTTON_LEFT, pressed) }

                    // Right
                    NesButton(
                        iconDirection = Direction.RIGHT,
                        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(width = 52.dp, height = 46.dp)
                    ) { pressed -> nesMachine.pressButton(Controller.BUTTON_RIGHT, pressed) }
                }

                // B & A Action Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Button B
                    NesButton(
                        text = "B",
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) { pressed -> nesMachine.pressButton(Controller.BUTTON_B, pressed) }

                    // Button A
                    NesButton(
                        text = "A",
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) { pressed -> nesMachine.pressButton(Controller.BUTTON_A, pressed) }
                }
            }

            // Lower controls: Select & Start centered
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NesButton(
                    text = "SELECT",
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .width(80.dp)
                        .height(36.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_SELECT, pressed) }

                NesButton(
                    text = "START",
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .width(80.dp)
                        .height(36.dp)
                ) { pressed -> nesMachine.pressButton(Controller.BUTTON_START, pressed) }
            }

            // Save-state buttons: stacked vertically under the pad controls, deliberately styled
            // apart from the NES buttons so they read as emulator functions, not game input.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onSaveState,
                    modifier = Modifier
                        .width(120.dp)
                        .height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("SAVE", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onLoadState,
                    modifier = Modifier
                        .width(120.dp)
                        .height(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("LOAD", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Settings Button in Top-Right corner of controller area
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(40.dp)
        ) {
            Text(text = "⚙", color = Color.White, fontSize = 22.sp)
        }
    }
}
