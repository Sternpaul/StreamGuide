package com.sternpaul.streamguide.core
import org.junit.Assert.*
import org.junit.Test
class XmlTvParserTest {
 @Test fun parsesProgrammeAndTimezone() {
  val xml="""<tv><programme start="20260830200000 +0000" stop="20260830210000 +0000" channel="bbc1"><title>News</title><desc>Headlines</desc></programme></tv>"""
  val p=XmlTvParser.parse(xml.byteInputStream()).single()
  assertEquals("bbc1",p.channelId); assertEquals("News",p.title); assertEquals(3600000,p.endEpochMs-p.startEpochMs)
 }
 @Test fun skipsBrokenProgramme() { assertTrue(XmlTvParser.parse("<tv><programme></programme></tv>".byteInputStream()).isEmpty()) }
}
