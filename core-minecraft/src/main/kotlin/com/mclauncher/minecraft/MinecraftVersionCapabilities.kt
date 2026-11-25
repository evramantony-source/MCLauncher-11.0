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

    /**
     * Minecraft 26.3 Snapshot 4 is the first SDL3 client and the last one that
     * accepts SDL's own OpenGL entry points. It is the newest build confirmed to
     * start on a device without MCLauncher's SDL/OpenGL compatibility handling.
     */
    private const val LAST_PERMISSIVE_SDL_MAJOR = 26
    private const val LAST_PERMISSIVE_SDL_MINOR = 3
    private const val LAST_PERMISSIVE_SDL_SNAPSHOT = 4

    fun supportsGraphicsApi(versionId: String): Boolean =
        versionPair.findAll(versionId).any { match ->
            val major = match.groupValues[1].toIntOrNull() ?: return@any false
            val minor = match.groupValues[2].toIntOrNull() ?: return@any false
            major > 26 || major == 26 && minor >= 2
        }

    /**
     * Whether an SDL3 Minecraft build needs MCLauncher's Android SDL/OpenGL
     * compatibility handling: the `glGetError` function-pointer identity bridge
     * and, on affected Qualcomm/Adreno devices, automatic bundled-Turnip
     * selection for the Vulkan backend.
     *
     * Minecraft 26.3 Snapshot 5 is the first client that compares the pointer
     * returned by `SDL_GL_GetProcAddress("glGetError")` with the one LWJGL's own
     * OpenGL provider returns and refuses to start when the two differ, so every
     * SDL build newer than Snapshot 4 needs the bridge. The gate is written as
     * "newer than the last permissive SDL build" rather than "at least Snapshot
     * N" so a later snapshot, pre-release or release can never silently lose it.
     *
     * Call sites must still confirm that the launch actually resolved the SDL
     * window backend; versions older than Snapshot 4 use the GLFW path and are
     * excluded here as well.
     */
    fun supportsSnapshotSdlGraphicsCompatibility(versionId: String): Boolean =
        newerThanLastPermissiveSdlBuild(versionId)

    private fun newerThanLastPermissiveSdlBuild(versionId: String): Boolean {
        val newest = calendarVersion.findAll(versionId)
            .mapNotNull { match ->
                val major = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val minor = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                // A release is newer than every snapshot of the same version.
                val snapshot = match.groupValues[3].toIntOrNull() ?: Int.MAX_VALUE
                Triple(major, minor, snapshot)
            }
            .maxWithOrNull(
                compareBy<Triple<Int, Int, Int>>({ it.first }, { it.second }, { it.third })
            )
            ?: return false
        val (major, minor, snapshot) = newest
        return when {
            major != LAST_PERMISSIVE_SDL_MAJOR -> major > LAST_PERMISSIVE_SDL_MAJOR
            minor != LAST_PERMISSIVE_SDL_MINOR -> minor > LAST_PERMISSIVE_SDL_MINOR
            else -> snapshot > LAST_PERMISSIVE_SDL_SNAPSHOT
        }
    }
}
