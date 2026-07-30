package com.mclauncher.model

import kotlinx.serialization.Serializable

@Serializable
enum class JavaVersion(val major: Int) {
    JAVA_8(8),
    JAVA_17(17),
    JAVA_21(21),
    JAVA_25(25)
}

@Serializable
enum class ModLoader(val id: String, val displayName: String) {
    VANILLA("vanilla", "Vanilla"),
    FABRIC("fabric", "Fabric"),
    QUILT("quilt", "Quilt"),
    FORGE("forge", "Forge"),
    NEOFORGE("neoforge", "NeoForge")
}


@Serializable
enum class ContentSource(val displayName: String) {
    MODRINTH("Modrinth"),
    CURSEFORGE("CurseForge")
}

@Serializable
enum class ContentType(val folderName: String, val displayName: String) {
    MOD("mods", "Mods"),
    MODPACK("modpacks", "Modpacks"),
    RESOURCE_PACK("resourcepacks", "Resource packs"),
    SHADER("shaderpacks", "Shaders")
}

@Serializable
enum class PerformancePreset(val displayName: String) {
    BATTERY("Battery saver"),
    BALANCED("Balanced"),
    PERFORMANCE("Performance"),
    CUSTOM("Custom")
}

@Serializable
enum class LauncherThemeMode(val displayName: String) {
    SYSTEM("Use device theme"),
    DARK("Dark"),
    LIGHT("Light")
}

@Serializable
enum class TouchLookMode(val displayName: String) {
    SWIPE("Swipe anywhere"),
    JOYSTICK("Look joystick")
}

/**
 * Minecraft's own renderer-backend preference, introduced in Java Edition 26.2.
 *
 * This is deliberately separate from [Renderer]: OpenLTW, MobileGlues and GL4ES
 * translate Minecraft's OpenGL path, while this value tells Minecraft whether it
 * should start that OpenGL path or its native Vulkan path.
 */
@Serializable
enum class MinecraftGraphicsApi(
    val optionsValue: String,
    val displayName: String,
    val description: String
) {
    DEFAULT(
        "default",
        "Default",
        "Use Minecraft's current default and fallback behavior"
    ),
    OPENGL(
        "opengl",
        "Prefer OpenGL",
        "Use the selected Android OpenGL renderer without probing Vulkan after a startup failure"
    ),
    VULKAN(
        "vulkan",
        "Prefer Vulkan (experimental)",
        "Use Minecraft 26.2's experimental native Vulkan backend"
    )
}

/**
 * Desktop-OpenGL/Vulkan compatibility backends understood by the launcher.
 * Alpha 02 embeds a verified default backend in the APK and keeps manifest-driven
 * overrides for advanced testing because each backend has separate ABI/GPU rules.
 */
@Serializable
enum class Renderer(
    val id: String,
    val displayName: String,
    val description: String,
    val requiresVulkan: Boolean = false
) {
    AUTO("auto", "Automatic", "Choose the first compatible installed backend"),
    MOBILE_GLUES("mobileglues", "MobileGlues", "Desktop OpenGL compatibility on top of OpenGL ES"),
    ANGLE("angle", "ANGLE renderer", "Direct desktop GL route through ANGLE", requiresVulkan = true),
    OPEN_LTW("openltw", "OpenLTW / LTW", "Lightweight desktop OpenGL translation backend"),
    NG_GL4ES("ng-gl4es", "NG-GL4ES", "Modern GL4ES-derived compatibility backend"),
    GL4ES("gl4es", "GL4ES", "Classic desktop OpenGL to OpenGL ES compatibility layer"),
    ZINK("zink", "Zink", "Mesa OpenGL implementation running over Vulkan", requiresVulkan = true),
    VIRGL("virgl", "VirGL", "Mesa VirGL desktop OpenGL backend"),
    VULKAN("vulkan", "Native Vulkan", "Minecraft's Vulkan path when provided", requiresVulkan = true),
    KRYPTON("krypton", "Krypton Wrapper", "Plugin renderer wrapper used by Android launchers"),
    CUSTOM("custom", "Custom renderer plugin", "Manifest-driven third-party renderer")
}

