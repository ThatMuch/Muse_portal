package com.portalmuse.core

/** What counts as the light of a studio background: bright, little colored, and cool rather than warm. */
private const val SEED_MIN = 160
private const val GROW_MIN = 125
private const val MAX_SPREAD = 40
/** White fur leans toward yellow (red above blue), the background does not. */
private const val MAX_WARMTH = 14
/** The tolerated gap between a pixel and its neighbor: a background is smooth, an outline is not. */
private const val MAX_STEP = 14

/**
 * Replaces the light background of a Muse picture with black: a flood fill from the edges that stops at the
 * character's outline. A white area surrounded by the character therefore stays white. Computed at half
 * resolution; buffers are reused from one picture to the next.
 */
class Backdrop(private val width: Int, private val height: Int) {
    private val halfWidth = (width + 1) / 2
    private val halfHeight = (height + 1) / 2
    /** 0: keep; 4: background; in between: edge. */
    private val mask = IntArray(halfWidth * halfHeight)
    private val queue = IntArray(halfWidth * halfHeight)

    private fun sample(pixels: IntArray, half: Int): Int =
        pixels[(half / halfWidth) * 2 * width + (half % halfWidth) * 2]

    private fun light(color: Int, minimum: Int): Boolean {
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        val low = minOf(red, green, blue)
        return low >= minimum && maxOf(red, green, blue) - low <= MAX_SPREAD && red - blue <= MAX_WARMTH
    }

    private fun close(a: Int, b: Int): Boolean =
        Math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= MAX_STEP &&
            Math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= MAX_STEP &&
            Math.abs((a and 0xFF) - (b and 0xFF)) <= MAX_STEP

    /**
     * Blackens the background of [pixels] (ARGB, row by row) in place. False, touching nothing, if the picture
     * has no light background.
     */
    fun blacken(pixels: IntArray): Boolean {
        require(pixels.size >= width * height)
        mask.fill(0)
        var tail = 0
        var border = 0
        fun seed(index: Int) {
            border++
            if (mask[index] == 0 && light(sample(pixels, index), SEED_MIN)) {
                mask[index] = 4
                queue[tail++] = index
            }
        }
        // The top and sides; the bottom may be occupied by the character.
        for (x in 0 until halfWidth) seed(x)
        for (y in 1 until halfHeight) {
            seed(y * halfWidth)
            seed(y * halfWidth + halfWidth - 1)
        }
        if (tail * 2 < border) return false
        var head = 0
        while (head < tail) {
            val index = queue[head++]
            val color = sample(pixels, index)
            val x = index % halfWidth
            fun grow(next: Int) {
                if (mask[next] != 0) return
                val other = sample(pixels, next)
                if (light(other, GROW_MIN) && close(color, other)) {
                    mask[next] = 4
                    queue[tail++] = next
                }
            }
            if (x > 0) grow(index - 1)
            if (x < halfWidth - 1) grow(index + 1)
            if (index >= halfWidth) grow(index - halfWidth)
            if (index < mask.size - halfWidth) grow(index + halfWidth)
        }
        soften(pixels)
        paint(pixels)
        return true
    }

    /** Light pixels touching the background are darkened: no white fringe. */
    private fun soften(pixels: IntArray) {
        for (index in mask.indices) {
            if (mask[index] != 0) continue
            val x = index % halfWidth
            val beside = x > 0 && mask[index - 1] == 4 || x < halfWidth - 1 && mask[index + 1] == 4 ||
                index >= halfWidth && mask[index - halfWidth] == 4 ||
                index < mask.size - halfWidth && mask[index + halfWidth] == 4
            if (!beside) continue
            val color = sample(pixels, index)
            val low = minOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)
            mask[index] = if (low >= GROW_MIN) 3 else 1
        }
    }

    private fun paint(pixels: IntArray) {
        for (y in 0 until height) {
            val row = (y / 2) * halfWidth
            val below = if (y % 2 == 1 && y / 2 < halfHeight - 1) row + halfWidth else row
            for (x in 0 until width) {
                val column = x / 2
                val right = if (x % 2 == 1 && column < halfWidth - 1) column + 1 else column
                // The average of the neighbors smooths the outline between two computed pixels.
                val covered = mask[row + column] + mask[row + right] + mask[below + column] + mask[below + right]
                if (covered == 0) continue
                val index = y * width + x
                if (covered >= 16) {
                    pixels[index] = 0xFF000000.toInt()
                } else {
                    val keep = 16 - covered
                    val color = pixels[index]
                    val red = (color shr 16 and 0xFF) * keep shr 4
                    val green = (color shr 8 and 0xFF) * keep shr 4
                    val blue = (color and 0xFF) * keep shr 4
                    pixels[index] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
    }
}
