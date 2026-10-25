package com.lielu.todo.data.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore by preferencesDataStore(name = "app_settings")
