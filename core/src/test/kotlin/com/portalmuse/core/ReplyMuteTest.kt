package com.portalmuse.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyMuteTest {
    private val mute = ReplyMute(startWithinMillis = 6_000, pauseMillis = 1_500, atMostMillis = 15_000)

    @Test fun nothingIsDroppedBeforeARequest() {
        assertFalse(mute.drops(true, 100))
    }

    @Test fun theWholeReplyIsDroppedAndTheNextOnePlays() {
        mute.start(0)
        assertTrue(mute.drops(false, 100))
        assertTrue(mute.drops(true, 800))
        assertTrue("a short pause within the reply", mute.drops(true, 2_000))
        assertTrue(mute.drops(false, 3_000))
        assertFalse("the next reply", mute.drops(true, 3_600))
        assertFalse(mute.drops(true, 3_620))
    }

    @Test fun aReplyThatNeverComesDoesNotMuteALaterOne() {
        mute.start(0)
        assertTrue(mute.drops(false, 5_000))
        assertFalse(mute.drops(true, 6_000))
    }

    @Test fun aReplyIsNotMutedForever() {
        mute.start(0)
        var now = 500L
        while (now < 15_000) {
            assertTrue(mute.drops(true, now))
            now += 500
        }
        assertFalse(mute.drops(true, 15_000))
    }
}
