package com.lielu.todo.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lielu.todo.AppContainer
import com.lielu.todo.ui.init.InitScreen
import com.lielu.todo.ui.init.InitViewModel
import com.lielu.todo.ui.settings.SettingsScreen
import com.lielu.todo.ui.settings.SettingsViewModel

object AppDestinations {
    const val INIT = "init"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = AppDestinations.INIT) {
        composable(AppDestinations.INIT) { entry ->
            val viewModel: InitViewModel =
                viewModel(
                    entry,
                    factory =
                        viewModelFactory {
                            initializer { InitViewModel(container.settingsRepository, container.backupManager) }
                        },
                )
            InitScreen(
                viewModel = viewModel,
                onSettingsClick = { navController.navigate(AppDestinations.SETTINGS) },
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
