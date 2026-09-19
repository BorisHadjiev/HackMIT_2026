package com.hackmit.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hackmit.app.MainActivity
import com.hackmit.app.R
import com.hackmit.app.data.SettingsStore
import com.hackmit.app.domain.AlertLevel
import com.hackmit.app.domain.SlurAssessment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface AlertEvent {
    data class RunFastAssessment(val reasons: List<String>) : AlertEvent
    data class Banner(val level: AlertLevel, val reasons: List<String>) : AlertEvent
}

/**
 * Turns slur assessments into user-facing alerts: notifications, an in-app banner,
 * an auto-run FAST assessment, and an optionally-cancellable SMS to a contact.
 */
class AlertManager(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {

    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AlertEvent> = _events.asSharedFlow()

    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()

    private var contact: String = ""
    private var smsEnabled: Boolean = false
    private var lastAlertMs = 0L
    private var smsJob: Job? = null
    private var lastWarningMs = 0L

    init {
        scope.launch { settings.emergencyContact.collect { contact = it } }
        scope.launch { settings.alertSmsEnabled.collect { smsEnabled = it } }
    }

    fun onAssessment(assessment: SlurAssessment) {
        when (assessment.level) {
            AlertLevel.NORMAL -> Unit
            AlertLevel.WARNING -> {
                val now = System.currentTimeMillis()
                if (now - lastWarningMs < WARNING_COOLDOWN_MS) return
                lastWarningMs = now
                _events.tryEmit(AlertEvent.Banner(AlertLevel.WARNING, assessment.reasons))
                notify(
                    id = NOTIFICATION_WARNING,
                    title = "Possible speech change",
                    text = assessment.reasons.firstOrNull() ?: "Slurring indicators rising",
                    priority = NotificationCompat.PRIORITY_DEFAULT,
                )
            }
            AlertLevel.ALERT -> {
                val now = System.currentTimeMillis()
                if (now - lastAlertMs < ALERT_COOLDOWN_MS) return
                lastAlertMs = now
                _events.tryEmit(AlertEvent.Banner(AlertLevel.ALERT, assessment.reasons))
                _events.tryEmit(AlertEvent.RunFastAssessment(assessment.reasons))
                notify(
                    id = NOTIFICATION_ALERT,
                    title = "Stroke signs detected",
                    text = "Speech change detected. Running the FAST check.",
                    priority = NotificationCompat.PRIORITY_HIGH,
                )
                startSmsCountdown(assessment.reasons)
            }
        }
    }

    fun cancelSms() {
        smsJob?.cancel()
        smsJob = null
        _countdown.value = null
    }

    private fun startSmsCountdown(reasons: List<String>) {
        if (!smsEnabled || contact.isBlank()) return
        smsJob?.cancel()
        smsJob = scope.launch {
            for (s in SMS_COUNTDOWN_SEC downTo 1) {
                _countdown.value = s
                delay(1000)
            }
            _countdown.value = null
            sendSms(reasons)
        }
    }

    private fun sendSms(reasons: List<String>) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val message = "StrokeSense detected a possible speech change at $time. " +
            "Please check on me. Signs: ${reasons.joinToString(", ").ifBlank { "speech change" }}."
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager?.sendTextMessage(contact, null, message, null, null)
            } catch (_: Exception) {
                openSmsComposer(message)
            }
        } else {
            openSmsComposer(message)
        }
    }

    private fun openSmsComposer(message: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$contact")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun notify(id: Int, title: String, text: String, priority: Int) {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val CHANNEL_ID = "stroke_alerts"
        const val CHANNEL_NAME = "Stroke alerts"
        private const val NOTIFICATION_WARNING = 1001
        private const val NOTIFICATION_ALERT = 1002
        private const val WARNING_COOLDOWN_MS = 60_000L
        private const val ALERT_COOLDOWN_MS = 300_000L
        const val SMS_COUNTDOWN_SEC = 15

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alerts when possible stroke signs are detected"
            }
            manager?.createNotificationChannel(channel)
        }
    }
}
