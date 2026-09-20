package com.hackmit.app.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

object UnoQBle {
    val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val ADVERTISING_NAME = "StrokeSense"
}

/**
 * The stack needs a moment after a GATT client is closed before it will hand out a
 * new one; connecting immediately after a disconnect otherwise fails with status 133.
 */
private const val GATT_SETTLE_MS = 700L

/**
 * Arduino UNO Q onboard BLE via Nordic UART Service advertised by python/main.py.
 */
class BleSensorSource(
    private val context: Context,
    private val macAddress: String,
) : SensorSource {

    private val samplesFlow = MutableSharedFlow<SensorSample>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val lineBuf = StringBuilder()
    private val gattLock = Any()
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var connected = false

    override val displayName: String = "UNO Q BLE ($macAddress)"

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Boolean {
        disconnect()
        val mac = macAddress.trim().uppercase()
        if (!mac.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))) return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull() ?: return false
        delay(GATT_SETTLE_MS)

        val ok = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                            if (!g.requestMtu(185)) {
                                g.discoverServices()
                            }
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            connected = false
                            if (cont.isActive) {
                                cont.resume(false)
                            } else {
                                // Dropped after the handshake: release the client now, or the
                                // next connect() is handed a dead one.
                                disconnect()
                            }
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        g.discoverServices()
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        val service = g.getService(UnoQBle.SERVICE)
                        val tx = service?.getCharacteristic(UnoQBle.TX)
                        val rx = service?.getCharacteristic(UnoQBle.RX)
                        if (tx == null || rx == null) {
                            if (cont.isActive) cont.resume(false)
                            return
                        }
                        rxChar = rx
                        g.setCharacteristicNotification(tx, true)
                        val cccd = tx.getDescriptor(UnoQBle.CCCD)
                        if (cccd != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                g.writeDescriptor(cccd)
                            }
                        } else if (cont.isActive) {
                            connected = true
                            cont.resume(true)
                        }
                    }

                    override fun onDescriptorWrite(
                        g: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int,
                    ) {
                        connected = status == BluetoothGatt.GATT_SUCCESS
                        if (cont.isActive) cont.resume(connected)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                    ) {
                        @Suppress("DEPRECATION")
                        ingest(characteristic.value)
                    }

                    override fun onCharacteristicChanged(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                    ) {
                        ingest(value)
                    }
                }
                val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, callback)
                }
                gatt = opened
                cont.invokeOnCancellation { disconnect() }
            }
        } ?: false
        if (!ok) disconnect()
        return ok
    }

    /**
     * Idempotent teardown: [BluetoothGatt.close] is what actually releases the client
     * and its callback, so both calls are needed and [gatt] is dropped afterwards.
     */
    override fun disconnect() {
        synchronized(gattLock) {
            connected = false
            rxChar = null
            val g = gatt
            gatt = null
            lineBuf.setLength(0)
            runCatching { g?.disconnect() }
            runCatching { g?.close() }
        }
    }

    override fun sendCommand(command: String): Boolean {
        val g = gatt ?: return false
        val rx = rxChar ?: return false
        val payload = "$command\n".toByteArray(Charsets.UTF_8)
        return synchronized(gattLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(rx, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                rx.value = payload
                @Suppress("DEPRECATION")
                g.writeCharacteristic(rx)
            }
        }
    }

    override fun samples(): Flow<SensorSample> = samplesFlow.asSharedFlow()

    private fun ingest(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        lineBuf.append(bytes.toString(Charsets.UTF_8))
        while (true) {
            val idx = lineBuf.indexOf("\n")
            if (idx < 0) break
            val line = lineBuf.substring(0, idx).trim('\r', ' ', '\t')
            lineBuf.delete(0, idx + 1)
            parseArmAngleLine(line)?.let { samplesFlow.tryEmit(it) }
        }
    }
}
