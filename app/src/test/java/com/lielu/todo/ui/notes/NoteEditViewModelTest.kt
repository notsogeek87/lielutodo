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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

            // Jamais envoyée au serveur : retirée tout de suite, sans passage par la corbeille Joplin.
            assertNull(notes.getById(note.id))
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

    @Test
    fun uneNoteSansTitreRecoitLaPremiereLigneALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()
            vm.onBodyChanged(TextFieldValue("## Courses de **samedi**\n- pain"))

            vm.finish()

            assertEquals("Courses de samedi", notes.getById(note.id)?.title)
        }

    @Test
    fun annulerPuisRetablirUneActionDeLaBarre() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.save(note.id, "t", "pain")
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.toggleCheckbox()
            assertEquals("- [ ] pain", vm.body.first().text)
            vm.undo()
            assertEquals("pain", vm.body.first().text)
            assertFalse(vm.canUndo.first())
            vm.redo()

            assertEquals("- [ ] pain", vm.body.first().text)
        }

    @Test
    fun laFrappeRegroupeUneRafaleEnUneEtapeDAnnulation() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            var now = 0L
            val vm =
                NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope, clock = { now })
            testDispatcher.scheduler.advanceUntilIdle()
            now = 5_000L
            vm.onBodyChanged(TextFieldValue("a"))
            now += 100
            vm.onBodyChanged(TextFieldValue("ab"))
            now += 5_000
            vm.onBodyChanged(TextFieldValue("ab c"))

            vm.undo()
            assertEquals("ab", vm.body.first().text)
            vm.undo()

            assertEquals("", vm.body.first().text)
        }

    @Test
    fun entreeSurUnElementDeListeProlongeLaListe() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings, backgroundScope)
            testDispatcher.scheduler.advanceUntilIdle()
            vm.onBodyChanged(TextFieldValue("- [x] pain", TextRange(10)))

            vm.onBodyChanged(TextFieldValue("- [x] pain\n", TextRange(11)))

            assertEquals("- [x] pain\n- [ ] ", vm.body.first().text)
            assertEquals(
                17,
                vm.body
                    .first()
                    .selection.start,
            )
        }
}
