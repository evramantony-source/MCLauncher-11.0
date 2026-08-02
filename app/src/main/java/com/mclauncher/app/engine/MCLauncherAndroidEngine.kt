package com.mclauncher.app.engine

import com.mclauncher.minecraft.LaunchPlanBuilder
import com.mclauncher.model.AuthSession
import com.mclauncher.model.LauncherAccount
import com.mclauncher.model.LauncherSettings
import com.mclauncher.model.MinecraftInstance
import java.io.File

/**
 * Stable boundary between launcher UI/instance management and the Android game
 * engine. A different frontend can call this contract without inheriting the
 * desktop process launcher used by Modrinth App.
 */
interface AndroidLauncherEngine {
    val id: String

    suspend fun prepareLaunch(
        instance: MinecraftInstance,
        account: LauncherAccount,
        settings: LauncherSettings,
        authSession: AuthSession?,
        architecture: String
    ): File
}

/**
 * The proven MCLauncher pipeline: resolves Mojang metadata, substitutes the
 * Android LWJGL stack and writes the plan consumed by NativeEngineCoordinator.
 */
class MCLauncherAndroidEngine(
    private val launchPlanBuilder: LaunchPlanBuilder
) : AndroidLauncherEngine {
    override val id: String = "mclauncher-android-native-v1"

    override suspend fun prepareLaunch(
        instance: MinecraftInstance,
        account: LauncherAccount,
        settings: LauncherSettings,
        authSession: AuthSession?,
        architecture: String
    ): File {
        val plan = launchPlanBuilder.build(
            instance = instance,
            account = account,
            settings = settings,
            authSession = authSession,
            architecture = architecture
        )
        return launchPlanBuilder.save(plan)
    }
}
