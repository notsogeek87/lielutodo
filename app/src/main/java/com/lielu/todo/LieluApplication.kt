package com.lielu.todo

import android.app.Application
import androidx.work.Configuration

class LieluApplication :
    Application(),
    Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduleAutoBackup()
        container.start()
    }

    // Initialisation à la demande : WorkManager lit ceci au premier WorkManager.getInstance(context), ce qui lui
    // permet d'utiliser un WorkerFactory construit avec les dépendances de AppContainer. L'initialisation
    // androidx.startup par défaut est désactivée dans le manifest, condition de cette surcharge.
    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(container.workerFactory)
                .build()
}
