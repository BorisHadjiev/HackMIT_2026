package com.hackmit.app.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorTransportResolveTest {

    @Test
    fun mockOnlyWhenBothFlagsSayMock() {
        assertEquals(SensorTransport.MOCK, resolveSensorTransport(true, "MOCK"))
        assertEquals(SensorTransport.MOCK, resolveSensorTransport(true, null))
        assertEquals(SensorTransport.MOCK, resolveSensorTransport(true, ""))
    }

    @Test
    fun turningMockOffSelectsBleEvenIfTransportStillSaysMock() {
        assertEquals(SensorTransport.BLE, resolveSensorTransport(false, "MOCK"))
        assertEquals(SensorTransport.BLE, resolveSensorTransport(false, null))
    }

    @Test
    fun bleChipWinsWhenMockSwitchWasLeftOn() {
        assertEquals(SensorTransport.BLE, resolveSensorTransport(true, "BLE"))
    }

    @Test
    fun bothBleFlagsStayBle() {
        assertEquals(SensorTransport.BLE, resolveSensorTransport(false, "BLE"))
    }

    @Test
    fun legacyWifiAndSppBecomeBle() {
        assertEquals(SensorTransport.BLE, sensorTransportOf("WIFI"))
        assertEquals(SensorTransport.BLE, sensorTransportOf("BLUETOOTH_SPP"))
        assertEquals(SensorTransport.BLE, resolveSensorTransport(false, "WIFI"))
    }
}
