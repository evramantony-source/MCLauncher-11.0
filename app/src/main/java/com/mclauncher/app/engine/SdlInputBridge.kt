package com.mclauncher.app.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Android/ART bridge for the SDL3 backend used by Minecraft 26.3 Snapshot 4+. */
object SdlInputBridge {
    private const val SDL_CLASS = "git.mojo.sdl.SDL"
    private const val SDL_ACTIVITY_CLASS = "git.mojo.sdl.SDLActivity"
    private const val SDL_INPUT_CONNECTION_CLASS = "git.mojo.sdl.SDLInputConnection"
    private const val GRAB_LISTENER_CLASS = "git.mojo.sdl.GrabListener"
    private const val CLIPBOARD_CLASS = "git.mojo.sdl.SDLClipboard"

    private const val SDL_ORIENTATION_UNKNOWN = 0
    private const val SDL_ORIENTATION_LANDSCAPE = 1
    private const val SDL_ORIENTATION_LANDSCAPE_FLIPPED = 2
    private const val SDL_ORIENTATION_PORTRAIT = 3
    private const val SDL_ORIENTATION_PORTRAIT_FLIPPED = 4

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
    private var nativeSetNaturalOrientation: Method? = null
    private var onNativeRotationChanged: Method? = null

    val isPrepared: Boolean get() = prepared
    val isActive: Boolean get() = active
    val isGrabbing: Boolean get() = grabbing

    fun setGrabStateListener(listener: ((Boolean) -> Unit)?) { grabListener = listener }

    @Synchronized
    fun prepare(activity: Activity): Result<Unit> {
        if (prepared) {
            hostActivity = activity
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            publishAndroidOrientation()
            return Result.success(Unit)
        }
        return runCatching {
            hostActivity = activity
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
                "nativeSetScreenResolution", intType, intType, intType, intType, floatType, floatType
            )
            onNativeResize = sdlActivity.getMethod("onNativeResize")
            onNativeKeyDown = sdlActivity.getMethod("onNativeKeyDown", intType)
            onNativeKeyUp = sdlActivity.getMethod("onNativeKeyUp", intType)
            onNativeKeyboardFocusLost = sdlActivity.getMethod("onNativeKeyboardFocusLost")
            onNativeMouse = sdlActivity.getMethod("onNativeMouse", intType, intType, floatType, floatType, booleanType)
            nativeFocusChanged = sdlActivity.getMethod("nativeFocusChanged", booleanType)
            nativeCommitText = inputConnection.getMethod("nativeCommitText", String::class.java, intType)

            // SDL 3.2+ added these callbacks as part of Android natural-orientation
            // handling. They are optional so older pinned bindings still work.
            nativeSetNaturalOrientation = runCatching { sdlActivity.getMethod("nativeSetNaturalOrientation", intType) }.getOrNull()
            onNativeRotationChanged = runCatching { sdlActivity.getMethod("onNativeRotationChanged", intType) }.getOrNull()

            setInitCallback.invoke(null, Runnable { onSdlInitialized() })

            grabListenerProxy = Proxy.newProxyInstance(loader, arrayOf(grabInterface)) { proxy, method, arguments ->
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

            val clipboard = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
            clipboardProxy = Proxy.newProxyInstance(loader, arrayOf(clipboardInterface)) { proxy, method, arguments ->
                when (method.name) {
                    "getClipboardString" -> clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
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

            sdl.getMethod("initialize").invoke(null)
            sdl.getMethod("setContext", Activity::class.java).invoke(null, activity)
            sdl.getMethod("setupJNI").invoke(null)
            publishAndroidOrientation()
            prepared = true
        }
    }

    @Synchronized
    fun surfaceCreated(surface: Surface, width: Int, height: Int, rate: Float) {
        pendingSurface = surface
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        refreshRate = rate.takeIf { it > 0f } ?: 60f
        publishAndroidOrientation()
        if (active) publishSurface(created = !surfacePublished)
    }

    @Synchronized
    fun surfaceChanged(surface: Surface, width: Int, height: Int, rate: Float) {
        pendingSurface = surface
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        refreshRate = rate.takeIf { it > 0f } ?: 60f
        publishAndroidOrientation()
        if (active) publishSurface(created = !surfacePublished)
    }

    @Synchronized fun surfaceDestroyed() {
        if (active && surfacePublished) invokeQuietly(onNativeSurfaceDestroyed)
        surfacePublished = false
        pendingSurface = null
        if (prepared) invokeQuietly(setNativeSurface, null)
    }

    fun focusChanged(hasFocus: Boolean) { if (active) invokeQuietly(nativeFocusChanged, hasFocus) }
    fun keyboardFocusLost() { if (active) invokeQuietly(onNativeKeyboardFocusLost) }

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
        val next = if (pressed) mouseButtonState or androidButton else mouseButtonState and androidButton.inv()
        if (next == mouseButtonState) return
        mouseButtonState = next
        invokeQuietly(onNativeMouse, mouseButtonState,
            if (pressed) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, x, y, relative)
    }

    fun mouseMotion(x: Float, y: Float, relative: Boolean) {
        if (active) invokeQuietly(onNativeMouse, 0, MotionEvent.ACTION_MOVE, x, y, relative)
    }

    fun mouseScroll(x: Float, y: Float) {
        if (active) invokeQuietly(onNativeMouse, 0, MotionEvent.ACTION_SCROLL, x, y, false)
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

    @Synchronized private fun onSdlInitialized() {
        active = true
        publishAndroidOrientation()
        pendingSurface?.let { publishSurface(created = !surfacePublished) }
    }

    /** Mirrors SDL's Android getNaturalOrientation()/getCurrentRotation() logic. */
    private fun publishAndroidOrientation() {
        val activity = hostActivity ?: return
        val natural = runCatching {
            val config = activity.resources.configuration
            val rotation = activity.windowManager.defaultDisplay.rotation
            if (((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) &&
                    config.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
                ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) &&
                    config.orientation == Configuration.ORIENTATION_PORTRAIT)) {
                SDL_ORIENTATION_LANDSCAPE
            } else {
                SDL_ORIENTATION_PORTRAIT
            }
        }.getOrDefault(SDL_ORIENTATION_LANDSCAPE)
        val rotation = when (runCatching { activity.windowManager.defaultDisplay.rotation }.getOrDefault(0)) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // SDL's native Android implementation adjusts rotation by +90 when the
        // natural orientation is landscape. Let SDL perform that exact conversion;
        // do not rotate the SurfaceView or Vulkan buffers ourselves.
        invokeOptional(nativeSetNaturalOrientation, natural)
        invokeOptional(onNativeRotationChanged, rotation)
    }

    private fun publishSurface(created: Boolean) {
        val surface = pendingSurface ?: return
        invokeQuietly(setNativeSurface, surface)
        if (created) invokeQuietly(onNativeSurfaceCreated)
        invokeQuietly(nativeSetScreenResolution,
            surfaceWidth, surfaceHeight, surfaceWidth, surfaceHeight, 1f, refreshRate)
        invokeQuietly(onNativeResize)
        invokeQuietly(onNativeSurfaceChanged)
        surfacePublished = true
    }

    private fun invokeOptional(method: Method?, vararg args: Any?) {
        if (method != null) runCatching { method.invoke(null, *args) }
    }

    private fun invokeQuietly(method: Method, vararg arguments: Any?) {
        runCatching { method.invoke(null, *arguments) }
    }
}
