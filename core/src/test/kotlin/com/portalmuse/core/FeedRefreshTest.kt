package com.portalmuse.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRefreshTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `an empty or old feed is asked again`() {
        assertTrue(FeedRefresh.isDue(null, null, now))
        assertTrue(FeedRefresh.isDue(now - FeedRefresh.STALE_MILLIS, null, now))
        assertFalse(FeedRefresh.isDue(now - 10 * minute, null, now))
    }

    @Test
    fun `a recent request is not repeated`() {
        assertFalse(FeedRefresh.isDue(null, now - 5 * minute, now))
        assertTrue(FeedRefresh.isDue(null, now - FeedRefresh.RETRY_MILLIS, now))
    }

    @Test
    fun `a clock set back does not block the feed`() {
        assertTrue(FeedRefresh.isDue(now + 60 * minute, now + 60 * minute, now))
    }
}
