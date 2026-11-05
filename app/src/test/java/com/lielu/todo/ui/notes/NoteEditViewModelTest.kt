package com.lielu.todo.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.lielu.todo.data.notes.Note
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.testutil.FakeNotesRepository
import com.lielu.todo.testutil.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun syncStub(
        settings: FakeSettingsRepository,
        notes: FakeNotesRepository,
    ) = NotesSync(settings, notes, apiFactory = { error("pas de réseau dans les tests") })

    @Test
    fun noteVideEstSupprimeeALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.finish()

            assertTrue(notes.getById(note.id)?.deleted == true)
        }

    @Test
    fun noteAvecContenuEstConserveeALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.save(note.id, "Titre", "Contenu")
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.finish()

            assertNotNull(notes.getById(note.id))
        }

    @Test
    fun noteAvecTitreSeulEstConserveeALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.upsert(Note(id = note.id, title = "Titre seul", body = "", userUpdatedTime = 1L, dirty = true))
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.finish()

            assertNotNull(notes.getById(note.id))
        }

    @Test
    fun wrapSelectionEntoureLaSelection() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.upsert(
                Note(
                    id = note.id,
                    title = "t",
                    body = "le mot important ici",
                    userUpdatedTime = 1L,
                    dirty = true,
                ),
            )
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()
            vm.onBodyChanged(TextFieldValue("le mot important ici", TextRange(7, 16)))

            vm.wrapSelection("**", "**")

            val bodyValue = vm.body.first()
            assertEquals("le mot **important** ici", bodyValue.text)
        }

    @Test
    fun wrapSelectionSansSelectionInsereAuCurseur() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.upsert(Note(id = note.id, title = "t", body = "abc", userUpdatedTime = 1L, dirty = true))
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()
            vm.onBodyChanged(TextFieldValue("abc", TextRange(1)))

            vm.wrapSelection("**", "**")

            val bodyValue = vm.body.first()
            assertEquals("a****bc", bodyValue.text)
            assertEquals(3, bodyValue.selection.start)
        }

    @Test
    fun insertSnippetAuMilieuDeLignePasseALaLigne() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.upsert(Note(id = note.id, title = "t", body = "avant apres", userUpdatedTime = 1L, dirty = true))
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()
            vm.onBodyChanged(TextFieldValue("avant apres", TextRange(5)))

            vm.insertSnippet("- élément")

            val bodyValue = vm.body.first()
            assertEquals("avant\n- élément apres", bodyValue.text)
        }

    @Test
    fun insertSnippetEnDebutDeLigneResteSurLaLigne() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.upsert(Note(id = note.id, title = "t", body = "ligne1\nligne2", userUpdatedTime = 1L, dirty = true))
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()
            vm.onBodyChanged(TextFieldValue("ligne1\nligne2", TextRange(7)))

            vm.insertSnippet("- élément")

            val bodyValue = vm.body.first()
            assertEquals("ligne1\n- élémentligne2", bodyValue.text)
        }

    @Test
    fun laDerniereFrappeEstEnregistreeALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.save(note.id, "Titre", "début")
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()
            vm.onBodyChanged(TextFieldValue("début et fin"))

            // Fermeture avant la fin du délai d'enregistrement automatique.
            vm.finish()

            assertEquals("début et fin", notes.getById(note.id)?.body)
        }
}
