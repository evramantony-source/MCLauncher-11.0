package com.mclauncher.app.engine

import android.content.Context
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

object NativeLaunchBridge {
    private val kotlinLaunchRunning = AtomicBoolean(false)

    private val loadResult: Result<Unit> = runCatching {
        System.loadLibrary("mclauncher")
    }

    val isAvailable: Boolean
        get() = loadResult.isSuccess

    val unavailableReason: String
        get() = loadResult.exceptionOrNull()?.message ?: "Native engine is not bundled"

    fun tryClaimLaunch(): Boolean = kotlinLaunchRunning.compareAndSet(false, true)

    fun releaseLaunch() {
        kotlinLaunchRunning.set(false)
    }

    external fun nativeStart(
        context: Context,
        javaHome: String,
        workingDirectory: String,
        jvmArguments: Array<String>,
        mainClass: String,
        gameArguments: Array<String>,
        environmentKeys: Array<String>,
        environmentValues: Array<String>,
        preloadLibraries: Array<String>,
        surface: Surface?
    ): Int

    external fun nativeSetSurface(surface: Surface?)
    external fun nativeStop()
    external fun nativeDrainLogs(): String?

    external fun nativeSendKey(key: Int, action: Int, modifiers: Int)
    external fun nativeSendRawKey(androidKey: Int, glfwKey: Int, action: Int, modifiers: Int, unicode: Int)
    external fun nativeSendChar(codePoint: Int)
    external fun nativeSendMouseButton(button: Int, action: Int, modifiers: Int)
    external fun nativeSendCursorDelta(dx: Float, dy: Float)
    external fun nativeSendCursorPosition(x: Double, y: Double)
    external fun nativeSendTouchButton(
        x: Double,
        y: Double,
        button: Int,
        action: Int,
        modifiers: Int
    )
    external fun nativeSyncPointerState(x: Double, y: Double, grabbing: Boolean)
    external fun nativeSendScroll(dx: Float, dy: Float)
    external fun nativeSendGamepadAxis(axis: Int, value: Float)
}
