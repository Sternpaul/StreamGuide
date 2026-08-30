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
}
