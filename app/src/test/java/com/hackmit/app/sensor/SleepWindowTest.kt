package com.hackmit.app.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepWindowTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun overnightWindowCoversMidnight() {
        val window = SleepWindow(enabled = true, start = "22:00", end = "07:00")
        assertTrue(window.contains(at(22)))
        assertTrue(window.contains(at(23, 30)))
        assertTrue(window.contains(at(3)))
        assertTrue(window.contains(at(6, 59)))
        assertFalse(window.contains(at(7)))
        assertFalse(window.contains(at(12)))
        assertFalse(window.contains(at(21, 59)))
    }

    @Test
    fun daytimeWindowStaysWithinTheDay() {
        val window = SleepWindow(enabled = true, start = "13:00", end = "15:30")
        assertTrue(window.contains(at(13)))
        assertTrue(window.contains(at(15, 29)))
        assertFalse(window.contains(at(15, 30)))
        assertFalse(window.contains(at(2)))
    }

    @Test
    fun disabledOrDegenerateWindowNeverSleeps() {
        assertFalse(SleepWindow(enabled = false, start = "22:00", end = "07:00").contains(at(23)))
        assertFalse(SleepWindow(enabled = true, start = "22:00", end = "22:00").contains(at(22)))
        assertFalse(SleepWindow(enabled = true, start = "nope", end = "07:00").contains(at(23)))
    }

    @Test
    fun commandCarriesNormalisedTimes() {
        assertEquals(
            "SETSLEEP,22:00,07:00",
            SleepWindow(enabled = true, start = "2200", end = "7:00").command(),
        )
        assertEquals("SETSLEEP,OFF", SleepWindow(enabled = false).command())
        assertEquals("SETSLEEP,OFF", SleepWindow(enabled = true, start = "25:00", end = "07:00").command())
    }

    @Test
    fun parsesBothTimeForms() {
        assertEquals(1320, parseHhMm("22:00"))
        assertEquals(1320, parseHhMm("2200"))
        assertEquals(425, parseHhMm("7:5"))
        assertNull(parseHhMm("24:00"))
        assertNull(parseHhMm("22:60"))
        assertNull(parseHhMm("later"))
        assertNull(parseHhMm(null))
    }

    @Test
    fun formatsMinutesAsWallClock() {
        assertEquals("00:00", formatHhMm(0))
        assertEquals("07:05", formatHhMm(425))
        assertEquals("23:59", formatHhMm(1439))
    }
}
