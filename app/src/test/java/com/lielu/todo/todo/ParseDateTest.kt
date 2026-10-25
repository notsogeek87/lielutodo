package com.lielu.todo.todo

import com.lielu.todo.data.model.lastUnreadableDate
import com.lielu.todo.data.model.parseDate
import com.lielu.todo.data.model.resetUnreadableDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ParseDateTest {
    private val noon = Instant.parse("2026-10-25T12:00:00Z")

    @Test
    fun readsRfc3339WithZuluAndOffsets() {
        assertEquals(noon, parseDate("2026-10-25T12:00:00Z"))
        assertEquals(noon, parseDate("2026-10-25T14:00:00+02:00"))
        assertEquals(noon.plusNanos(123_456_789), parseDate("2026-10-25T14:00:00.123456789+02:00"))
    }

    @Test
    fun readsVariantsOtherServersSend() {
        assertEquals(noon, parseDate("2026-10-25T14:00:00+0200"))
        assertEquals(noon, parseDate("1792929600"))
        assertEquals(noon, parseDate("1792929600000"))
        assertEquals(noon, parseDate("2026-10-25T12:00:00Z[UTC]"))
    }

    @Test
    fun noDateMeansNull() {
        resetUnreadableDate()
        assertNull(parseDate(""))
        assertNull(parseDate(null))
        assertNull(parseDate("0001-01-01T00:00:00Z"))
        assertNull(lastUnreadableDate)
    }

    @Test
    fun unreadableValueIsRememberedForTheDiagnostic() {
        resetUnreadableDate()
        assertNull(parseDate("demain-ish"))
        assertEquals("demain-ish", lastUnreadableDate)
        resetUnreadableDate()
        assertNull(lastUnreadableDate)
    }
}
