package com.hackmit.app.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BleScanMatchTest {

    private val otherUuid: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

    @Test
    fun matchesOnNordicUartServiceUuid() {
        assertTrue(isStrokeSenseAdvertisement(null, listOf(UnoQBle.SERVICE)))
    }

    @Test
    fun matchesOnLocalNameWhenUuidWasDropped() {
        assertTrue(isStrokeSenseAdvertisement("StrokeSense", emptyList()))
        assertTrue(isStrokeSenseAdvertisement("strokesense", emptyList()))
    }

    @Test
    fun ignoresUnrelatedAdvertisers() {
        assertFalse(isStrokeSenseAdvertisement("Galaxy Buds", listOf(otherUuid)))
        assertFalse(isStrokeSenseAdvertisement(null, emptyList()))
        assertFalse(isStrokeSenseAdvertisement("", emptyList()))
    }
}
