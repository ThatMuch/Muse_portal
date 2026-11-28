package com.portalmuse.core

import com.portalmuse.core.LocalRequest.Night
import com.portalmuse.core.LocalRequest.PlayRadio
import com.portalmuse.core.LocalRequest.StopAlarm
import com.portalmuse.core.LocalRequest.StopRadio
import com.portalmuse.core.LocalRequest.StopTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRequestTest {
    private fun check(expected: LocalRequest, vararg phrases: String) {
        for (phrase in phrases) assertEquals(phrase, expected, LocalRequest.parse(phrase, "Nova"))
    }

    @Test fun `timers and alarms`() {
        check(StopTimer, "Arrête le minuteur.", "Hey Muse, coupe les minuteurs", "annule mon minuteur", "stop the timer")
        check(StopAlarm, "Arrête le réveil", "Hé Nova, stoppe l'alarme", "turn off my alarm")
    }

    @Test fun radio() {
        check(PlayRadio(Stations.byId("franceinter")), "Mets France Inter", "Hey Muse, lance la radio France Inter")
        check(PlayRadio(Stations.byId("fip")), "mets-moi FIP s'il te plaît", "play fip")
        check(PlayRadio(null), "Mets la radio.", "allume la radio", "turn on the radio")
        check(StopRadio, "Arrête la radio", "coupe la radio", "stop the radio")
    }

    @Test fun `night mode`() {
        check(Night(true), "Mode nuit", "passe en mode nuit", "éteins l'écran", "turn off the screen", "night mode on")
        check(Night(false), "désactive le mode nuit", "rallume l'écran", "turn on the screen", "night mode off")
    }

    @Test fun `volume stays recognized`() {
        check(LocalRequest.Volume(VolumeRequest.Change(-15)), "Hey Muse, baisse le son")
    }

    @Test fun `ordinary talk triggers nothing`() {
        for (phrase in listOf(
            "Quel temps fait-il ?", "ne coupe pas la radio", "il écoute la radio", "mets le minuteur à dix minutes",
            "à quelle heure est mon réveil ?", "éteins la lumière", "bonne nuit", "mets un minuteur de 5 minutes",
        )) {
            assertNull(phrase, LocalRequest.parse(phrase))
        }
    }
}
