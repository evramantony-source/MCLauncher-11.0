package com.mclauncher.minecraft

/**
 * Feature gates for Minecraft's post-1.21 calendar-style version numbers.
 *
 * Loader-generated IDs usually prefix the base game version (for example,
 * fabric-loader-0.19.3-26.2), so every numeric major/minor pair is inspected
 * instead of assuming the whole ID is a plain release number.
 */
object MinecraftVersionCapabilities {
    private val versionPair = Regex("""(?<!\d)(\d{1,4})\.(\d+)(?!\d)""")

    fun supportsGraphicsApi(versionId: String): Boolean =
        versionPair.findAll(versionId).any { match ->
            val major = match.groupValues[1].toIntOrNull() ?: return@any false
            val minor = match.groupValues[2].toIntOrNull() ?: return@any false
            major > 26 || major == 26 && minor >= 2
        }
}
