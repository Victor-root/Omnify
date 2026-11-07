package com.looker.droidify.work

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class UpdateCheckTimingTest {

    private val now = 100.days.inWholeMilliseconds

    private fun hoursAgo(hours: Int) = now - hours.hours.inWholeMilliseconds

    @Test
    fun `a check is due once the chosen frequency has gone by`() {
        assertFalse(isCheckDue(hoursAgo(11), 12.hours, now))
        assertTrue(isCheckDue(hoursAgo(12), 12.hours, now))
        assertFalse(isCheckDue(hoursAgo(12), 1.days, now))
    }

    @Test
    fun `a device with no finished check is owed one`() {
        assertTrue(isCheckDue(null, 12.hours, now))
    }

    @Test
    fun `background checks count as stopped after two missed runs and a margin`() {
        assertFalse(isBackgroundStalled(hoursAgo(23), 6.hours, now))
        assertTrue(isBackgroundStalled(hoursAgo(24), 6.hours, now))
        assertFalse(isBackgroundStalled(hoursAgo(35), 12.hours, now))
        assertTrue(isBackgroundStalled(hoursAgo(36), 12.hours, now))
        assertFalse(isBackgroundStalled(hoursAgo(59), 1.days, now))
        assertTrue(isBackgroundStalled(hoursAgo(60), 1.days, now))
    }
}
