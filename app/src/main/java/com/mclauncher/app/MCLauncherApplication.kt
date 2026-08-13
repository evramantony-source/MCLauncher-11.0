package com.mclauncher.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceView
import com.mclauncher.app.data.LauncherStore
import com.mclauncher.app.engine.GameInputBridge
import com.mclauncher.minecraft.MinecraftLayout

class MCLauncherApplication : Application() {
    lateinit var launcherStore: LauncherStore
        private set

    lateinit var minecraftLayout: MinecraftLayout
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        launcherStore = LauncherStore(this)
        minecraftLayout = MinecraftLayout(filesDir.resolve("minecraft"))
        minecraftLayout.ensureBaseDirectories()
        registerActivityLifecycleCallbacks(physicalKeyboardFallback)
    }

    private val physicalKeyboardFallback = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity !is GameActivity) return
            activity.window.decorView.post {
                val surfaceView = findSurfaceView(activity.window.decorView)
                surfaceView?.setOnKeyListener { _, keyCode, event ->
                    // GameActivity normally handles this through dispatchKeyEvent().
                    // This fallback covers Android devices that route a physical
                    // keyboard directly to the focused SurfaceView instead.
                    if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        GameInputBridge.handleAndroidKey(event)
                    } else {
                        false
                    }
                }
            }
        }

        override fun onActivityDestroyed(activity: Activity) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private fun findSurfaceView(view: android.view.View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    companion object {
        @JvmStatic
        lateinit var instance: MCLauncherApplication
            private set
    }
}
