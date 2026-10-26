package com.lielu.todo.ui

import com.lielu.todo.ui.task.extractLinks
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtractLinksTest {
    @Test
    fun findsLinkInText() {
        val text = "Liste : https://kdrive.infomaniak.com/app/share/557607/ea5ac36b-f1d7."
        assertEquals(listOf("https://kdrive.infomaniak.com/app/share/557607/ea5ac36b-f1d7"), extractLinks(text))
    }

    @Test
    fun ignoresDuplicatesAndPlainText() {
        assertEquals(listOf("http://a.fr/x"), extractLinks("http://a.fr/x et (http://a.fr/x)"))
        assertEquals(emptyList(), extractLinks("pas de lien https://"))
    }
}
