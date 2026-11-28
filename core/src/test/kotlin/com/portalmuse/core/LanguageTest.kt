package com.portalmuse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LanguageTest {
    @Test fun `texts follow the device language`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("Fermer", tr("Close", "Fermer"))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("Close", tr("Close", "Fermer"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun `English farewells end a call too`() {
        for (phrase in listOf("goodbye", "Good night!", "see you tomorrow", "talk to you later", "hang up", "end the call")) {
            assertTrue(phrase, Farewell.matches(phrase))
        }
    }
}
