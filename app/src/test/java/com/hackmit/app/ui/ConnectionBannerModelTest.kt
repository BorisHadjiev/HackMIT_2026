package com.hackmit.app.ui

import com.hackmit.app.ui.components.ConnectionTone
import com.hackmit.app.ui.components.connectionBannerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionBannerModelTest {

    @Test
    fun liveShowsConnectedAndMac() {
        val model = connectionBannerModel(SensorStage.LIVE, "14:B5:CD:F3:98:71")
        assertEquals(ConnectionTone.LIVE, model.tone)
        assertTrue(model.title.startsWith("Connected"))
        assertTrue(model.title.contains("StrokeSense"))
        assertEquals("14:B5:CD:F3:98:71", model.subtitle)
        assertFalse(model.title.contains("Not connected"))
    }

    @Test
    fun disconnectedNeverReadsLive() {
        val model = connectionBannerModel(SensorStage.DISCONNECTED, "14:B5:CD:F3:98:71")
        assertEquals(ConnectionTone.DOWN, model.tone)
        assertEquals("Disconnected", model.title)
        assertFalse(model.title.contains("Live", ignoreCase = true))
        assertFalse(model.title.contains("Connected ·"))
    }

    @Test
    fun connectingIsAmberProgress() {
        val model = connectionBannerModel(SensorStage.CONNECTING, "AA:BB")
        assertEquals(ConnectionTone.PROGRESS, model.tone)
        assertEquals("Connecting…", model.title)
    }

    @Test
    fun mockIsGrayNotLive() {
        val model = connectionBannerModel(SensorStage.MOCK)
        assertEquals(ConnectionTone.MOCK, model.tone)
        assertEquals("Mock data", model.title)
        assertFalse(model.title.contains("Connected"))
    }

    @Test
    fun failedAndIdleAreNotConnected() {
        assertEquals("Not connected", connectionBannerModel(SensorStage.FAILED).title)
        assertEquals("Not connected", connectionBannerModel(SensorStage.IDLE).title)
        assertEquals(ConnectionTone.DOWN, connectionBannerModel(SensorStage.NOT_FOUND).tone)
    }
}
