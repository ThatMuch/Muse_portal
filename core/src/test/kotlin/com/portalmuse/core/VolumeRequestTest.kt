package com.portalmuse.core

import com.portalmuse.core.VolumeRequest.Change
import com.portalmuse.core.VolumeRequest.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeRequestTest {
    private fun check(expected: VolumeRequest, vararg phrases: String) {
        for (phrase in phrases) assertEquals(phrase, expected, VolumeRequest.parse(phrase, "Nova"))
    }

    @Test fun `louder and quieter in French and English`() {
        check(
            Change(15), "Monte le son.", "monte un peu le volume", "Tu peux monter le son ?", "Plus fort !",
            "Nova, augmente le son s'il te plaît", "Hey Muse, parle plus fort", "turn it up", "Volume up, please",
            "Can you turn up the volume?", "louder",
        )
        check(
            Change(-15), "Baisse le son", "Moins fort.", "peux-tu baisser le volume ?", "diminue encore le son",
            "turn the volume down", "a bit quieter",
        )
    }

    @Test fun `a level in digits or words`() {
        check(Level(50), "Mets le volume à 50 %.", "mets le son à cinquante pour cent", "set the volume to 50 percent")
        check(Level(80), "Monte le son à quatre-vingts", "Règle le volume à 80%")
        check(Level(0), "mets le volume à zéro")
    }

    @Test fun `other sentences leave the volume alone`() {
        for (phrase in listOf(
            "Quel temps fait-il ?", "Il a parlé de plus fort que lui", "Le volume des ventes augmente",
            "Mets le volume à 400", "monte le son de la télé dans le salon quand le film commence ce soir",
            "baisse le chauffage", "",
        )) {
            assertNull(phrase, VolumeRequest.parse(phrase))
        }
    }
}
