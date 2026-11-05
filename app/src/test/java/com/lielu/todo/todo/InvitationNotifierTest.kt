package com.lielu.todo.todo

import com.lielu.todo.data.joplin.ShareInvitation
import com.lielu.todo.data.joplin.ShareStatus
import com.lielu.todo.reminder.newInvitations
import org.junit.Assert.assertEquals
import org.junit.Test

class InvitationNotifierTest {
    private fun invitation(id: String) = ShareInvitation(id, ShareStatus.PENDING, "f", "Admin", "admin@lielu.eu")

    @Test
    fun seulesLesInvitationsJamaisSignaleesSontNotifiees() {
        val fresh = newInvitations(listOf(invitation("a"), invitation("b")), alreadyNotified = setOf("a"))

        assertEquals(listOf("b"), fresh.map { it.shareUserId })
    }
}
