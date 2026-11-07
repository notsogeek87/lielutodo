package com.lielu.todo.ui.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class UpdatePromptPolicyTest {
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = 1_800_000_000_000L

    @Test
    fun neverShown_checks() {
        assertTrue(UpdatePromptPolicy.shouldCheckOnOpen(now, 0L))
    }

    @Test
    fun shownDuringTheWeek_doesNotCheck() {
        assertFalse(UpdatePromptPolicy.shouldCheckOnOpen(now, now))
        assertFalse(UpdatePromptPolicy.shouldCheckOnOpen(now, now - 6 * day))
        assertFalse(UpdatePromptPolicy.shouldCheckOnOpen(now, now - 7 * day + 1))
    }

    @Test
    fun seventhDay_checksAgain() {
        assertTrue(UpdatePromptPolicy.shouldCheckOnOpen(now, now - 7 * day))
        assertTrue(UpdatePromptPolicy.shouldCheckOnOpen(now, now - 30 * day))
    }

    @Test
    fun clockMovedBack_checks() {
        assertTrue(UpdatePromptPolicy.shouldCheckOnOpen(now, now + day))
    }
}
