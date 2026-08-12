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
    private val calendarVersion = Regex(
        """(?i)(?<!\d)(\d{1,4})\.(\d+)(?:[-_.]?snapshot[-_.]?(\d+))?(?!\d)"""
    )

    fun supportsGraphicsApi(versionId: String): Boolean =
        versionPair.findAll(versionId).any { match ->
            val major = match.groupValues[1].toIntOrNull() ?: return@any false
            val minor = match.groupValues[2].toIntOrNull() ?: return@any false
            major > 26 || major == 26 && minor >= 2
        }

    /**
     * Minecraft 26.3 Snapshot 6 introduced the strict SDL3 backend checks used
     * by later snapshots and releases. Keep its Android compatibility path
     * separate from the GLFW path used by already-working older versions.
     */
    fun supportsSnapshotSdlGraphicsCompatibility(versionId: String): Boolean =
        calendarVersion.findAll(versionId).any { match ->
            val major = match.groupValues[1].toIntOrNull() ?: return@any false
            val minor = match.groupValues[2].toIntOrNull() ?: return@any false
            when {
                major > 26 -> true
                major < 26 -> false
                minor > 3 -> true
                minor < 3 -> false
                match.groupValues[3].isBlank() -> true
                else -> (match.groupValues[3].toIntOrNull() ?: 0) >= 6
            }
        }
}
