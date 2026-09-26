package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPolicyTest {
    @Test fun missingProviderNeverStartsUpdates() {
        assertEquals(StartupRefreshAction.NONE, RefreshPolicy.onAppStart(false, true, true, true))
    }
    @Test fun playlistOptionIncludesGuideRefresh() {
        assertEquals(StartupRefreshAction.FULL_PLAYLIST, RefreshPolicy.onAppStart(true, true, true, true))
    }
    @Test fun staleGuideRefreshesWhenStartupOptionIsEnabled() {
        assertEquals(StartupRefreshAction.EPG_ONLY, RefreshPolicy.onAppStart(true, false, true, true))
        assertEquals(StartupRefreshAction.NONE, RefreshPolicy.onAppStart(true, false, true, false))
        assertEquals(StartupRefreshAction.NONE, RefreshPolicy.onAppStart(true, false, false, true))
    }
}
