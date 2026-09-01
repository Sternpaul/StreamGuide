package com.sternpaul.streamguide.data

import java.io.File

/** Atomic JSON-lines persistence that never materializes a whole JSON document. */
object JsonLinesStore {
    fun <T> write(file: File, values: Sequence<T>, encode: (T) -> String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.bufferedWriter().use { writer ->
            values.forEach { value ->
                writer.appendLine(encode(value))
            }
        }
        if (!temp.renameTo(file)) {
            temp.inputStream().use { input -> file.outputStream().use(input::copyTo) }
            temp.delete()
        }
    }

    fun <T : Any> read(file: File, decode: (String) -> T?): List<T> {
        if (!file.exists()) return emptyList()
        return file.bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).mapNotNull(decode).toList()
        }
    }
}
