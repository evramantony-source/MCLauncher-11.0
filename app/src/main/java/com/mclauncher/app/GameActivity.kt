package com.mclauncher.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mclauncher.app.engine.GameInputBridge
import com.mclauncher.app.engine.GyroInputController
import com.mclauncher.app.engine.NativeEngineCoordinator
import com.mclauncher.app.engine.NativeLaunchBridge
import com.mclauncher.app.ui.game.GameTouchOverlay
import com.mclauncher.app.ui.theme.MCLauncherTheme
import com.mclauncher.minecraft.LaunchPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import git.artdeell.dnbootstrap.glfw.GLFW

class GameActivity : ComponentActivity() {
    private var gyroInputController: GyroInputController? = null
    private var gyroEnabled: Boolean = false
    private var gyroSensitivity: Float = 1f
    private var physicalMouseCapture: Boolean = true
    private var hideTouchControlsWithExternalInput: Boolean = false
    private var gameSurfaceView: SurfaceView? = null
    @Volatile private var launcherOverlayOwnsTouch: Boolean = true
    private var externalInputDetected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val planPath = intent.getStringExtra(EXTRA_PLAN_PATH).orEmpty()
        val launchPlan = runBlocking(Dispatchers.IO) {
            runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString(
                    LaunchPlan.serializer(),
                    File(planPath).readText()
                )
            }.getOrNull()
        }
        val targetBufferWidth = launchPlan?.windowWidth?.coerceAtLeast(1)
        val targetBufferHeight = launchPlan?.windowHeight?.coerceAtLeast(1)
        val launcherSettings = runBlocking(Dispatchers.IO) {
            (application as MCLauncherApplication).launcherStore.load().settings
        }
        GameInputBridge.configure(
            launcherSettings.controllerBindings,
            launcherSettings.lookSensitivity,
            launcherSettings.gamepadDeadZone
        )
        gyroEnabled = launcherSettings.gyroEnabled
        gyroSensitivity = launcherSettings.lookSensitivity
        physicalMouseCapture = launcherSettings.physicalMouseCapture
        hideTouchControlsWithExternalInput = launcherSettings.hideTouchControlsWithExternalInput
        if (launcherSettings.sustainedPerformanceMode) {
            runCatching { window.setSustainedPerformanceMode(true) }
        }
        gyroInputController = GyroInputController(this)
        GLFW.initializeBridge(applicationContext)
        GLFW.setGrabListener { grabbing ->
            runOnUiThread {
                GameInputBridge.syncPointerState(GLFW.cursorX, GLFW.cursorY, grabbing)
                if (physicalMouseCapture && grabbing) {
                    runCatching { gameSurfaceView?.requestPointerCapture() }
                } else {
                    runCatching { gameSurfaceView?.releasePointerCapture() }
                }
                GameInputBridge.resetPointerPosition()
            }
        }
        GLFW.setCursorListener { cursor ->
            runOnUiThread {
                val view = gameSurfaceView ?: return@runOnUiThread
                view.pointerIcon = if (cursor == null) {
                    PointerIcon.getSystemIcon(this, PointerIcon.TYPE_DEFAULT)
                } else {
                    runCatching { PointerIcon.create(cursor.bitmap, cursor.hotX.toFloat(), cursor.hotY.toFloat()) }
                        .getOrElse { PointerIcon.getSystemIcon(this, PointerIcon.TYPE_DEFAULT) }
                }
            }
        }
        val coordinator = NativeEngineCoordinator(applicationContext)
        val dataRoot = File(planPath).parentFile?.parentFile ?: filesDir
        val sessionLog = File(dataRoot, "logs/latest-session.log").apply {
            parentFile?.mkdirs()
            writeText("MCLauncher 11.0 alpha22 session ${System.currentTimeMillis()}\n")
        }

        setContent {
            MCLauncherTheme(themeMode = launcherSettings.themeMode) {
                var surface by remember { mutableStateOf<Surface?>(null) }
                var status by remember { mutableStateOf("Waiting for game surface") }
                var running by remember { mutableStateOf(false) }
                var launchOverlayVisible by remember { mutableStateOf(true) }
                var controlsVisible by remember { mutableStateOf(true) }
                var gameMenuRequested by remember { mutableStateOf(false) }
                val logs = remember { mutableStateListOf<String>() }
                val pointerState by GameInputBridge.pointerState.collectAsState()
                val directTouchEnabled = running &&
                    !launchOverlayVisible &&
                    !gameMenuRequested &&
                    launcherSettings.virtualMouseEnabled &&
                    !pointerState.grabbed

                SideEffect {
                    launcherOverlayOwnsTouch = launchOverlayVisible || gameMenuRequested
                }

                DisposableEffect(directTouchEnabled) {
                    GameInputBridge.setDirectTouchCaptureEnabled(directTouchEnabled)
                    onDispose {
                        GameInputBridge.setDirectTouchCaptureEnabled(false)
                    }
                }

                LaunchedEffect(externalInputDetected) {
                    if (externalInputDetected && hideTouchControlsWithExternalInput) controlsVisible = false
                }

                fun appendLog(text: String, persist: Boolean = true) {
                    text.lineSequence().filter(String::isNotBlank).forEach { line ->
                        logs += line
                        if (persist) runCatching { sessionLog.appendText(line + "\n") }
                    }
                    while (logs.size > 300) logs.removeAt(0)
                }

                fun beginLaunch(target: Surface) {
                    if (running) return
                    when {
                        !NativeLaunchBridge.isAvailable -> {
                            status = "Native engine unavailable"
                            appendLog(NativeLaunchBridge.unavailableReason)
                        }
                        planPath.isBlank() -> status = "Launch plan missing"
                        else -> {
                            running = true
                            launchOverlayVisible = true
                            status = "Starting Minecraft"
                            appendLog("Opening ${File(planPath).name}")
                        }
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        if (NativeLaunchBridge.isAvailable) {
                            runCatching { NativeLaunchBridge.nativeSetSurface(null) }
                            runCatching { NativeLaunchBridge.nativeStop() }
                        }
                    }
                }

                // A SurfaceView may report surfaceChanged several times during startup.
                // The launch belongs to the Play attempt, not to each Surface instance.
                LaunchedEffect(running) {
                    val target = surface ?: return@LaunchedEffect
                    if (!running || !NativeLaunchBridge.isAvailable) return@LaunchedEffect
                    if (!NativeLaunchBridge.tryClaimLaunch()) {
                        appendLog("Ignored duplicate UI launch request", persist = false)
                        return@LaunchedEffect
                    }
                    val result = try {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                coordinator.launch(File(planPath), target, sessionLog)
                            }
                        }
                    } finally {
                        NativeLaunchBridge.releaseLaunch()
                    }
                    result.onSuccess { exitCode ->
                        status = if (exitCode == 0) "Minecraft closed" else "Minecraft exited with code $exitCode"
                        appendLog(status)
                        archiveSessionLog(sessionLog, if (exitCode == 0) "closed" else "exit-$exitCode")
                    }.onFailure { error ->
                        status = "Launch failed"
                        appendLog(error.stackTraceToString())
                        appendLog("Full session log: ${sessionLog.absolutePath}")
                        archiveSessionLog(sessionLog, "failed")
                    }
                    running = false
                    launchOverlayVisible = true
                }

                LaunchedEffect(running) {
                    while (running && NativeLaunchBridge.isAvailable) {
                        val batch = withContext(Dispatchers.IO) { NativeLaunchBridge.nativeDrainLogs() }
                        if (!batch.isNullOrBlank()) {
                            // Native lines are written directly to the session file so they
                            // survive a hard process crash. Draining only updates the overlay.
                            appendLog(batch, persist = false)
                            if (
                                batch.contains("Invoking ") ||
                                batch.contains("LWJGL", ignoreCase = true) ||
                                batch.contains("OpenGL", ignoreCase = true) ||
                                batch.contains("Vulkan", ignoreCase = true)
                            ) {
                                status = "Minecraft is running"
                                launchOverlayVisible = false
                            }
                        }
                        delay(100)
                    }
                    val finalBatch = if (NativeLaunchBridge.isAvailable) {
                        withContext(Dispatchers.IO) { NativeLaunchBridge.nativeDrainLogs() }
                    } else null
                    if (!finalBatch.isNullOrBlank()) appendLog(finalBatch, persist = false)
                }

                LaunchedEffect(running, launchOverlayVisible) {
                    if (running && launchOverlayVisible) {
                        delay(3_500)
                        if (running) launchOverlayVisible = false
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            SurfaceView(context).also { view ->
                                gameSurfaceView = view
                                view.keepScreenOn = true
                                view.isFocusable = true
                                view.isFocusableInTouchMode = true
                                view.requestFocus()
                                if (targetBufferWidth != null && targetBufferHeight != null) {
                                    // Keep the native buffer in the exact resolution Minecraft was
                                    // launched with. The SurfaceView itself still fills the tablet;
                                    // input is transformed between these two coordinate spaces.
                                    view.holder.setFixedSize(targetBufferWidth, targetBufferHeight)
                                }
                                view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                                    GameInputBridge.setInputViewSize(right - left, bottom - top)
                                }
                                val directTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                                val directTouchDragHold = ViewConfiguration.getLongPressTimeout().toLong()
                                // Keep touchscreen coordinates in the SurfaceView's own
                                // coordinate space. Reconstructing them at Activity level
                                // introduces status-bar and compatibility-mode offsets on
                                // large-screen Android devices.
                                view.setOnTouchListener { _, event ->
                                    val touchSource = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
                                        event.isFromSource(InputDevice.SOURCE_STYLUS)
                                    val canHandle = touchSource &&
                                        !launcherOverlayOwnsTouch &&
                                        GameInputBridge.isDirectTouchCaptureEnabled()
                                    if (!canHandle) {
                                        false
                                    } else {
                                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                            view.parent?.requestDisallowInterceptTouchEvent(true)
                                        }
                                        val handled = GameInputBridge.handleDirectTouch(
                                            event = event,
                                            width = view.width,
                                            height = view.height,
                                            dragThresholdPixels = directTouchSlop,
                                            dragActivationDelayMillis = directTouchDragHold
                                        )
                                        if (
                                            event.actionMasked == MotionEvent.ACTION_UP ||
                                            event.actionMasked == MotionEvent.ACTION_CANCEL
                                        ) {
                                            view.parent?.requestDisallowInterceptTouchEvent(false)
                                        }
                                        handled
                                    }
                                }
                                view.holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        surface = holder.surface
                                        GameInputBridge.setInputViewSize(view.width, view.height)
                                        GameInputBridge.setGameBufferSize(
                                            targetBufferWidth ?: view.width,
                                            targetBufferHeight ?: view.height
                                        )
                                        if (NativeLaunchBridge.isAvailable) {
                                            runCatching { NativeLaunchBridge.nativeSetSurface(holder.surface) }
                                        }
                                        status = "Game surface ready"
                                        beginLaunch(holder.surface)
                                    }

                                    override fun surfaceChanged(
                                        holder: SurfaceHolder,
                                        format: Int,
                                        width: Int,
                                        height: Int
                                    ) {
                                        surface = holder.surface
                                        GameInputBridge.setInputViewSize(view.width, view.height)
                                        GameInputBridge.setGameBufferSize(width, height)
                                        runCatching {
                                            sessionLog.appendText(
                                                "Android game surface input=${view.width}x${view.height} " +
                                                    "buffer=${width}x${height}\n"
                                            )
                                        }
                                        if (NativeLaunchBridge.isAvailable) {
                                            runCatching { NativeLaunchBridge.nativeSetSurface(holder.surface) }
                                        }
                                    }

                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        surface = null
                                        if (NativeLaunchBridge.isAvailable) {
                                            runCatching { NativeLaunchBridge.nativeSetSurface(null) }
                                        }
                                    }
                                })
                            }
                        }
                    )

                    GameTouchOverlay(
                        visible = running &&
                            !launchOverlayVisible &&
                            !gameMenuRequested &&
                            controlsVisible,
                        settings = launcherSettings,
                        pointerState = pointerState,
                        onMenu = { gameMenuRequested = !gameMenuRequested },
                        onToggleVisibility = { controlsVisible = false }
                    )

                    if (running && !launchOverlayVisible && !controlsVisible) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .height(40.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { controlsVisible = true })
                                }
                        ) {
                            Text("Controls", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (gameMenuRequested && running && !launchOverlayVisible) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(0.72f)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                    MaterialTheme.shapes.large
                                )
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Game menu", style = MaterialTheme.typography.titleLarge)
                            Button(onClick = { gameMenuRequested = false }) { Text("Return to game") }
                            OutlinedButton(onClick = { controlsVisible = !controlsVisible }) {
                                Text(if (controlsVisible) "Hide touch controls" else "Show touch controls")
                            }
                            OutlinedButton(onClick = { finish() }) { Text("Close game") }
                        }
                    }

                    if (launchOverlayVisible) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(0.9f)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                                    MaterialTheme.shapes.large
                                )
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (running) CircularProgressIndicator()
                            else Icon(Icons.Rounded.Terminal, contentDescription = null)
                            Text(status, style = MaterialTheme.typography.titleLarge)

                            if (logs.isNotEmpty()) {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    items(logs.takeLast(12)) { line ->
                                        Text(
                                            line,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (!running && surface != null && NativeLaunchBridge.isAvailable && planPath.isNotBlank()) {
                                Button(onClick = { beginLaunch(surface!!) }) { Text("Retry launch") }
                            }
                            OutlinedButton(onClick = { finish() }) {
                                Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                                Text("Back to launcher", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (gyroEnabled) gyroInputController?.start(gyroSensitivity)
    }

    override fun onPause() {
        gyroInputController?.stop()
        GameInputBridge.releaseAll()
        runCatching { gameSurfaceView?.releasePointerCapture() }
        super.onPause()
    }

    private fun archiveSessionLog(log: File, reason: String) {
        if (!log.isFile) return
        val archive = File(log.parentFile, "session-${System.currentTimeMillis()}-$reason.log")
        runCatching { log.copyTo(archive, overwrite = true) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.deviceId >= 0 && event.isFromSource(InputDevice.SOURCE_KEYBOARD)) {
            externalInputDetected = true
        }
        if (GameInputBridge.handleAndroidKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        ) externalInputDetected = true
        if (GameInputBridge.handleGenericMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            externalInputDetected = true
            if (physicalMouseCapture && GLFW.isGrabbing() && event.actionMasked == MotionEvent.ACTION_DOWN) {
                runCatching { gameSurfaceView?.requestPointerCapture() }
            }
            if (GameInputBridge.handlePointerButtonEvent(event)) return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        GameInputBridge.resetPointerPosition()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        launcherOverlayOwnsTouch = true
        gyroInputController?.stop()
        GameInputBridge.releaseAll()
        GLFW.setGrabListener(null)
        GLFW.setCursorListener(null)
        gameSurfaceView = null
        if (NativeLaunchBridge.isAvailable) {
            runCatching { NativeLaunchBridge.nativeSetSurface(null) }
            if (isFinishing) runCatching { NativeLaunchBridge.nativeStop() }
        }
        val terminateMinecraftProcess = isFinishing
        super.onDestroy()
        if (terminateMinecraftProcess) {
            // GameActivity runs in :minecraft. HotSpot cannot be safely restarted in the
            // same Android process, so close only this isolated process after the activity.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 120)
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    companion object {
        const val EXTRA_PLAN_PATH = "launch_plan_path"
    }
}
