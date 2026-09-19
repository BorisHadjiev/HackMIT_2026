package com.hackmit.app.sensor

import kotlinx.coroutines.flow.Flow

enum class SensorTransport { MOCK, BLUETOOTH_SPP, BLE, WIFI }

/**
 * Holds the currently selected [SensorSource]. Screens call [select] when the user
 * picks hardware in Settings; until then everything runs on the mock source.
 */
class SensorRepository {

    var transport: SensorTransport = SensorTransport.MOCK
        private set

    var current: SensorSource = MockSensorSource()
        private set

    val isMock: Boolean get() = transport == SensorTransport.MOCK

    fun select(transport: SensorTransport, address: String = "", mockAbnormal: Boolean = false) {
        this.transport = transport
        current = when (transport) {
            SensorTransport.MOCK -> MockSensorSource(mockAbnormal)
            SensorTransport.BLUETOOTH_SPP -> BluetoothSppSource(address)
            SensorTransport.BLE -> BleSensorSource(address)
            SensorTransport.WIFI -> WifiSensorSource(address)
        }
    }

    fun samples(): Flow<SensorSample> = current.samples()
}
