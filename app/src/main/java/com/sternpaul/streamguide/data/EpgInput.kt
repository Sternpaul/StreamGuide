package com.sternpaul.streamguide.data

import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

object EpgInput {
    fun open(input: InputStream, url: String, contentEncoding: String): InputStream {
        val buffered = PushbackInputStream(input.buffered(), 2)
        val first = buffered.read()
        val second = buffered.read()
        if (second >= 0) buffered.unread(second)
        if (first >= 0) buffered.unread(first)
        val gzip = url.endsWith(".gz", ignoreCase = true) ||
            contentEncoding.contains("gzip", ignoreCase = true) ||
            first == 0x1f && second == 0x8b
        return if (gzip) GZIPInputStream(buffered) else buffered
    }
}
