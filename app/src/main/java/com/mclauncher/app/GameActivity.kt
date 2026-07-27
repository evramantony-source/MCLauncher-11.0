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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.File
import git.artdeell.dnbootstrap.glfw.GLFW

class GameActivity : ComponentActivity() {
    private var gyroInputController: GyroInputController? = null
    private var gyroEnabled: Boolean = false
    private var gyroSensitivity: Float = 1f
    private var physicalMouseCapture: Boolean = true
    private var hideTouchControlsWithExternalInput: Boolean = false
    private var gameSurfaceView: SurfaceView? = null
    private var externalInputDetected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val planPath = intent.getStringExtra(EXTRA_PLAN_PATH).orEmpty()
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
            writeText("MCLauncher 11.0 alpha01 session ${System.currentTimeMillis()}\n")
        }

        setContent {
            MCLauncherTheme {
                var surface by remember { mutableStateOf<Surface?>(null) }
                var status by remember { mutableStateOf("Waiting for game surface") }
                var running by remember { mutableStateOf(false) }
                var launchOverlayVisible by remember { mutableStateOf(true) }
                var controlsVisible by remember { mutableStateOf(true) }
                var gameMenuRequested by remember { mutableStateOf(false) }
                val logs = remember { mutableStateListOf<String>() }

                LaunchedEffect(externalInputDetected) {
                    if (externalInputDetected && hideTouchControlsWithExternalInput) controlsVisible = false
                }

                fun appendLog(text: String) {
                    text.lineSequence().filter(String::isNotBlank).forEach { line ->
                        logs += line
                        runCatching { sessionLog.appendText(line + "\n") }
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

                LaunchedEffect(running, surface) {
                    val target = surface ?: return@LaunchedEffect
                    if (!running || !NativeLaunchBridge.isAvailable) return@LaunchedEffect
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            coordinator.launch(File(planPath), target, sessionLog)
                        }
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
                            appendLog(batch)
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
                    if (!finalBatch.isNullOrBlank()) appendLog(finalBatch)
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
                                view.holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        surface = holder.surface
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
                        visible = running && !launchOverlayVisible && controlsVisible,
                        settings = launcherSettings,
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
            if (physicalMouseCapture && event.actionMasked == MotionEvent.ACTION_DOWN) {
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
