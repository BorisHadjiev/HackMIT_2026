package com.hackmit.app.sensor

/**
 * Quiet hours for the board's buzzers, as local wall-clock times.
 *
 * A [start] later than [end] is an overnight window (22:00 to 07:00). The MCU has no
 * clock, so the window is enforced on the UNO Q's Linux bridge, which refuses to cue
 * while it is inside the window; the phone only owns the setting.
 */
data class SleepWindow(
    val enabled: Boolean = false,
    val start: String = "22:00",
    val end: String = "07:00",
) {
    val startMinutes: Int? get() = parseHhMm(start)

    val endMinutes: Int? get() = parseHhMm(end)

    /** A zero-length window would silently never fire, so it does not count as valid. */
    val valid: Boolean
        get() {
            val from = startMinutes ?: return false
            val to = endMinutes ?: return false
            return from != to
        }

    /** Board command: `SETSLEEP,22:00,07:00`, or `SETSLEEP,OFF` when quiet hours are off. */
    fun command(): String = if (enabled && valid) {
        "SETSLEEP,${formatHhMm(startMinutes!!)},${formatHhMm(endMinutes!!)}"
    } else {
        "SETSLEEP,OFF"
    }

    /** True when [minuteOfDay] falls inside the window, including overnight windows. */
    fun contains(minuteOfDay: Int): Boolean {
        if (!enabled || !valid) return false
        val from = startMinutes ?: return false
        val to = endMinutes ?: return false
        return if (from < to) minuteOfDay in from until to else minuteOfDay >= from || minuteOfDay < to
    }

    val label: String
        get() = when {
            !enabled -> "Off"
            valid -> "${formatHhMm(startMinutes!!)} – ${formatHhMm(endMinutes!!)}"
            else -> "Invalid time"
        }
}

/** Accepts `22:00`, `2200` and `7:5`; returns minutes past midnight, or null if unusable. */
fun parseHhMm(text: String?): Int? {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val hourPart: String
    val minutePart: String
    if (trimmed.contains(':')) {
        hourPart = trimmed.substringBefore(':')
        minutePart = trimmed.substringAfter(':')
    } else if (trimmed.length in 3..4 && trimmed.all { it.isDigit() }) {
        hourPart = trimmed.dropLast(2)
        minutePart = trimmed.takeLast(2)
    } else {
        return null
    }
    val hour = hourPart.trim().toIntOrNull() ?: return null
    val minute = minutePart.trim().toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

fun formatHhMm(minuteOfDay: Int): String {
    val wrapped = ((minuteOfDay % 1440) + 1440) % 1440
    return "%02d:%02d".format(wrapped / 60, wrapped % 60)
}
