package com.hackmit.app.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArmAngleParserTest {

    @Test
    fun parsesAngLine() {
        val sample = parseArmAngleLine("ANG,L,45.2,R,43.1,DIFF,2.1", timestampMs = 1)
        assertNotNull(sample)
        assertEquals(45.2f, sample!!.leftDeg, 0.01f)
        assertEquals(43.1f, sample.rightDeg, 0.01f)
        assertEquals(2.1f, sample.diffDeg, 0.01f)
    }

    @Test
    fun fillsDiffWhenMissing() {
        val sample = parseArmAngleLine("ANG,L,50.0,R,40.0,DIFF,nan", timestampMs = 1)!!
        assertEquals(10.0f, sample.diffDeg, 0.01f)
    }

    @Test
    fun parsesHumanReadableLine() {
        val sample = parseArmAngleLine("L 12.5   R 10.0", timestampMs = 1)!!
        assertEquals(12.5f, sample.leftDeg, 0.01f)
        assertEquals(10.0f, sample.rightDeg, 0.01f)
        assertEquals(2.5f, sample.diffDeg, 0.01f)
    }

    @Test
    fun ignoresDebugLines() {
        assertNull(parseArmAngleLine("LEFT ready"))
        assertNull(parseArmAngleLine("Match  L 62.1  R 61.5  next cue in 1.00 min"))
    }

    @Test
    fun absDiffAgreesWithFirmware() {
        val left = 62.1f
        val right = 61.5f
        val sample = parseArmAngleLine("ANG,L,$left,R,$right,DIFF,${abs(left - right)}")!!
        assertTrue(sample.diffDeg <= 5.0f)
    }
}
