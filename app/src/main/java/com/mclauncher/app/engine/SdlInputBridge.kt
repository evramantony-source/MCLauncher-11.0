package com.mclauncher.app.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Android/ART side of MojoSDL for Minecraft 26.3 Snapshot 4 and newer.
 *
 * The exact SDL bindings are produced from the pinned engine source and added to
 * the final APK at build time. Reflection lets normal JVM tests compile without
 * a generated AAR while keeping every JNI class name identical to MojoSDL.
 */
object SdlInputBridge {
    private const val SDL_CLASS = "git.mojo.sdl.SDL"
    private const val SDL_ACTIVITY_CLASS = "git.mojo.sdl.SDLActivity"
    private const val SDL_INPUT_CONNECTION_CLASS = "git.mojo.sdl.SDLInputConnection"
    private const val GRAB_LISTENER_CLASS = "git.mojo.sdl.GrabListener"
    private const val CLIPBOARD_CLASS = "git.mojo.sdl.SDLClipboard"

    @Volatile private var prepared = false
    @Volatile private var active = false
    @Volatile private var grabbing = false
    @Volatile private var grabListener: ((Boolean) -> Unit)? = null

    private var hostActivity: Activity? = null
    private var pendingSurface: Surface? = null
    private var surfacePublished = false
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var refreshRate = 60f
    private var mouseButtonState = 0
    private var grabListenerProxy: Any? = null
    private var clipboardProxy: Any? = null

    private lateinit var setNativeSurface: Method
    private lateinit var setInitCallback: Method
    private lateinit var addGrabListener: Method
    private lateinit var setClipboard: Method
    private lateinit var onNativeSurfaceCreated: Method
    private lateinit var onNativeSurfaceChanged: Method
    private lateinit var onNativeSurfaceDestroyed: Method
    private lateinit var nativeSetScreenResolution: Method
    private lateinit var onNativeResize: Method
    private lateinit var onNativeKeyDown: Method
    private lateinit var onNativeKeyUp: Method
    private lateinit var onNativeKeyboardFocusLost: Method
    private lateinit var onNativeMouse: Method
    private lateinit var nativeFocusChanged: Method
    private lateinit var nativeCommitText: Method

    val isPrepared: Boolean get() = prepared
    val isActive: Boolean get() = active
    val isGrabbing: Boolean get() = grabbing

    fun setGrabStateListener(listener: ((Boolean) -> Unit)?) {
        grabListener = listener
    }

