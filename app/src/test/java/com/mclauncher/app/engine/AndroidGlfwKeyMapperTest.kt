package com.mclauncher.app.engine

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidGlfwKeyMapperTest {
    @Test
    fun reversesTouchControlKeysForSdlAndroidInput() {
        assertEquals(KeyEvent.KEYCODE_W, AndroidGlfwKeyMapper.toAndroid(87))
        assertEquals(KeyEvent.KEYCODE_1, AndroidGlfwKeyMapper.toAndroid(49))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, AndroidGlfwKeyMapper.toAndroid(256))
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, AndroidGlfwKeyMapper.toAndroid(340))
    }
}
