package com.sternpaul.streamguide.core
import org.junit.Assert.*
import org.junit.Test
class M3uParserTest {
 @Test fun parsesAttributesGroupsAndUrl() {
  val input = """#EXTM3U
#EXTINF:-1 tvg-id="bbc1" tvg-logo="https://logo/1.png" group-title="News",BBC One
https://stream/live.m3u8"""
  val channels = M3uParser.parse(input)
  assertEquals(1, channels.size); assertEquals("bbc1", channels[0].tvgId); assertEquals("News", channels[0].group); assertEquals("BBC One", channels[0].name); assertEquals("https://stream/live.m3u8", channels[0].url)
 }
 @Test fun ignoresMalformedEntries() { assertTrue(M3uParser.parse("""#EXTM3U
#EXTINF:-1,No URL""").isEmpty()) }
}
