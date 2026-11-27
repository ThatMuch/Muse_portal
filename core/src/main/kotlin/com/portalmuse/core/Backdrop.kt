package com.portalmuse.core

/** Le clair d'un fond de studio : lumineux, peu coloré, et plutôt froid que chaud. */
private const val SEED_MIN = 160
private const val GROW_MIN = 125
private const val MAX_SPREAD = 40
/** Les poils blancs tirent sur le jaune (rouge au-dessus du bleu), pas le fond. */
private const val MAX_WARMTH = 14
/** L'écart toléré d'un point à son voisin : un fond est lisse, un contour ne l'est pas. */
private const val MAX_STEP = 14

/**
 * Remplace par du noir le fond clair d'une image de Muse : une propagation depuis les bords, qui
 * s'arrête aux contours du personnage. Un blanc entouré par le personnage reste donc blanc. Le
 * calcul se fait à demi-résolution ; les tampons sont réutilisés d'une image à l'autre.
 */
class Backdrop(private val width: Int, private val height: Int) {
    private val halfWidth = (width + 1) / 2
    private val halfHeight = (height + 1) / 2
    /** 0 : à garder ; 4 : fond ; entre les deux : liseré. */
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
     * Noircit le fond de [pixels] (ARGB, rangée par rangée) sur place. Faux, sans rien toucher, si
     * l'image n'a pas de fond clair.
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
        // Le haut et les côtés ; le bas peut être occupé par le personnage.
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

    /** Les points clairs qui touchent le fond sont assombris : pas de liseré blanc. */
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
                // La moyenne des voisins lisse le contour entre deux points calculés.
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
