package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDelayTest {
    @Test fun offKeepsPlayerDefaults() {
        val plan = LiveDelay.plan(0)
        assertEquals(LiveDelay.DEFAULT_START_MS, plan.startMs)
        assertEquals(LiveDelay.DEFAULT_REBUFFER_MS, plan.rebufferMs)
        assertNull(plan.liveOffsetMs)
    }
    @Test fun delayWaitsForCushionBeforeStartingAndAfterStall() {
        val plan = LiveDelay.plan(10)
        assertEquals(10_000, plan.startMs)
        assertEquals(10_000, plan.rebufferMs)
        assertEquals(10_000L, plan.liveOffsetMs)
    }
    @Test fun startThresholdsNeverExceedMinBuffer() {
        LiveDelay.allowedSeconds.forEach { seconds ->
            val plan = LiveDelay.plan(seconds)
            assertTrue(plan.startMs <= plan.minBufferMs && plan.rebufferMs <= plan.minBufferMs && plan.minBufferMs <= plan.maxBufferMs)
        }
    }
    @Test fun unknownValueFallsBackToDefault() { assertEquals(LiveDelay.DEFAULT_SECONDS, LiveDelay.sanitize(7)) }
    @Test fun bufferingTimeoutGrowsWithDelay() { assertEquals(45_000L, LiveDelay.bufferingTimeoutMs(15_000L, 30)) }
}
