package com.mclauncher.app.ui.game

import kotlin.math.floor

/** Minecraft HUD geometry used to place a transparent touch target over the real hotbar. */
internal object HotbarTouchLayout {
    const val SLOT_COUNT = 9
    private const val LOGICAL_WIDTH = 182
    private const val LOGICAL_HEIGHT = 22
    private const val MIN_SCALED_WIDTH = 320
    private const val MIN_SCALED_HEIGHT = 240

    fun guiScale(surfaceWidth: Int, surfaceHeight: Int, requestedScale: Int = 0): Int {
        val maximum = if (requestedScale <= 0) Int.MAX_VALUE else requestedScale
        var scale = 1
        while (
            scale < maximum &&
            surfaceWidth / (scale + 1) >= MIN_SCALED_WIDTH &&
            surfaceHeight / (scale + 1) >= MIN_SCALED_HEIGHT
        ) {
            scale += 1
        }
        return scale
    }

    fun widthPixels(surfaceWidth: Int, surfaceHeight: Int, requestedScale: Int = 0): Int =
        LOGICAL_WIDTH * guiScale(surfaceWidth, surfaceHeight, requestedScale)

    fun heightPixels(surfaceWidth: Int, surfaceHeight: Int, requestedScale: Int = 0): Int =
        LOGICAL_HEIGHT * guiScale(surfaceWidth, surfaceHeight, requestedScale)

    fun slotAt(localX: Float, regionWidth: Float): Int? {
        if (regionWidth <= 0f || localX < 0f || localX > regionWidth) return null
        val slot = floor(localX / regionWidth * SLOT_COUNT).toInt()
        return slot.coerceIn(0, SLOT_COUNT - 1)
    }
}
