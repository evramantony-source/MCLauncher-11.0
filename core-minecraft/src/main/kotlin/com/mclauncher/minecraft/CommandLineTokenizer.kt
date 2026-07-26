package com.mclauncher.minecraft

object CommandLineTokenizer {
    fun tokenize(command: String): List<String> {
        val output = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                output += current.toString()
                current.setLength(0)
            }
        }

        for (char in command) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> flush()
                else -> current.append(char)
            }
        }
        if (escaped) current.append('\\')
        require(quote == null) { "Unclosed quote in command line" }
        flush()
        return output
    }
}