@Serializable
enum class GraphicsDriver(
    val id: String,
    val displayName: String,
    val description: String,
    val requiresVulkan: Boolean = false
) {
    AUTO("auto", "Automatic", "Use the renderer package recommendation"),
    SYSTEM("system", "System driver", "Use Android's built-in GLES/Vulkan driver"),
    ANGLE("angle", "ANGLE", "Translate OpenGL ES through ANGLE", requiresVulkan = true),
    TURNIP("turnip", "Turnip", "Mesa Vulkan driver for supported Qualcomm Adreno GPUs", requiresVulkan = true),
    PANVK("panvk", "PanVK", "Mesa Vulkan driver for supported Mali GPUs", requiresVulkan = true),
    SWIFTSHADER("swiftshader", "SwiftShader", "Software Vulkan/GLES fallback", requiresVulkan = true)
}

@Serializable
data class ControlElement(
    val id: String,
    val label: String,
    val keyCode: Int? = null,
    val mouseButton: Int? = null,
    val x: Float,
    val y: Float,
    val width: Float = 0.12f,
    val height: Float = 0.09f,
    val opacity: Float = 0.72f,
    val visible: Boolean = true,
    val toggle: Boolean = false
)

@Serializable
data class ControllerBinding(
    val androidKeyCode: Int,
    val glfwKeyCode: Int? = null,
    val mouseButton: Int? = null,
    val label: String
)

object DefaultControls {
    fun layout(): List<ControlElement> = listOf(
        // Hidden legacy movement buttons remain editable for players who prefer a D-pad.
        ControlElement("move_w", "W", keyCode = 87, x = 0.12f, y = 0.66f, visible = false),
        ControlElement("move_a", "A", keyCode = 65, x = 0.04f, y = 0.76f, visible = false),
        ControlElement("move_s", "S", keyCode = 83, x = 0.12f, y = 0.76f, visible = false),
        ControlElement("move_d", "D", keyCode = 68, x = 0.20f, y = 0.76f, visible = false),
        ControlElement("sneak", "Sneak", keyCode = 340, x = 0.03f, y = 0.52f, width = 0.13f),
        ControlElement("sprint", "Sprint", keyCode = 341, x = 0.18f, y = 0.52f, width = 0.13f),
        ControlElement("jump", "Jump", keyCode = 32, x = 0.82f, y = 0.66f, width = 0.15f),
        ControlElement("attack", "Hit", mouseButton = 0, x = 0.82f, y = 0.77f, width = 0.15f),
        ControlElement("use", "Use", mouseButton = 1, x = 0.66f, y = 0.77f, width = 0.15f),
        ControlElement("inventory", "Inv", keyCode = 69, x = 0.68f, y = 0.08f, width = 0.1f, height = 0.07f),
        ControlElement("chat", "Chat", keyCode = 84, x = 0.80f, y = 0.08f, width = 0.1f, height = 0.07f),
        ControlElement("escape", "Esc", keyCode = 256, x = 0.02f, y = 0.04f, width = 0.1f, height = 0.07f),
        ControlElement("drop", "Drop", keyCode = 81, x = 0.56f, y = 0.08f, width = 0.1f, height = 0.07f, visible = false),
        ControlElement("swap_hands", "Swap", keyCode = 70, x = 0.56f, y = 0.17f, width = 0.1f, height = 0.07f, visible = false),
        ControlElement("perspective", "F5", keyCode = 294, x = 0.44f, y = 0.08f, width = 0.08f, height = 0.07f, visible = false),
        ControlElement("pick_block", "Pick", mouseButton = 2, x = 0.70f, y = 0.58f, width = 0.1f, height = 0.07f, visible = false)
    )

    fun controllerBindings(): List<ControllerBinding> = listOf(
        ControllerBinding(96, glfwKeyCode = 32, label = "A → Jump"),
        ControllerBinding(97, glfwKeyCode = 340, label = "B → Sneak"),
        ControllerBinding(99, mouseButton = 0, label = "X → Attack"),
        ControllerBinding(100, mouseButton = 1, label = "Y → Use"),
        ControllerBinding(102, glfwKeyCode = 69, label = "L1 → Inventory"),
        ControllerBinding(103, glfwKeyCode = 81, label = "R1 → Drop"),
        ControllerBinding(108, glfwKeyCode = 256, label = "Start → Escape")
    )
}

