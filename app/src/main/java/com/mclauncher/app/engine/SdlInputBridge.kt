package com.mclauncher.app.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object SdlInputBridge {
    private const val SDL_CLASS = "git.mojo.sdl.SDL"
    private const val SDL_ACTIVITY_CLASS = "git.mojo.sdl.SDLActivity"
    private const val SDL_INPUT_CONNECTION_CLASS = "git.mojo.sdl.SDLInputConnection"
    private const val GRAB_LISTENER_CLASS = "git.mojo.sdl.GrabListener"
    private const val CLIPBOARD_CLASS = "git.mojo.sdl.SDLClipboard"
    private const val SDL_ORIENTATION_LANDSCAPE = 1
    private const val SDL_ORIENTATION_PORTRAIT = 3

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

    val isPrepared get() = prepared
    val isActive get() = active
    val isGrabbing get() = grabbing
    fun setGrabStateListener(listener: ((Boolean) -> Unit)?) { grabListener = listener }

    @Synchronized
    fun prepare(activity: Activity): Result<Unit> {
        if (prepared) {
            hostActivity = activity
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            publishAndroidOrientation()
            installEscapeFallback()
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
            nativeSetScreenResolution = sdlActivity.getMethod("nativeSetScreenResolution", intType, intType, intType, intType, floatType, floatType)
            onNativeResize = sdlActivity.getMethod("onNativeResize")
            onNativeKeyDown = sdlActivity.getMethod("onNativeKeyDown", intType)
            onNativeKeyUp = sdlActivity.getMethod("onNativeKeyUp", intType)
            onNativeKeyboardFocusLost = sdlActivity.getMethod("onNativeKeyboardFocusLost")
            onNativeMouse = sdlActivity.getMethod("onNativeMouse", intType, intType, floatType, floatType, booleanType)
            nativeFocusChanged = sdlActivity.getMethod("nativeFocusChanged", booleanType)
            nativeCommitText = inputConnection.getMethod("nativeCommitText", String::class.java, intType)
            nativeSetNaturalOrientation = runCatching { sdlActivity.getMethod("nativeSetNaturalOrientation", intType) }.getOrNull()
            onNativeRotationChanged = runCatching { sdlActivity.getMethod("onNativeRotationChanged", intType) }.getOrNull()

            setInitCallback.invoke(null, Runnable { onSdlInitialized() })
            grabListenerProxy = Proxy.newProxyInstance(loader, arrayOf(grabInterface)) { proxy, method, args ->
                when (method.name) {
                    "onGrabState" -> { grabbing = args?.firstOrNull() as? Boolean ?: false; grabListener?.invoke(grabbing); null }
                    "toString" -> "MCLauncherSDLGrabListener"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }.also { addGrabListener.invoke(null, it) }
            val clipboard = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
            clipboardProxy = Proxy.newProxyInstance(loader, arrayOf(clipboardInterface)) { proxy, method, args ->
                when (method.name) {
                    "getClipboardString" -> clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
                    "setClipboardString" -> { clipboard.setPrimaryClip(ClipData.newPlainText("Minecraft", args?.firstOrNull()?.toString().orEmpty())); null }
                    "toString" -> "MCLauncherSDLClipboard"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }.also { setClipboard.invoke(null, it) }
            sdl.getMethod("initialize").invoke(null)
            sdl.getMethod("setContext", Activity::class.java).invoke(null, activity)
            sdl.getMethod("setupJNI").invoke(null)
            publishAndroidOrientation()
            prepared = true
            installEscapeFallback()
        }
    }

    @Synchronized fun surfaceCreated(surface: Surface, width: Int, height: Int, rate: Float) {
        pendingSurface = surface
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        refreshRate = rate.takeIf { it > 0f } ?: 60f
        publishAndroidOrientation()
        if (active) publishSurface(!surfacePublished)
    }

    @Synchronized fun surfaceChanged(surface: Surface, width: Int, height: Int, rate: Float) {
        pendingSurface = surface
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        refreshRate = rate.takeIf { it > 0f } ?: 60f
        publishAndroidOrientation()
        if (active) publishSurface(!surfacePublished)
    }

    @Synchronized fun surfaceDestroyed() {
        if (active && surfacePublished) invoke(onNativeSurfaceDestroyed)
        surfacePublished = false
        pendingSurface = null
        if (prepared) invoke(setNativeSurface, null)
    }

    fun focusChanged(hasFocus: Boolean) { if (active) invoke(nativeFocusChanged, hasFocus) }
    fun keyboardFocusLost() { if (active) invoke(onNativeKeyboardFocusLost) }
    fun key(androidKeyCode: Int, pressed: Boolean) {
        if (!active || androidKeyCode == KeyEvent.KEYCODE_UNKNOWN) return
        invoke(if (pressed) onNativeKeyDown else onNativeKeyUp, androidKeyCode)
    }
    fun commitCodePoint(codePoint: Int) {
        if (active && Character.isValidCodePoint(codePoint)) invoke(nativeCommitText, String(Character.toChars(codePoint)), 0)
    }
    @Synchronized fun mouseButton(button: Int, pressed: Boolean, x: Float, y: Float, relative: Boolean) {
        if (!active) return
        val next = if (pressed) mouseButtonState or button else mouseButtonState and button.inv()
        if (next == mouseButtonState) return
        mouseButtonState = next
        invoke(onNativeMouse, mouseButtonState, if (pressed) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, x, y, relative)
    }
    fun mouseMotion(x: Float, y: Float, relative: Boolean) { if (active) invoke(onNativeMouse, 0, MotionEvent.ACTION_MOVE, x, y, relative) }
    fun mouseScroll(x: Float, y: Float) { if (active) invoke(onNativeMouse, 0, MotionEvent.ACTION_SCROLL, x, y, false) }

    @Synchronized fun shutdown() {
        keyboardFocusLost(); focusChanged(false); surfaceDestroyed()
        active = false; grabbing = false; mouseButtonState = 0; grabListener = null; hostActivity = null
    }

    @Synchronized private fun onSdlInitialized() {
        active = true
        publishAndroidOrientation()
        installEscapeFallback()
        pendingSurface?.let { publishSurface(!surfacePublished) }
    }

    private fun publishAndroidOrientation() {
        val activity = hostActivity ?: return
        val natural = runCatching {
            val config = activity.resources.configuration
            val rotation = activity.windowManager.defaultDisplay.rotation
            if (((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) && config.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
                ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) && config.orientation == Configuration.ORIENTATION_PORTRAIT)) SDL_ORIENTATION_LANDSCAPE else SDL_ORIENTATION_PORTRAIT
        }.getOrDefault(SDL_ORIENTATION_LANDSCAPE)
        val rotation = when (runCatching { activity.windowManager.defaultDisplay.rotation }.getOrDefault(0)) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        runCatching { nativeSetNaturalOrientation?.invoke(null, natural) }
        runCatching { onNativeRotationChanged?.invoke(null, rotation) }
    }

    private fun installEscapeFallback() {
        val activity = hostActivity ?: return
        activity.runOnUiThread {
            val listener = View.OnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_ESCAPE) return@OnKeyListener false
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> { if (active) invoke(onNativeKeyDown, KeyEvent.KEYCODE_ESCAPE); true }
                    KeyEvent.ACTION_UP -> { if (active) invoke(onNativeKeyUp, KeyEvent.KEYCODE_ESCAPE); true }
                    else -> true
                }
            }
            findSurfaceView(activity.window.decorView)?.setOnKeyListener(listener)
            activity.window.decorView.setOnKeyListener(listener)
        }
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) findSurfaceView(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun publishSurface(created: Boolean) {
        val surface = pendingSurface ?: return
        invoke(setNativeSurface, surface)
        if (created) invoke(onNativeSurfaceCreated)
        invoke(nativeSetScreenResolution, surfaceWidth, surfaceHeight, surfaceWidth, surfaceHeight, 1f, refreshRate)
        invoke(onNativeResize)
        invoke(onNativeSurfaceChanged)
        surfacePublished = true
    }

    private fun invoke(method: Method, vararg args: Any?) { runCatching { method.invoke(null, *args) } }
}
