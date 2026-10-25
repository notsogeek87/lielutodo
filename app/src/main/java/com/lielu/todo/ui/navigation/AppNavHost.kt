package com.lielu.todo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lielu.todo.AppContainer
import com.lielu.todo.ui.home.HomeScreen
import com.lielu.todo.ui.home.TaskListScreen
import com.lielu.todo.ui.login.LoginScreen
import com.lielu.todo.ui.settings.SettingsScreen
import com.lielu.todo.ui.settings.SettingsViewModel
import com.lielu.todo.ui.task.TaskDetailScreen

object AppDestinations {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PROJECT = "project/{id}"
    const val LABEL = "label/{id}"
    const val TASK = "task/{id}"

    fun project(id: Long) = "project/$id"

    fun label(id: Long) = "label/$id"

    fun task(id: Long) = "task/$id"
}

private val idArgument = listOf(navArgument("id") { type = NavType.LongType })

@Composable
fun AppNavHost(container: AppContainer) {
    val loggedIn by container.session.loggedIn.collectAsStateWithLifecycle()
    if (!loggedIn) {
        LoginScreen(container.session, container.vikunjaClient, container.todoRepository)
        return
    }

    val navController = rememberNavController()
    val repository = container.todoRepository

    NavHost(navController = navController, startDestination = AppDestinations.HOME) {
        composable(AppDestinations.HOME) {
            HomeScreen(
                repository = repository,
                serverLabel = "${container.session.username} · ${container.session.baseUrl}",
                onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                onOpenProject = { navController.navigate(AppDestinations.project(it)) },
                onOpenLabel = { navController.navigate(AppDestinations.label(it)) },
                onSettings = { navController.navigate(AppDestinations.SETTINGS) },
                onLogout = { container.session.clear() },
            )
        }

        composable(AppDestinations.PROJECT, arguments = idArgument) { entry ->
            TaskListScreen(
                repository = repository,
                projectId = entry.arguments?.getLong("id"),
                labelId = null,
                onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(AppDestinations.LABEL, arguments = idArgument) { entry ->
            TaskListScreen(
                repository = repository,
                projectId = null,
                labelId = entry.arguments?.getLong("id"),
                onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(AppDestinations.TASK, arguments = idArgument) { entry ->
            TaskDetailScreen(
                repository = repository,
                taskId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
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
                                )
                            }
                        },
                )
            SettingsScreen(viewModel = viewModel, onBackClick = { navController.popBackStack() })
        }
    }
}