@Serializable
data class LauncherSettings(
    val selectedJava: JavaVersion = JavaVersion.JAVA_21,
    val renderer: Renderer = Renderer.AUTO,
    val graphicsDriver: GraphicsDriver = GraphicsDriver.AUTO,
    val minecraftGraphicsApi: MinecraftGraphicsApi = MinecraftGraphicsApi.DEFAULT,
    val themeMode: LauncherThemeMode = LauncherThemeMode.SYSTEM,
    val memoryMb: Int = 2048,
    val width: Int = 1280,
    val height: Int = 720,
    val showSnapshots: Boolean = false,
    val keepLauncherOpen: Boolean = true,
    val customJvmArgs: String = "",
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val fpsLimit: Int = 60,
    val resolutionScale: Float = 1f,
    val autoInstallDependencies: Boolean = true,
    val autoUpdateContent: Boolean = false,
    val microsoftClientId: String = "",
    val runtimeCatalogUrl: String = "",
    val componentCatalogUrl: String = "",
    val curseForgeApiKey: String = "",
    val controlScale: Float = 1f,
    val controlOpacity: Float = 0.72f,
    val lookSensitivity: Float = 1f,
    val gyroEnabled: Boolean = false,
    val movementJoystickEnabled: Boolean = true,
    val touchLookMode: TouchLookMode = TouchLookMode.SWIPE,
    val virtualMouseEnabled: Boolean = true,
    // Kept for settings written by Alpha 01. touchLookMode is authoritative in Alpha 02.
    val lookJoystickEnabled: Boolean = true,
    val joystickSize: Float = 0.22f,
    val joystickDeadZone: Float = 0.18f,
    val gamepadDeadZone: Float = 0.18f,
    val physicalMouseCapture: Boolean = true,
    val hideTouchControlsWithExternalInput: Boolean = false,
    val sustainedPerformanceMode: Boolean = false,
    val controlLayout: List<ControlElement> = DefaultControls.layout(),
    val controllerBindings: List<ControllerBinding> = DefaultControls.controllerBindings()
)

/**
 * Optional launch-only overrides stored with one Minecraft instance.
 *
 * Existing instances and newly created instances inherit global launcher settings
 * until the user enables this block. Nullable values keep state migrations safe and
 * allow newly added global settings to remain authoritative by default.
 */
@Serializable
data class InstanceLaunchSettings(
    val enabled: Boolean = false,
    val renderer: Renderer? = null,
    val graphicsDriver: GraphicsDriver? = null,
    val minecraftGraphicsApi: MinecraftGraphicsApi? = null,
    val memoryMb: Int? = null,
    val fpsLimit: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val resolutionScale: Float? = null,
    val customJvmArgs: String? = null,
    val performancePreset: PerformancePreset? = null
) {
    fun applyTo(global: LauncherSettings): LauncherSettings {
        if (!enabled) return global
        return global.copy(
            renderer = renderer ?: global.renderer,
            graphicsDriver = graphicsDriver ?: global.graphicsDriver,
            minecraftGraphicsApi = minecraftGraphicsApi ?: global.minecraftGraphicsApi,
            memoryMb = (memoryMb ?: global.memoryMb).coerceIn(768, 6144),
            fpsLimit = (fpsLimit ?: global.fpsLimit).coerceIn(20, 260),
            width = (width ?: global.width).coerceIn(640, 2560),
            height = (height ?: global.height).coerceIn(360, 1600),
            resolutionScale = (resolutionScale ?: global.resolutionScale).coerceIn(0.50f, 1.00f),
            customJvmArgs = customJvmArgs ?: global.customJvmArgs,
            performancePreset = performancePreset ?: global.performancePreset
        )
    }

    companion object {
        fun fromGlobal(global: LauncherSettings): InstanceLaunchSettings = InstanceLaunchSettings(
            enabled = true,
            renderer = global.renderer,
            graphicsDriver = global.graphicsDriver,
            minecraftGraphicsApi = global.minecraftGraphicsApi,
            memoryMb = global.memoryMb,
            fpsLimit = global.fpsLimit,
            width = global.width,
            height = global.height,
            resolutionScale = global.resolutionScale,
            customJvmArgs = global.customJvmArgs,
            performancePreset = global.performancePreset
        )
    }
}

@Serializable
data class MinecraftInstance(
    val id: String,
    val name: String,
    val versionId: String,
    val gameDirectoryName: String,
    val javaVersion: JavaVersion,
    val loader: ModLoader = ModLoader.VANILLA,
    val loaderVersion: String? = null,
    val createdAtEpochMs: Long,
    val lastPlayedAtEpochMs: Long? = null,
    val installed: Boolean = false,
    val favorite: Boolean = false,
    val iconPath: String? = null,
    val launchSettings: InstanceLaunchSettings = InstanceLaunchSettings()
)

@Serializable
data class LauncherSnapshot(
    val accounts: List<LauncherAccount> = emptyList(),
    val selectedAccountId: String? = null,
    val instances: List<MinecraftInstance> = emptyList(),
    val settings: LauncherSettings = LauncherSettings()
)
