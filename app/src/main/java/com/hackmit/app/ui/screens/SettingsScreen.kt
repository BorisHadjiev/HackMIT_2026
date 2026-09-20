package com.hackmit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.BuildConfig
import com.hackmit.app.alert.AlertConfig
import com.hackmit.app.sensor.ScannedBleDevice
import com.hackmit.app.sensor.SensorTransport
import com.hackmit.app.sensor.bleScanPermissions
import com.hackmit.app.sensor.bluetoothEnabled
import com.hackmit.app.sensor.scanForStrokeSense
import com.hackmit.app.sensor.sensorTransportOf
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.components.ConnectionStatusBanner
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScreenScaffold
import com.hackmit.app.ui.components.SleepHoursEditor
import com.hackmit.app.ui.components.rememberPermissionsState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the UNO Q without system pairing. A BLE-only GATT peripheral is invisible to
 * Android's Bluetooth settings screen, so this in-app scan is the only reliable way
 * for the user to discover the board.
 */
@Composable
private fun BleBoardPicker(selected: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val scanPermission = rememberPermissionsState(bleScanPermissions())
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<ScannedBleDevice>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        devices = emptyList()
        status = "Scanning…"
        var problem: String? = null
        withTimeoutOrNull(12_000) {
            scanForStrokeSense(context).collect { update ->
                devices = update.devices
                problem = update.error
                status = update.error
                    ?: if (update.devices.isEmpty()) "Scanning…" else "Found ${update.devices.size}"
            }
        }
        status = problem
            ?: if (devices.isEmpty()) {
                "No StrokeSense advertisement seen. Check the board is powered and python/main.py is running."
            } else {
                "Found ${devices.size} — tap to select"
            }
        scanning = false
    }

    when {
        !scanPermission.granted -> {
            Text(
                "Bluetooth scan permission is required to find the board.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = scanPermission.request, modifier = Modifier.fillMaxWidth()) {
                Text("Allow Bluetooth")
            }
        }

        !bluetoothEnabled(context) -> Text(
            "Turn Bluetooth on to scan for StrokeSense.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> {
            Button(
                onClick = { scanning = !scanning },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (scanning) "Stop scan" else "Scan for StrokeSense") }
            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            devices.forEach { device ->
                FilterChip(
                    selected = selected.equals(device.address, ignoreCase = true),
                    onClick = { onPick(device.address) },
                    label = { Text("${device.name}  ${device.address}  ${device.rssi} dBm") },
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AssessmentViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()

    val storedProxy by vm.settingsStore.deepgramProxyUrl.collectAsState(initial = "")
    val storedMac by vm.settingsStore.sensorMac.collectAsState(initial = "")
    val storedTransportName by vm.settingsStore.sensorTransport.collectAsState(initial = "MOCK")
    val storedContact by vm.settingsStore.emergencyContact.collectAsState(initial = "")
    val storedSms by vm.settingsStore.alertSmsEnabled.collectAsState(initial = false)
    val cfg by vm.settingsStore.alertConfig.collectAsState(initial = AlertConfig())

    var proxyInput by remember(storedProxy) { mutableStateOf(storedProxy) }
    var address by remember(storedMac) { mutableStateOf(storedMac) }
    var contact by remember(storedContact) { mutableStateOf(storedContact) }
    var sms by remember(storedSms) { mutableStateOf(storedSms) }
    var transport by remember(storedTransportName) { mutableStateOf(sensorTransportOf(storedTransportName)) }
    var gatewayUrl by remember(cfg) { mutableStateOf(cfg.gatewayUrl) }
    var gatewayToken by remember(cfg) { mutableStateOf(cfg.gatewayToken) }
    var contactName by remember(cfg) { mutableStateOf(cfg.trustedContactName) }
    var contactPhone by remember(cfg) { mutableStateOf(cfg.trustedContactPhone) }
    var emergencyNumber by remember(cfg) { mutableStateOf(cfg.emergencyNumber) }

    val gatewayReady = cfg.gatewayUrl.isNotBlank() && cfg.gatewayToken.isNotBlank()

    ScreenScaffold(title = "Settings", onBack = { nav.popBackStack() }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    InfoRow(
                        "Voice & alerts",
                        if (gatewayReady) "Ready" else "Gateway not configured",
                        valueColor = if (gatewayReady) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    )
                    InfoRow("Backend", cfg.gatewayUrl.ifBlank { "—" })
                    InfoRow("Gateway token", if (cfg.gatewayToken.isNotBlank()) "Configured" else "Missing")
                    Text(
                        "Everything — spoken instructions, the Deepgram proxy, speaker gating, " +
                            "personalized results, and care alerts — runs through this one backend. " +
                            "It is pre-configured for this demo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Backend gateway
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Backend gateway", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = gatewayUrl,
                        onValueChange = { gatewayUrl = it },
                        label = { Text("Gateway URL") },
                        placeholder = { Text(BuildConfig.GATEWAY_BASE_URL.ifBlank { "https://host" }) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gatewayToken,
                        onValueChange = { gatewayToken = it },
                        label = { Text("Gateway token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch { vm.settingsStore.setAlertConfig(cfg.copy(gatewayUrl = gatewayUrl, gatewayToken = gatewayToken)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save gateway") }
                    OutlinedButton(
                        onClick = {
                            scope.launch { vm.settingsStore.setAlertConfig(cfg.copy(gatewayUrl = "", gatewayToken = "")) }
                            gatewayUrl = cfg.gatewayUrl
                            gatewayToken = cfg.gatewayToken
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reset to demo defaults") }
                }
            }

            // Care alerts (Linq)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Care alerts", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = { contactName = it },
                        label = { Text("Trusted contact name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = contactPhone,
                        onValueChange = { contactPhone = it },
                        label = { Text("Trusted contact phone (E.164)") },
                        placeholder = { Text("+15551234567") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = emergencyNumber,
                        onValueChange = { emergencyNumber = it },
                        label = { Text("Local emergency number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                vm.settingsStore.setAlertConfig(
                                    cfg.copy(
                                        trustedContactName = contactName,
                                        trustedContactPhone = contactPhone,
                                        emergencyNumber = emergencyNumber,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save contact") }
                    Text(
                        "A short screening summary is sent to this contact on request. The Linq " +
                            "integration token stays on the backend.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Emergency SMS (monitoring)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Emergency SMS", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        label = { Text("Emergency contact number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Text contact on alert")
                        Switch(
                            checked = sms,
                            onCheckedChange = {
                                sms = it
                                scope.launch { vm.settingsStore.setAlertSmsEnabled(it) }
                            },
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                vm.settingsStore.setEmergencyContact(contact)
                                vm.settingsStore.setAlertSmsEnabled(sms)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save SMS settings") }
                    Text(
                        "On a high-confidence alert you get a 15s window to cancel before the text is sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Speech transcription
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Speech transcription", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Audio streams through the backend proxy by default (Deepgram key stays " +
                            "on the server). These are only for advanced overrides.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = proxyInput,
                        onValueChange = { proxyInput = it },
                        label = { Text("Deepgram proxy URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("Deepgram API key (direct mode only)") },
                        enabled = false,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { scope.launch { vm.settingsStore.setDeepgramProxyUrl(proxyInput) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save transcription settings") }
                }
            }

            // Sensor source — one exclusive choice. Mock and BLE must never both look selected.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Arduino IMU", style = MaterialTheme.typography.titleMedium)
                    ConnectionStatusBanner(stage = vm.sensorStage, address = vm.sensorAddress)
                    Text(
                        "Pick one source. Mock is demo data. Uno Q is the real board " +
                            "(StrokeSense over BLE — it will not appear in Android Bluetooth " +
                            "settings). Scan or paste the MAC, then Connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = transport == SensorTransport.MOCK,
                            onClick = {
                                transport = SensorTransport.MOCK
                                vm.chooseMock()
                            },
                            label = { Text("Mock data") },
                        )
                        FilterChip(
                            selected = transport == SensorTransport.BLE,
                            onClick = {
                                transport = SensorTransport.BLE
                                vm.chooseBle(address)
                            },
                            label = { Text("Uno Q BLE") },
                        )
                    }
                    if (transport == SensorTransport.BLE) {
                        BleBoardPicker(
                            selected = address,
                            onPick = {
                                address = it
                                vm.chooseBle(it)
                            },
                        )
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Board MAC") },
                            placeholder = { Text("14:B5:CD:F3:98:71") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                scope.launch { vm.applySensorSource(SensorTransport.BLE, address) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Connect to board") }
                        OutlinedButton(
                            enabled = vm.sensorLinkActive,
                            onClick = { vm.disconnectSensor() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Disconnect") }
                    }
                    SleepHoursEditor(vm)
                }
            }

            // About
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    InfoRow("App", BuildConfig.APPLICATION_ID)
                    InfoRow("Version", BuildConfig.VERSION_NAME)
                    InfoRow("Build type", BuildConfig.BUILD_TYPE)
                }
            }
        }
    }
}