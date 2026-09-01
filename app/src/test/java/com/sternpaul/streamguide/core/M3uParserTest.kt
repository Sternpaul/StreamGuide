package com.sternpaul.streamguide.core
import org.junit.Assert.*
import org.junit.Test
import java.io.File

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

 @Test fun streamsPlaylistLargerThanFiftyMegabytesAndKeepsLiveChannels() {
  val playlist = File.createTempFile("large-playlist", ".m3u")
  try {
   playlist.bufferedWriter().use { writer ->
    writer.appendLine("#EXTM3U")
    val padding = "x".repeat(900)
    repeat(58_000) { index ->
     writer.appendLine("#EXTINF:-1 group-title=\"VOD\",Movie $index $padding")
     writer.appendLine("https://provider.example/movie/u/p/$index.mp4")
    }
    writer.appendLine("#EXTINF:-1 tvg-id=\"live-1\" group-title=\"News\",Live News")
    writer.appendLine("https://provider.example/live/u/p/1.ts")
   }
   assertTrue(playlist.length() > 50L * 1024 * 1024)

   val channels = playlist.inputStream().use(M3uParser::parse)

   assertEquals(1, channels.size)
   assertEquals("Live News", channels.single().name)
  } finally {
   playlist.delete()
  }
 }
}
