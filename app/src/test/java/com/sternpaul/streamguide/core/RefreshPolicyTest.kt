package com.sternpaul.streamguide.core

import org.junit.Assert.*
import org.junit.Test

class RefreshPolicyTest {
    @Test fun missingProviderNeverStartsUpdates() {
        assertEquals(StartupRefreshAction.NONE, RefreshPolicy.onAppStart(false, true))
    }
    @Test fun playlistOptionIncludesGuideRefresh() {
        assertEquals(StartupRefreshAction.FULL_PLAYLIST, RefreshPolicy.onAppStart(true, true))
    }
    @Test fun everyOpenRequestsEpgWhenPlaylistUpdatesAreOff() {
        repeat(3) {
            assertEquals(StartupRefreshAction.EPG_ONLY, RefreshPolicy.onAppStart(true, false))
        }
    }
    @Test fun foregroundAndManualRequestsCannotReuseEvenJustDownloadedData() {
        assertFalse(RefreshPolicy.canReuseRecentEpg(force = true, lastSuccess = 100_000, now = 100_000))
        assertFalse(RefreshPolicy.canReuseRecentEpg(force = true, lastSuccess = 100_000, now = 100_001))
    }
    @Test fun scheduledUpdatesStillCoalesceRecentDownloads() {
        assertTrue(RefreshPolicy.canReuseRecentEpg(force = false, lastSuccess = 100_000, now = 159_999))
        assertFalse(RefreshPolicy.canReuseRecentEpg(force = false, lastSuccess = 100_000, now = 160_000))
        assertFalse(RefreshPolicy.canReuseRecentEpg(force = false, lastSuccess = 0, now = 1))
        assertFalse(RefreshPolicy.canReuseRecentEpg(force = false, lastSuccess = 100_000, now = 99_999))
    }
}
