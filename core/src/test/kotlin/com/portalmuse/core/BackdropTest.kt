package com.portalmuse.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropTest {
    private val width = 120
    private val height = 160
    private val black = 0xFF000000.toInt()
    private val fur = 0xFFD8641E.toInt()
    private val chest = 0xFFEBE1D2.toInt()
    private val paw = 0xFF2A201C.toInt()

    /** Un fond de studio en dégradé, un personnage orange au poitrail blanc, une patte sombre. */
    private fun picture() = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val distance = Math.hypot((x - 60).toDouble(), (y - 80).toDouble())
        val grey = 236 - y / 4
        when {
            distance < 15 -> chest
            distance < 40 -> fur
            x in 50..70 && y in 118..150 -> paw
            else -> (0xFF shl 24) or ((grey - 2) shl 16) or (grey shl 8) or (grey + 2)
        }
    }

    @Test fun theBackgroundTurnsBlackAndTheCharacterStays() {
        val pixels = picture()
        assertTrue(Backdrop(width, height).blacken(pixels))
        fun at(x: Int, y: Int) = pixels[y * width + x]
        for ((x, y) in listOf(0 to 0, 119 to 0, 5 to 80, 115 to 150, 2 to 159, 60 to 10)) {
            assertEquals("fond en $x,$y", black, at(x, y))
        }
        assertEquals("poitrail enfermé dans le pelage", chest, at(60, 80))
        assertEquals(fur, at(60, 55))
        assertEquals(paw, at(60, 135))
    }

    @Test fun aDarkPictureIsLeftAlone() {
        val pixels = IntArray(width * height) { if (it % 7 == 0) fur else black }
        val before = pixels.copyOf()
        assertFalse(Backdrop(width, height).blacken(pixels))
        assertArrayEquals(before, pixels)
    }

    @Test fun buffersAreReusedFromOnePictureToTheNext() {
        val backdrop = Backdrop(width, height)
        val first = picture().also { backdrop.blacken(it) }
        assertArrayEquals(first, picture().also { backdrop.blacken(it) })
        val odd = Backdrop(5, 5)
        assertTrue(odd.blacken(IntArray(25) { 0xFFE0E2E4.toInt() }))
    }
}
