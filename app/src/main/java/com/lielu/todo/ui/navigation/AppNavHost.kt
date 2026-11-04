package com.lielu.todo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lielu.todo.AppContainer
import com.lielu.todo.ui.notes.NoteEditScreen
import com.lielu.todo.ui.notes.NoteEditViewModel
import com.lielu.todo.ui.notes.NotesListScreen
import com.lielu.todo.ui.notes.NotesViewModel
import com.lielu.todo.ui.settings.SettingsScreen
import com.lielu.todo.ui.settings.SettingsViewModel

object AppDestinations {
    const val NOTES = "notes"
    const val NOTE_EDIT = "note_edit/{noteId}"
    const val SETTINGS = "settings"

    fun noteEdit(noteId: String) = "note_edit/$noteId"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = AppDestinations.NOTES) {
        composable(AppDestinations.NOTES) { entry ->
            val viewModel: NotesViewModel =
                viewModel(
                    entry,
                    factory =
                        viewModelFactory {
                            initializer {
                                NotesViewModel(
                                    container.settingsRepository,
                                    container.backupManager,
                                    container.notesRepository,
                                    container.notesSync,
                                )
                            }
                        },
                )
            NotesListScreen(
                viewModel = viewModel,
                onNoteClick = { noteId -> navController.navigate(AppDestinations.noteEdit(noteId)) },
                onSettingsClick = { navController.navigate(AppDestinations.SETTINGS) },
            )
        }

        composable(
            AppDestinations.NOTE_EDIT,
            arguments = listOf(navArgument("noteId") { type = NavType.StringType }),
        ) { entry ->
            val noteId = entry.arguments?.getString("noteId") ?: return@composable
            val viewModel: NoteEditViewModel =
                viewModel(
                    entry,
                    factory =
                        viewModelFactory {
                            initializer {
                                NoteEditViewModel(
                                    noteId,
                                    container.notesRepository,
                                    container.notesSync,
                                    container.settingsRepository,
                                )
                            }
                        },
                )
            NoteEditScreen(
                viewModel = viewModel,
                onBackClick = { navController.popBackStack() },
            )
        }

        composable(AppDestinations.SETTINGS) { entry ->
            val viewModel: SettingsViewModel =
                viewModel(
                    entry,
                    factory =
                        viewModelFactory {
                            initializer {
                                SettingsViewModel(
                                    container.settingsRepository,
                                    container.backupManager,
                                    container.autoBackup,
                                    container.autoBackupScheduler,
                                    container.updateManager,
                                    container.updatesEnabled,
                                    container.notesSync,
                                )
                            }
                        },
                )
            SettingsScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
        }
    }
}
