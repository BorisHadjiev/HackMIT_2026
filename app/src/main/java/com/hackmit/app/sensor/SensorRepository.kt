package com.hackmit.app.sensor

import android.content.Context
import kotlinx.coroutines.flow.Flow

/** The UNO Q is a BLE-only peripheral, so BLE and the demo mock are the only options. */
enum class SensorTransport { MOCK, BLE }

/**
 * Reads a persisted transport name. Earlier builds also stored `BLUETOOTH_SPP` and
 * `WIFI`, so anything unrecognised degrades to BLE (or the mock when nothing is
 * saved) instead of leaving the picker on an option that no longer exists.
 */
fun sensorTransportOf(name: String?): SensorTransport = when (name?.trim()?.uppercase()) {
    SensorTransport.BLE.name -> SensorTransport.BLE
    SensorTransport.MOCK.name, null, "" -> SensorTransport.MOCK
    else -> SensorTransport.BLE
}

/**
 * Older builds stored "use mock" as a Switch *and* Mock/BLE as chips. Those two
 * writes could disagree — typically mock=false with transport still MOCK — and
 * Motor connect used to honor mock first, which ignored a BLE chip tap,
 * or honor transport first, which ignored turning mock off.
 *
 * Rule: mock only when *both* flags say mock. Anything else is real BLE.
 */
fun resolveSensorTransport(mockSensors: Boolean, transportName: String?): SensorTransport {
    val named = sensorTransportOf(transportName)
    return if (named == SensorTransport.MOCK && mockSensors) {
        SensorTransport.MOCK
    } else {
        SensorTransport.BLE
    }
}

/**
 * Holds the currently selected [SensorSource]. Screens call [select] when the user
 * picks hardware in Settings; until then everything runs on the mock source.
 */
class SensorRepository(private val context: Context) {

    var transport: SensorTransport = SensorTransport.MOCK
        private set

    var current: SensorSource = MockSensorSource()
        private set

    var lastAddress: String = ""
        private set

    /**
     * Bumped on every [select]. Screens record the value they connected under so a
     * screen leaving composition cannot tear down a link a newer screen just opened.
     */
    var sessionId: Int = 0
        private set

    val isMock: Boolean get() = transport == SensorTransport.MOCK

    fun select(transport: SensorTransport, address: String = "", mockAbnormal: Boolean = false) {
        stopAndDisconnect()
        this.transport = transport
        this.lastAddress = address
        sessionId++
        current = when (transport) {
            SensorTransport.MOCK -> MockSensorSource(mockAbnormal)
            SensorTransport.BLE -> BleSensorSource(context, address)
        }
    }

    suspend fun connect(): Boolean = current.connect()

    fun scanReadiness(): BleScanReadiness = bleScanReadiness(context)

    /** Short BLE scan used to auto-locate the UNO Q when no MAC is saved yet. */
    suspend fun findBleBoard(timeoutMs: Long = 8_000): ScannedBleDevice? =
        findStrokeSense(context, timeoutMs)

    fun disconnect() = current.disconnect()

    /**
     * Teardown for a deliberate disconnect. STOP goes out first so the board stops
     * buzzing instead of cueing on by itself once the link is gone.
     */
    fun stopAndDisconnect() {
        current.sendCommand("STOP")
        current.disconnect()
    }

    fun sendCommand(command: String): Boolean = current.sendCommand(command)

    fun samples(): Flow<SensorSample> = current.samples()
}
