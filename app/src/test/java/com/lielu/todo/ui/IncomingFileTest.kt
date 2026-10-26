package com.lielu.todo.ui

import com.lielu.todo.ui.common.acceptsIncomingUri
import com.lielu.todo.ui.common.readAtMost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class IncomingFileTest {
    private val own = "com.lielu.todo.files"

    @Test
    fun onlyContentFromAnotherAppIsAcceptedFromAShare() {
        assertTrue(acceptsIncomingUri("content", "com.android.providers.media", own, allowOwn = false))
        // Une app tierce ne doit pas faire joindre (puis envoyer) un fichier privé de l'app.
        assertFalse(acceptsIncomingUri("file", null, own, allowOwn = false))
        assertFalse(acceptsIncomingUri("content", own, own, allowOwn = false))
        // La photo prise par l'app elle-même passe par son FileProvider.
        assertTrue(acceptsIncomingUri("content", own, own, allowOwn = true))
        assertFalse(acceptsIncomingUri("file", null, own, allowOwn = true))
    }

    @Test
    fun readingStopsAsSoonAsTheLimitIsExceeded() {
        val data = ByteArray(200_000) { it.toByte() }
        assertArrayEquals(data, readAtMost(ByteArrayInputStream(data), 200_000))
        assertNull(readAtMost(ByteArrayInputStream(data), 199_999))
        assertArrayEquals(ByteArray(0), readAtMost(ByteArrayInputStream(ByteArray(0)), 10))
    }
}
