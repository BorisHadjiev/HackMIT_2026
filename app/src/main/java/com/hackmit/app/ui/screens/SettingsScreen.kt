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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hackmit.app.BuildConfig
import com.hackmit.app.alert.AlertConfig
import com.hackmit.app.sensor.SensorTransport
import com.hackmit.app.ui.AssessmentViewModel
import com.hackmit.app.ui.components.InfoRow
import com.hackmit.app.ui.components.ScreenScaffold
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AssessmentViewModel, nav: NavController) {
    val scope = rememberCoroutineScope()

    val storedProxy by vm.settingsStore.deepgramProxyUrl.collectAsState(initial = "")
    val storedMock by vm.settingsStore.mockSensors.collectAsState(initial = true)
    val storedMac by vm.settingsStore.sensorMac.collectAsState(initial = "")
    val storedContact by vm.settingsStore.emergencyContact.collectAsState(initial = "")
    val storedSms by vm.settingsStore.alertSmsEnabled.collectAsState(initial = false)
    val cfg by vm.settingsStore.alertConfig.collectAsState(initial = AlertConfig())

    var proxyInput by remember(storedProxy) { mutableStateOf(storedProxy) }
    var mock by remember(storedMock) { mutableStateOf(storedMock) }
    var address by remember(storedMac) { mutableStateOf(storedMac) }
    var contact by remember(storedContact) { mutableStateOf(storedContact) }
    var sms by remember(storedSms) { mutableStateOf(storedSms) }
    var transport by remember { mutableStateOf(SensorTransport.MOCK) }
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

            // Sensor source
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Sensor source", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Use mock sensors")
                        Switch(
                            checked = mock,
                            onCheckedChange = {
                                mock = it
                                scope.launch { vm.settingsStore.setMockSensors(it) }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SensorTransport.entries.forEach { option ->
                            FilterChip(
                                selected = transport == option,
                                onClick = { transport = option },
                                label = { Text(option.name) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("MAC / endpoint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                vm.settingsStore.setSensorMac(address)
                                vm.settingsStore.setSensorTransport(transport.name)
                            }
                            vm.sensorRepository.select(transport, address)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Apply sensor source") }
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