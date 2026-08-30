package com.sternpaul.streamguide.core

import org.junit.Assert.*
import org.junit.Test

class CatchupUrlTest {
    @Test fun expandsCommonM3uPlaceholders() {
        val channel = Channel("id", "TV", "https://live", catchupSource = "https://archive/{utc}/{utcend}/{duration}.m3u8", catchupDays = 3)
        val program = Program("id", "News", startEpochMs = 1_000_000L, endEpochMs = 1_360_000L)
        assertEquals("https://archive/1000/1360/360.m3u8", CatchupUrl.forProgram(channel, program))
    }
    @Test fun returnsNullWithoutCatchupMetadata() { assertNull(CatchupUrl.forProgram(Channel("id","TV","u"), Program("id","P",startEpochMs=1,endEpochMs=2))) }
}
