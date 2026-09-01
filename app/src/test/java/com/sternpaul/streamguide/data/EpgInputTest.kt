package com.sternpaul.streamguide.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class EpgInputTest {
    @Test
    fun detectsGzipFromMagicBytesWithoutBufferingTheWholeDownload() {
        val compressed = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write("<tv/>".toByteArray()) }
        }.toByteArray()

        val text = EpgInput.open(ByteArrayInputStream(compressed), "https://guide/epg", "")
            .bufferedReader().use { it.readText() }

        assertEquals("<tv/>", text)
    }
}
