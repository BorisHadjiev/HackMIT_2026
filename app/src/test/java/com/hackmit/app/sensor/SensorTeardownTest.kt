package com.hackmit.app.sensor

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTeardownTest {

    @Test
    fun mockSourceStopsStreamingAfterDisconnect() = runBlocking {
        val source = MockSensorSource()
        assertTrue(source.connect())
        assertTrue(source.samples().first().leftDeg.isFinite())
        source.disconnect()
        assertTrue(source.samples().toList().isEmpty())
    }

    @Test
    fun mockSourceStreamsAgainAfterReconnect() = runBlocking {
        val source = MockSensorSource()
        source.connect()
        source.disconnect()
        assertTrue(source.connect())
        assertTrue(source.samples().first().leftDeg.isFinite())
    }

    @Test
    fun staleTransportNamesDegradeToBle() {
        assertEquals(SensorTransport.BLE, sensorTransportOf("BLUETOOTH_SPP"))
        assertEquals(SensorTransport.BLE, sensorTransportOf("WIFI"))
        assertEquals(SensorTransport.BLE, sensorTransportOf("ble"))
    }

    @Test
    fun blankTransportNameFallsBackToMock() {
        assertEquals(SensorTransport.MOCK, sensorTransportOf("MOCK"))
        assertEquals(SensorTransport.MOCK, sensorTransportOf(null))
        assertEquals(SensorTransport.MOCK, sensorTransportOf(" "))
    }
}
