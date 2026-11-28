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

    @Test fun `the wake word may be transcribed in many ways`() {
        check(
            Change(-15), "Hey, Muse. Baisse le son.", "Hé Muz, baisse le son", "Hey mews baisse un petit peu le volume",
            "Et muse, mets moins fort", "baisse le son sur le Portal, merci", "Ok, alors baisse encore un peu le son",
        )
        check(Change(15), "Hey Muse, mets le son un peu plus fort", "Eh Nova, tu peux mettre plus fort ?")
        check(Level(40), "Hey, Muse, le son à 40", "volume à quarante", "mets le volume sur 40 %")
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
            "baisse le chauffage", "", "c'est plus fort", "ne baisse pas le son", "don't turn it up",
            "il ne veut pas monter le son", "il crie de plus en plus fort", "il faut vraiment baisser le son",
        )) {
            assertNull(phrase, VolumeRequest.parse(phrase))
        }
    }
}
