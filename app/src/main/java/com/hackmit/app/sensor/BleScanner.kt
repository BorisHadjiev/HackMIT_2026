package com.hackmit.app.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** One StrokeSense-looking advertiser seen during a BLE scan. */
data class ScannedBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

/** Why a BLE scan cannot start, or [Ready] when it can. */
enum class BleScanReadiness { READY, NO_PERMISSION, BLUETOOTH_OFF, NO_SCANNER }

/** Accumulated scan state. [error] is set once the scan cannot continue. */
data class BleScanUpdate(
    val devices: List<ScannedBleDevice> = emptyList(),
    val error: String? = null,
)

/**
 * Matches the UNO Q bridge from `python/main.py`. The service UUID is the reliable
 * signal; the local name is a fallback for advertisements whose UUID list was
 * dropped to fit the 31-byte legacy payload.
 */
internal fun isStrokeSenseAdvertisement(name: String?, serviceUuids: List<UUID>): Boolean {
    if (serviceUuids.any { it == UnoQBle.SERVICE }) return true
    val local = name?.trim().orEmpty()
    return local.equals(UnoQBle.ADVERTISING_NAME, ignoreCase = true)
}

@SuppressLint("MissingPermission")
fun bleScanReadiness(context: Context): BleScanReadiness {
    if (!hasBleScanPermission(context)) return BleScanReadiness.NO_PERMISSION
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: return BleScanReadiness.BLUETOOTH_OFF
    if (!adapter.isEnabled) return BleScanReadiness.BLUETOOTH_OFF
    if (adapter.bluetoothLeScanner == null) return BleScanReadiness.NO_SCANNER
    return BleScanReadiness.READY
}

private fun scanFailureText(errorCode: Int): String = when (errorCode) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already running"
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scan registration failed"
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning unsupported"
    else -> "Scan failed (code $errorCode)"
}

/**
 * Scans for the StrokeSense peripheral and emits the running list of matches.
 *
 * The scan is deliberately unfiltered so a board that advertises only its local
 * name is still found; matching happens in [isStrokeSenseAdvertisement].
 */
@SuppressLint("MissingPermission")
fun scanForStrokeSense(context: Context): Flow<BleScanUpdate> = callbackFlow {
    when (bleScanReadiness(context)) {
        BleScanReadiness.NO_PERMISSION -> {
            trySend(BleScanUpdate(error = "Bluetooth permission required"))
            close()
            return@callbackFlow
        }
        BleScanReadiness.BLUETOOTH_OFF -> {
            trySend(BleScanUpdate(error = "Turn Bluetooth on"))
            close()
            return@callbackFlow
        }
        BleScanReadiness.NO_SCANNER -> {
            trySend(BleScanUpdate(error = "BLE scanning unavailable"))
            close()
            return@callbackFlow
        }
        BleScanReadiness.READY -> Unit
    }

    val scanner = context.getSystemService(BluetoothManager::class.java)!!.adapter.bluetoothLeScanner
    val found = linkedMapOf<String, ScannedBleDevice>()

    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord
            val uuids = record?.serviceUuids?.map { it.uuid }.orEmpty()
            val advertisedName = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
            if (!isStrokeSenseAdvertisement(advertisedName, uuids)) return
            val address = result.device.address ?: return
            found[address] = ScannedBleDevice(
                name = advertisedName?.takeIf { it.isNotBlank() } ?: UnoQBle.ADVERTISING_NAME,
                address = address,
                rssi = result.rssi,
            )
            trySend(BleScanUpdate(devices = found.values.toList()))
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            trySend(BleScanUpdate(found.values.toList(), scanFailureText(errorCode)))
            close()
        }
    }

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    val started = runCatching { scanner.startScan(emptyList(), settings, callback) }.isSuccess
    if (!started) {
        trySend(BleScanUpdate(error = "Could not start BLE scan"))
        close()
        return@callbackFlow
    }
    trySend(BleScanUpdate())

    awaitClose { runCatching { scanner.stopScan(callback) } }
}

/**
 * Convenience for auto-connect: returns the strongest StrokeSense advertiser seen
 * within [timeoutMs], or null if none showed up.
 */
suspend fun findStrokeSense(context: Context, timeoutMs: Long = 8_000): ScannedBleDevice? =
    withTimeoutOrNull(timeoutMs) {
        scanForStrokeSense(context)
            .mapNotNull { update -> update.devices.maxByOrNull { it.rssi } }
            .first()
    }
