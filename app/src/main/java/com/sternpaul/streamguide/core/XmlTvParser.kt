package com.sternpaul.streamguide.core

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

object XmlTvParser {
    fun parse(input: InputStream): List<Program> = buildList { parse(input) { add(it) } }

    fun parse(input: InputStream, onProgram: (Program) -> Unit): Int {
        var count = 0
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
            try { setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) { }
            try { setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) { }
        }
        factory.newSAXParser().parse(input, object : DefaultHandler() {
            var channel = ""; var start = 0L; var stop = 0L
            var title = ""; var description = ""; var activeTag = ""; val buffer = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                activeTag = qName.lowercase(); buffer.setLength(0)
                if (activeTag == "programme") {
                    channel = attributes.getValue("channel").orEmpty()
                    start = parseDate(attributes.getValue("start"))
                    stop = parseDate(attributes.getValue("stop"))
                    title = ""; description = ""
                }
            }
            override fun characters(ch: CharArray, startIndex: Int, length: Int) { buffer.append(ch, startIndex, length) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName.lowercase()) {
                    "title" -> title = buffer.toString().trim()
                    "desc" -> description = buffer.toString().trim()
                    "programme" -> if (channel.isNotBlank() && title.isNotBlank() && start > 0 && stop > start) {
                        onProgram(Program(channel, title, description, start, stop))
                        count++
                    }
                }
                activeTag = ""; buffer.setLength(0)
            }
        })
        return count
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        val patterns = if (normalized.contains(' ')) listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmm Z") else listOf("yyyyMMddHHmmss", "yyyyMMddHHmm")
        return patterns.firstNotNullOfOrNull { pattern -> runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(normalized)?.time }.getOrNull() } ?: 0
    }
}
