package com.lielu.todo.ui.notes

import com.lielu.todo.data.notes.Note
import com.lielu.todo.data.notes.NotesSync
import com.lielu.todo.testutil.FakeNotesRepository
import com.lielu.todo.testutil.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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

    private fun syncStub(settings: FakeSettingsRepository, notes: FakeNotesRepository) =
        NotesSync(settings, notes, apiFactory = { error("pas de réseau dans les tests") })

    @Test
    fun noteVideEstSupprimeeALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.onCleared()
            testDispatcher.scheduler.advanceUntilIdle()

            assertNull(notes.getById(note.id))
        }

    @Test
    fun noteAvecContenuEstConserveeALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.save(note.id, "Titre", "Contenu")
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.onCleared()
            testDispatcher.scheduler.advanceUntilIdle()

            assertNotNull(notes.getById(note.id))
        }

    @Test
    fun noteAvecTitreSeulEstConserveeALaFermeture() =
        runTest {
            val settings = FakeSettingsRepository()
            val notes = FakeNotesRepository()
            val note = notes.create()
            notes.upsert(Note(id = note.id, title = "Titre seul", body = "", userUpdatedTime = 1L, dirty = true))
            val vm = NoteEditViewModel(note.id, notes, syncStub(settings, notes), settings)
            testDispatcher.scheduler.advanceUntilIdle()

            vm.onCleared()
            testDispatcher.scheduler.advanceUntilIdle()

            assertNotNull(notes.getById(note.id))
        }
}
