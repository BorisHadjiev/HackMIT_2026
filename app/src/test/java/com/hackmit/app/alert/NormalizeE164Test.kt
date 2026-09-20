package com.hackmit.app.alert

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeE164Test {

    @Test
    fun `keeps clean E164`() {
        assertEquals("+13173709781", normalizeE164("+13173709781"))
    }

    @Test
    fun `strips separators`() {
        assertEquals("+13173709781", normalizeE164("(317) 370-9781"))
    }

    @Test
    fun `prepends US country code for 10 digits`() {
        assertEquals("+13173709781", normalizeE164("3173709781"))
    }

    @Test
    fun `handles 11 digit US number without plus`() {
        assertEquals("+13173709781", normalizeE164("13173709781"))
    }

    @Test
    fun `converts 00 international prefix`() {
        assertEquals("+13173709781", normalizeE164("0013173709781"))
    }

    @Test
    fun `keeps non-US country code`() {
        assertEquals("+442071234567", normalizeE164("+44 20 7123 4567"))
    }

    @Test
    fun `returns input when nothing usable`() {
        assertEquals("abc", normalizeE164("abc"))
    }
}