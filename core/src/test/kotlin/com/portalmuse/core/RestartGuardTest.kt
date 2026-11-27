package com.portalmuse.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestartGuardTest {
    @Test fun threeReopeningsInTenMinutesAndNoMore() {
        var history = emptyList<Long>()
        for (time in listOf(1_000_000L, 1_001_000L, 1_002_000L)) history = RestartGuard.allow(history, time)!!
        assertNull(RestartGuard.allow(history, 1_003_000L))
        assertEquals(listOf(2_000_000L), RestartGuard.allow(history, 2_000_000L))
        // Une horloge revenue en arrière ne bloque pas pour toujours.
        assertEquals(listOf(5L), RestartGuard.allow(history, 5L))
    }
}
