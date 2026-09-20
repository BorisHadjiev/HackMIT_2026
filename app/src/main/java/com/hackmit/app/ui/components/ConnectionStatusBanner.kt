package com.hackmit.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hackmit.app.sensor.UnoQBle
import com.hackmit.app.ui.SensorStage
import com.hackmit.app.ui.theme.Amber
import com.hackmit.app.ui.theme.Coral
import com.hackmit.app.ui.theme.Green
import com.hackmit.app.ui.theme.Slate

enum class ConnectionTone { LIVE, PROGRESS, MOCK, DOWN }

data class ConnectionBannerModel(
    val title: String,
    val subtitle: String,
    val tone: ConnectionTone,
)

/**
 * Copy for the persistent connected/not-connected banner. Kept free of Compose so
 * the wording can be unit-tested: Live must never read as disconnected, and
 * disconnected must never read as Live.
 */
fun connectionBannerModel(
    stage: SensorStage,
    address: String = "",
    deviceName: String = UnoQBle.ADVERTISING_NAME,
): ConnectionBannerModel {
    val mac = address.trim()
    return when (stage) {
        SensorStage.LIVE -> ConnectionBannerModel(
            title = "Connected · $deviceName",
            subtitle = mac,
            tone = ConnectionTone.LIVE,
        )
        SensorStage.CONNECTING -> ConnectionBannerModel(
            title = "Connecting…",
            subtitle = mac.ifBlank { deviceName },
            tone = ConnectionTone.PROGRESS,
        )
        SensorStage.SCANNING -> ConnectionBannerModel(
            title = "Scanning…",
            subtitle = "Looking for $deviceName",
            tone = ConnectionTone.PROGRESS,
        )
        SensorStage.MOCK -> ConnectionBannerModel(
            title = "Mock data",
            subtitle = "Demo stream — not the Uno Q",
            tone = ConnectionTone.MOCK,
        )
        SensorStage.DISCONNECTED -> ConnectionBannerModel(
            title = "Disconnected",
            subtitle = mac,
            tone = ConnectionTone.DOWN,
        )
        SensorStage.PERMISSION_REQUIRED -> ConnectionBannerModel(
            title = "Not connected",
            subtitle = "Bluetooth permission required",
            tone = ConnectionTone.DOWN,
        )
        SensorStage.BLUETOOTH_OFF -> ConnectionBannerModel(
            title = "Not connected",
            subtitle = "Turn Bluetooth on",
            tone = ConnectionTone.DOWN,
        )
        SensorStage.NOT_FOUND -> ConnectionBannerModel(
            title = "Not connected",
            subtitle = "StrokeSense not found",
            tone = ConnectionTone.DOWN,
        )
        SensorStage.FAILED -> ConnectionBannerModel(
            title = "Not connected",
            subtitle = if (mac.isBlank()) "Could not connect" else "Could not connect · $mac",
            tone = ConnectionTone.DOWN,
        )
        SensorStage.IDLE -> ConnectionBannerModel(
            title = "Not connected",
            subtitle = mac,
            tone = ConnectionTone.DOWN,
        )
    }
}

@Composable
fun ConnectionStatusBanner(
    stage: SensorStage,
    address: String = "",
    modifier: Modifier = Modifier,
) {
    val model = connectionBannerModel(stage, address)
    val (container, content) = when (model.tone) {
        ConnectionTone.LIVE -> Green.copy(alpha = 0.16f) to Green
        ConnectionTone.PROGRESS -> Amber.copy(alpha = 0.20f) to Color(0xFF8A5A00)
        ConnectionTone.MOCK -> Slate.copy(alpha = 0.12f) to Slate
        ConnectionTone.DOWN -> Coral.copy(alpha = 0.16f) to Coral
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    model.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
                if (model.subtitle.isNotBlank()) {
                    Text(
                        model.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = content,
                    )
                }
            }
        }
    }
}
