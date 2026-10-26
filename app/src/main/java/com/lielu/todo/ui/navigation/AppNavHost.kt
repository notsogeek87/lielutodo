package com.lielu.todo.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.lielu.todo.ui.home.ActivityScreen
import com.lielu.todo.ui.home.HomeScreen
import com.lielu.todo.ui.home.KanbanScreen
import com.lielu.todo.ui.home.ListTarget
import com.lielu.todo.ui.home.NotificationsScreen
import com.lielu.todo.ui.home.SearchScreen
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
    const val FILTER = "filter/{id}"
    const val FAVORITES = "favorites"
    const val COMPLETED = "completed"
    const val NOTIFICATIONS = "notifications"
    const val ACTIVITY = "activity"
    const val KANBAN = "kanban/{id}"
    const val SEARCH = "search"
    const val TASK = "task/{id}"

    fun project(id: Long) = "project/$id"

    fun label(id: Long) = "label/$id"

    fun kanban(id: Long) = "kanban/$id"

    fun filter(id: Long) = "filter/$id"

    fun task(id: Long) = "task/$id"
}

/** Sur grand écran (Fold déplié) les écrans plein format ne s'étirent pas sur toute la largeur. */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight()) { content() }
    }
}

private val idArgument = listOf(navArgument("id") { type = NavType.LongType })

@Composable
fun AppNavHost(
    container: AppContainer,
    openTaskId: Long? = null,
    onTaskOpened: () -> Unit = {},
) {
    val loggedIn by container.session.loggedIn.collectAsStateWithLifecycle()
    if (!loggedIn) {
        LoginScreen(container.session, container.vikunjaClient, container.todoRepository)
        return
    }

    val navController = rememberNavController()
    val repository = container.todoRepository

    // Notification de rappel ou ligne de widget : on ouvre la tâche par-dessus l'écran d'accueil.
    LaunchedEffect(openTaskId) {
        if (openTaskId != null) {
            navController.navigate(AppDestinations.task(openTaskId))
            onTaskOpened()
        }
    }

    NavHost(navController = navController, startDestination = AppDestinations.HOME) {
        composable(AppDestinations.HOME) {
            HomeScreen(
                repository = repository,
                userName = container.session.username,
                serverLabel = container.session.baseUrl,
                onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                onOpenProject = { navController.navigate(AppDestinations.project(it)) },
                onOpenLabel = { navController.navigate(AppDestinations.label(it)) },
                onOpenFilter = { navController.navigate(AppDestinations.filter(it)) },
                onOpenFavorites = { navController.navigate(AppDestinations.FAVORITES) },
                onOpenCompleted = { navController.navigate(AppDestinations.COMPLETED) },
                onSearch = { navController.navigate(AppDestinations.SEARCH) },
                reminderScheduler = container.reminderScheduler,
                prefs = container.prefs,
                onOpenNotifications = { navController.navigate(AppDestinations.NOTIFICATIONS) },
                onOpenActivity = { navController.navigate(AppDestinations.ACTIVITY) },
                onSettings = { navController.navigate(AppDestinations.SETTINGS) },
                onLogout = { container.session.clear() },
            )
        }

        composable(AppDestinations.PROJECT, arguments = idArgument) { entry ->
            Centered {
                TaskListScreen(
                    repository = repository,
                    target = ListTarget(projectId = entry.arguments?.getLong("id")),
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                    onOpenKanban = { navController.navigate(AppDestinations.kanban(it)) },
                )
            }
        }

        composable(AppDestinations.LABEL, arguments = idArgument) { entry ->
            Centered {
                TaskListScreen(
                    repository = repository,
                    target = ListTarget(labelId = entry.arguments?.getLong("id")),
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.FILTER, arguments = idArgument) { entry ->
            Centered {
                TaskListScreen(
                    repository = repository,
                    target = ListTarget(filterId = entry.arguments?.getLong("id")),
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.FAVORITES) {
            Centered {
                TaskListScreen(
                    repository = repository,
                    target = ListTarget(favorites = true),
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.COMPLETED) {
            Centered {
                TaskListScreen(
                    repository = repository,
                    target = ListTarget(completed = true),
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.NOTIFICATIONS) {
            Centered {
                NotificationsScreen(
                    repository = repository,
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.ACTIVITY) {
            Centered {
                ActivityScreen(
                    repository = repository,
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.KANBAN, arguments = idArgument) { entry ->
            Centered {
                KanbanScreen(
                    repository = repository,
                    projectId = entry.arguments?.getLong("id") ?: 0L,
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.SEARCH) {
            Centered {
                SearchScreen(
                    repository = repository,
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.TASK, arguments = idArgument) { entry ->
            Centered {
                TaskDetailScreen(
                    repository = repository,
                    taskId = entry.arguments?.getLong("id") ?: 0L,
                    onOpenTask = { navController.navigate(AppDestinations.task(it)) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(AppDestinations.SETTINGS) { entry ->
            Centered {
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
}