    @Synchronized
    fun prepare(activity: Activity): Result<Unit> {
        if (prepared) {
            hostActivity = activity
            return Result.success(Unit)
        }
        return runCatching {
            hostActivity = activity
            val loader = activity.classLoader
            val sdl = Class.forName(SDL_CLASS, true, loader)
            val sdlActivity = Class.forName(SDL_ACTIVITY_CLASS, true, loader)
            val inputConnection = Class.forName(SDL_INPUT_CONNECTION_CLASS, true, loader)
            val grabInterface = Class.forName(GRAB_LISTENER_CLASS, true, loader)
            val clipboardInterface = Class.forName(CLIPBOARD_CLASS, true, loader)
            val intType = Int::class.javaPrimitiveType!!
            val floatType = Float::class.javaPrimitiveType!!
            val booleanType = Boolean::class.javaPrimitiveType!!

            setNativeSurface = sdlActivity.getMethod("setNativeSurface", Surface::class.java)
            setInitCallback = sdlActivity.getMethod("setInitCallback", Runnable::class.java)
            addGrabListener = sdlActivity.getMethod("addGrabListener", grabInterface)
            setClipboard = sdlActivity.getMethod("setClipboard", clipboardInterface)
            onNativeSurfaceCreated = sdlActivity.getMethod("onNativeSurfaceCreated")
            onNativeSurfaceChanged = sdlActivity.getMethod("onNativeSurfaceChanged")
            onNativeSurfaceDestroyed = sdlActivity.getMethod("onNativeSurfaceDestroyed")
            nativeSetScreenResolution = sdlActivity.getMethod(
                "nativeSetScreenResolution",
                intType,
                intType,
                intType,
                intType,
                floatType,
                floatType
            )
            onNativeResize = sdlActivity.getMethod("onNativeResize")
            onNativeKeyDown = sdlActivity.getMethod("onNativeKeyDown", intType)
            onNativeKeyUp = sdlActivity.getMethod("onNativeKeyUp", intType)
            onNativeKeyboardFocusLost = sdlActivity.getMethod("onNativeKeyboardFocusLost")
            onNativeMouse = sdlActivity.getMethod(
                "onNativeMouse",
                intType,
                intType,
                floatType,
                floatType,
                booleanType
            )
            nativeFocusChanged = sdlActivity.getMethod(
                "nativeFocusChanged",
                booleanType
            )
            nativeCommitText = inputConnection.getMethod(
                "nativeCommitText",
                String::class.java,
                intType
            )

            val initCallback = Runnable { onSdlInitialized() }
            setInitCallback.invoke(null, initCallback)

            grabListenerProxy = Proxy.newProxyInstance(
                loader,
                arrayOf(grabInterface)
            ) { proxy, method, arguments ->
                when (method.name) {
                    "onGrabState" -> {
                        val next = arguments?.firstOrNull() as? Boolean ?: false
                        grabbing = next
                        grabListener?.invoke(next)
                        null
                    }
                    "toString" -> "MCLauncherSDLGrabListener"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            }.also { addGrabListener.invoke(null, it) }

            val clipboard = requireNotNull(
                activity.getSystemService(ClipboardManager::class.java)
            ) { "Android clipboard service is unavailable" }
            clipboardProxy = Proxy.newProxyInstance(
                loader,
                arrayOf(clipboardInterface)
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getClipboardString" -> clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(activity)
                        ?.toString()
                        .orEmpty()
                    "setClipboardString" -> {
                        val value = arguments?.firstOrNull()?.toString().orEmpty()
                        clipboard.setPrimaryClip(ClipData.newPlainText("Minecraft", value))
                        null
                    }
                    "toString" -> "MCLauncherSDLClipboard"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            }.also { setClipboard.invoke(null, it) }

            // Match MojoLauncher's proven initialization order. libSDL3.so and
            // its libmojoexec.so dependency are packaged in the app native dir.
            sdl.getMethod("initialize").invoke(null)
            sdl.getMethod("setContext", Activity::class.java).invoke(null, activity)
            sdl.getMethod("setupJNI").invoke(null)
            prepared = true
        }
    }

    @Synchronized
    fun surfaceCreated(surface: Surface, width: Int, height: Int, rate: Float) {
        pendingSurface = surface
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        refreshRate = rate.takeIf { it > 0f } ?: 60f
        if (active) publishSurface(created = !surfacePublished)
    }

    @Synchronized
    fun surfaceChanged(surface: Surface, width: Int, height: Int, rate: Float) {
        pendingSurface = surface
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        refreshRate = rate.takeIf { it > 0f } ?: 60f
        if (active) publishSurface(created = !surfacePublished)
    }

    @Synchronized
    fun surfaceDestroyed() {
        if (active && surfacePublished) invokeQuietly(onNativeSurfaceDestroyed)
        surfacePublished = false
        pendingSurface = null
        if (prepared) invokeQuietly(setNativeSurface, null)
    }

    fun focusChanged(hasFocus: Boolean) {
        if (active) invokeQuietly(nativeFocusChanged, hasFocus)
    }

    fun keyboardFocusLost() {
        if (active) invokeQuietly(onNativeKeyboardFocusLost)
    }

    fun key(androidKeyCode: Int, pressed: Boolean) {
        if (!active || androidKeyCode == KeyEvent.KEYCODE_UNKNOWN) return
        invokeQuietly(if (pressed) onNativeKeyDown else onNativeKeyUp, androidKeyCode)
    }

    fun commitCodePoint(codePoint: Int) {
        if (!active || !Character.isValidCodePoint(codePoint)) return
        invokeQuietly(nativeCommitText, String(Character.toChars(codePoint)), 0)
    }

    @Synchronized
    fun mouseButton(androidButton: Int, pressed: Boolean, x: Float, y: Float, relative: Boolean) {
        if (!active) return
        val next = if (pressed) {
            mouseButtonState or androidButton
        } else {
            mouseButtonState and androidButton.inv()
        }
        if (next == mouseButtonState) return
        mouseButtonState = next
        invokeQuietly(
            onNativeMouse,
            mouseButtonState,
            if (pressed) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
            x,
            y,
            relative
        )
    }

    fun mouseMotion(x: Float, y: Float, relative: Boolean) {
        if (active) {
            invokeQuietly(onNativeMouse, 0, MotionEvent.ACTION_MOVE, x, y, relative)
        }
    }

    fun mouseScroll(x: Float, y: Float) {
        if (active) {
            invokeQuietly(onNativeMouse, 0, MotionEvent.ACTION_SCROLL, x, y, false)
        }
    }

    @Synchronized
    fun shutdown() {
        keyboardFocusLost()
        focusChanged(false)
        surfaceDestroyed()
        active = false
        grabbing = false
        mouseButtonState = 0
        grabListener = null
        hostActivity = null
    }

    @Synchronized
    private fun onSdlInitialized() {
        active = true
        pendingSurface?.let { publishSurface(created = !surfacePublished) }
    }

    @Suppress("DEPRECATION")
    private fun publishSurface(created: Boolean) {
        val surface = pendingSurface ?: return
        invokeQuietly(setNativeSurface, surface)
        if (created) invokeQuietly(onNativeSurfaceCreated)

        // SDL's Android backend deliberately keeps the render-buffer dimensions
        // separate from the physical display dimensions. Passing the fixed
        // SurfaceView buffer as both values makes SDL think a landscape tablet is
        // portrait when the buffer has been resized, which rotates Minecraft while
        // the Android overlay remains correctly oriented. Mirror upstream SDLSurface:
        // report the fixed Surface dimensions first and the real display metrics
        // second, including Android's logical density.
        val metrics = DisplayMetrics()
        val displayMetricsAvailable = runCatching {
            hostActivity?.windowManager?.defaultDisplay?.getRealMetrics(metrics)
            metrics.widthPixels > 0 && metrics.heightPixels > 0
        }.getOrDefault(false)
        val deviceWidth = if (displayMetricsAvailable) metrics.widthPixels else surfaceWidth
        val deviceHeight = if (displayMetricsAvailable) metrics.heightPixels else surfaceHeight
        val density = if (displayMetricsAvailable && metrics.densityDpi > 0) {
            metrics.densityDpi / 160f
        } else {
            1f
        }

        invokeQuietly(
            nativeSetScreenResolution,
            surfaceWidth,
            surfaceHeight,
            deviceWidth,
            deviceHeight,
            density,
            refreshRate
        )
        invokeQuietly(onNativeResize)
        invokeQuietly(onNativeSurfaceChanged)
        surfacePublished = true
    }

    private fun invokeQuietly(method: Method, vararg arguments: Any?) {
        runCatching { method.invoke(null, *arguments) }
    }
}
