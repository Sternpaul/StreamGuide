package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDelayTest {
    @Test fun defaultWaitsForSecondStall() {
        assertFalse(LiveDelay.shouldActivate(10, LiveDelay.DEFAULT_ACTIVATION, 0))
        assertFalse(LiveDelay.shouldActivate(10, LiveDelay.DEFAULT_ACTIVATION, 1))
        assertTrue(LiveDelay.shouldActivate(10, LiveDelay.DEFAULT_ACTIVATION, 2))
    }
    @Test fun alwaysStartsWithCushion() { assertTrue(LiveDelay.shouldActivate(10, 0, 0)) }
    @Test fun offNeverActivates() { assertFalse(LiveDelay.shouldActivate(0, 0, 99)) }
    @Test fun shortLiveWindowCapsCushion() {
        assertEquals(9_000, LiveDelay.effectiveCushionMs(20_000, 18_000))
        assertEquals(20_000, LiveDelay.effectiveCushionMs(20_000, null))
    }
    @Test fun unknownValuesFallBackToDefaults() {
        assertEquals(LiveDelay.DEFAULT_SECONDS, LiveDelay.sanitizeSeconds(7))
        assertEquals(LiveDelay.DEFAULT_ACTIVATION, LiveDelay.sanitizeActivation(9))
    }
    @Test fun bufferingTimeoutGrowsWithCushion() { assertEquals(25_000L, LiveDelay.bufferingTimeoutMs(15_000L, 10_000)) }
    @Test fun waitingTextShowsProgress() {
        assertEquals("Waiting 10s to avoid further buffering · 4s ready", LiveDelay.waitingText(10_000, 4_300))
        assertEquals("Waiting 10s to avoid further buffering · 10s ready", LiveDelay.waitingText(10_000, 12_000))
    }
    @Test fun labels() {
        assertEquals("Off", LiveDelay.secondsLabel(0))
        assertEquals("After 2nd stall", LiveDelay.activationLabel(2))
    }
}
