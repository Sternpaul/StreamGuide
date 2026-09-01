package com.sternpaul.streamguide.core
import org.junit.Assert.*
import org.junit.Test
class OrderingTest {
 @Test fun manualRanksSurviveProviderReorderAndNewChannelsAppend() {
  val old = listOf(Channel("a","A","u1","G",providerOrder=0,manualRank=2000), Channel("b","B","u2","G",providerOrder=1,manualRank=1000))
  val refreshed = listOf(Channel("b","B","u2","G",providerOrder=0), Channel("c","C","u3","G",providerOrder=1), Channel("a","A","u1","G",providerOrder=2))
  val result = ChannelReconciler.reconcile(old, refreshed)
  assertEquals(listOf("b","a","c"), result.sortedWith(ChannelOrdering.manual).map { it.id })
 }
 @Test fun removedChannelDoesNotCorruptRanks() { val result=ChannelReconciler.reconcile(listOf(Channel("a","A","u","G",manualRank=10)), emptyList()); assertTrue(result.isEmpty()) }
 @Test fun initialImportDoesNotDuplicateEveryChannelObject() {
  val incoming = listOf(Channel("a", "A", "u1"), Channel("b", "B", "u2"))
  val result = ChannelReconciler.reconcile(emptyList(), incoming)
  assertSame(incoming, result)
 }
 @Test fun movesChannelToAnAbsoluteOneBasedPosition() {
  val channels = listOf("a", "b", "c", "d").mapIndexed { index, id -> Channel(id, id.uppercase(), "u", manualRank = (index + 1) * 1000L) }
  val result = ChannelReconciler.moveTo(channels, "d", 2)
  assertEquals(listOf("a", "d", "b", "c"), result.sortedWith(ChannelOrdering.manual).map { it.id })
 }
 @Test fun customNameAndGroupSurviveProviderRefresh() {
  val old = listOf(Channel("a", "Provider", "u", customName = "BBC", customGroup = "News"))
  val result = ChannelReconciler.reconcile(old, listOf(Channel("a", "Renamed Provider", "u2", group = "World"))).single()
  assertEquals("BBC", result.displayName)
  assertEquals("News", result.displayGroup)
 }
}
