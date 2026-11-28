package com.portalmuse.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PreRollTest {
    /** A source fed by hand; an empty chunk ends it. */
    private class Source {
        val queue = LinkedBlockingQueue<ByteArray>()
        fun next(): ByteArray? = queue.take().takeIf { it.isNotEmpty() }
        fun say(vararg values: Int) = values.forEach { queue.put(byteArrayOf(it.toByte(), 0)) }
        fun end() = queue.put(ByteArray(0))
        fun awaitDrained() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (queue.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            Thread.sleep(30)
        }
    }

    private fun PreRoll.values(count: Int) = List(count) { read()!![0].toInt() }

    @Test fun whatWasSaidBeforeTheFirstReadComesOutInOrder() {
        val source = Source()
        val roll = PreRoll(source::next, capacity = 10)
        source.say(1, 2, 3)
        source.awaitDrained()
        assertEquals(listOf(1, 2, 3), roll.values(3))
        source.say(4)
        assertEquals(listOf(4), roll.values(1))
        source.end()
        assertNull(roll.read())
    }

    @Test fun onlyTheMostRecentSoundIsKept() {
        val source = Source()
        val roll = PreRoll(source::next, capacity = 3)
        source.say(1, 2, 3, 4, 5)
        source.end()
        source.awaitDrained()
        assertEquals(listOf(3, 4, 5), roll.values(3))
        assertNull(roll.read())
    }

    @Test fun theBacklogIsPacedAndStopForgetsIt() {
        val source = Source()
        val roll = PreRoll(source::next, capacity = 20, minGapMillis = 20)
        source.say(1, 2, 3, 4, 5, 6)
        source.awaitDrained()
        val start = System.nanoTime()
        roll.values(5)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 60)
        roll.stop()
        assertNull(roll.read())
        source.end()
        roll.join(1_000)
    }

    @Test fun doublingTheRateInterpolatesAcrossFrames() {
        fun pcm(vararg samples: Int) = ByteArray(samples.size * 2) { (samples[it / 2] shr (8 * (it % 2))).toByte() }
        assertArrayEquals(pcm(50, 100, 200, 300), Upsampler.to48k(pcm(100, 300), 0))
        assertEquals(-400, Upsampler.last(pcm(100, -400)))
        assertEquals(1920, Upsampler.to48k(ByteArray(960), 0).size)
    }

    @Test fun upsamplingKeepsTheSignalAndItsDuration() {
        fun pcm(vararg samples: Int) = ByteArray(samples.size * 2) { (samples[it / 2] shr (8 * (it % 2))).toByte() }
        assertArrayEquals(pcm(0, 200, 400, 600, 800, 900), Upsampler.to24k(pcm(0, 300, 600, 900)))
        assertArrayEquals(pcm(-3000, -3000, -3000), Upsampler.to24k(pcm(-3000, -3000)))
        // 20 ms of listening become 20 ms of call.
        assertEquals(960, Upsampler.to24k(ByteArray(640)).size)
        assertEquals(1920, Upsampler.to24k(ByteArray(1280)).size)
    }
}
