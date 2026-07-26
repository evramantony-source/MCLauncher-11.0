package com.mclauncher.app

import android.app.Application
import com.mclauncher.app.data.LauncherStore
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
    }

    companion object {
        @JvmStatic
        lateinit var instance: MCLauncherApplication
            private set
    }
}
