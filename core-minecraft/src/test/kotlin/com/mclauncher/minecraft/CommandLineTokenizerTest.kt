package com.mclauncher.minecraft

import kotlin.test.Test
import kotlin.test.assertEquals

class CommandLineTokenizerTest {
    @Test
    fun `splits normal arguments`() {
        assertEquals(listOf("-Xmx2G", "-Dkey=value"), CommandLineTokenizer.tokenize("-Xmx2G -Dkey=value"))
    }

    @Test
    fun `keeps quoted values together`() {
        assertEquals(
            listOf("-Dpath=/some folder/file", "hello world"),
            CommandLineTokenizer.tokenize("\"-Dpath=/some folder/file\" 'hello world'")
        )
    }
}
