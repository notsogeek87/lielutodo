package com.lielu.todo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lielu.todo.data.settings.ThemeMode
import com.lielu.todo.ui.navigation.AppNavHost
import com.lielu.todo.ui.theme.AppTheme
import com.lielu.todo.ui.update.AppUpdateViewModel
import com.lielu.todo.ui.update.UpdatePrompt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as LieluApplication).container

        setContent {
            // ThemeMode.SYSTEM (valeur initiale tant que le DataStore n'a pas livré la sienne) retombe sur
            // isSystemInDarkTheme() : jamais de flash clair/sombre incorrect pendant la lecture initiale.
            val themeMode by container.settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val darkTheme =
                when (themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                }
            AppTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost(container = container)
                    val updateViewModel: AppUpdateViewModel =
                        viewModel(
                            factory =
                                viewModelFactory {
                                    initializer {
                                        AppUpdateViewModel(container.updateManager, container.updatesEnabled)
                                    }
                                },
                        )
                    UpdatePrompt(updateViewModel)
                }
            }
        }
    }
}
