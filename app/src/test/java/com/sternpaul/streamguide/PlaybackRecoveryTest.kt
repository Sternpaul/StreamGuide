package com.sternpaul.streamguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun retryStateAdvancesThenBecomesUnavailable() {
        assertEquals(PlaybackRecoveryPolicy.RetryPlan(1, 1_000L), PlaybackRecoveryPolicy.nextRetry(0))
        assertEquals(PlaybackRecoveryPolicy.RetryPlan(2, 2_500L), PlaybackRecoveryPolicy.nextRetry(1))
        assertEquals(PlaybackRecoveryPolicy.RetryPlan(3, 5_000L), PlaybackRecoveryPolicy.nextRetry(2))
        assertNull(PlaybackRecoveryPolicy.nextRetry(3))
    }

    @Test fun retryScheduleStopsAfterAutomaticRetryLimit() {
        assertEquals(1_000L, PlaybackRecoveryPolicy.delayForRetry(1))
        assertEquals(2_500L, PlaybackRecoveryPolicy.delayForRetry(2))
        assertEquals(5_000L, PlaybackRecoveryPolicy.delayForRetry(3))
        assertNull(PlaybackRecoveryPolicy.delayForRetry(4))
        assertNull(PlaybackRecoveryPolicy.delayForRetry(0))
    }
}
