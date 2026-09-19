package com.hackmit.app.sensor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

/**
 * One IMU reading. Units are whatever the firmware sends; keep it consistent:
 * accel in m/s^2, gyro in deg/s.
 */
data class SensorSample(
    val timestampMs: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
) {
    val accelMagnitude: Float get() = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
}

/**
 * Any hardware that can stream IMU data. Implementations must be safe to call
 * [connect] before collecting [samples].
 */
interface SensorSource {
    val displayName: String

    suspend fun connect(): Boolean

    fun disconnect()

    fun samples(): Flow<SensorSample>
}

/**
 * Synthetic source so the whole app is demoable without an Arduino.
 * [abnormal] injects tremor + drift so the UI can show a positive screen.
 */
class MockSensorSource(private val abnormal: Boolean = false) : SensorSource {

    override val displayName: String = if (abnormal) "Mock IMU (abnormal)" else "Mock IMU (normal)"

    override suspend fun connect(): Boolean {
        delay(400)
        return true
    }

    override fun disconnect() = Unit

    override fun samples(): Flow<SensorSample> = flow {
        var t = 0L
        while (true) {
            val tremor = if (abnormal) {
                2.2f * sin(t / 38.0).toFloat() + 0.8f * sin(t / 11.0).toFloat()
            } else {
                0.12f * sin(t / 90.0).toFloat()
            }
            val drift = if (abnormal) (t / 1000f) * 0.9f else 0f
            emit(
                SensorSample(
                    timestampMs = t,
                    ax = tremor + drift,
                    ay = 0.05f * sin(t / 70.0).toFloat(),
                    az = 9.81f,
                    gx = tremor * 2.1f,
                    gy = drift,
                    gz = 0.08f * sin(t / 60.0).toFloat(),
                ),
            )
            t += 50
            delay(50)
        }
    }
}

/**
 * Arduino Uno + HC-05/HC-06 over Classic Bluetooth SPP.
 *
 * TODO: implement with BluetoothSocket RFCOMM.
 *   1. BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac)
 *   2. createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
 *   3. socket.connect(); read line-delimited CSV: ax,ay,az,gx,gy,gz
 *   4. Requires BLUETOOTH_CONNECT at runtime on API 31+.
 */
class BluetoothSppSource(private val macAddress: String) : SensorSource {

    override val displayName: String = "Bluetooth SPP ($macAddress)"

    override suspend fun connect(): Boolean = false

    override fun disconnect() = Unit

    override fun samples(): Flow<SensorSample> = flow { /* TODO: parse RFCOMM stream */ }
}

/**
 * HM-10 / ESP32 over BLE GATT notifications.
 *
 * TODO: implement with BluetoothGatt + a notify characteristic (e.g. FFE1).
 *   Requires BLUETOOTH_SCAN/CONNECT at runtime on API 31+.
 */
class BleSensorSource(private val macAddress: String) : SensorSource {

    override val displayName: String = "BLE ($macAddress)"

    override suspend fun connect(): Boolean = false

    override fun disconnect() = Unit

    override fun samples(): Flow<SensorSample> = flow { /* TODO: parse GATT notifications */ }
}

/**
 * ESP8266/ESP32 gateway that pushes samples over WebSocket, e.g. ws://192.168.4.1:81.
 *
 * TODO: implement with OkHttp WebSocket and JSON frames.
 */
class WifiSensorSource(private val endpoint: String) : SensorSource {

    override val displayName: String = "WiFi ($endpoint)"

    override suspend fun connect(): Boolean = false

    override fun disconnect() = Unit

    override fun samples(): Flow<SensorSample> = flow { /* TODO: parse WebSocket frames */ }
}
