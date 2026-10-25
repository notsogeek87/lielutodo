package com.lielu.todo.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lielu.todo.data.settings.AutoBackupSettings
import java.util.concurrent.TimeUnit

/** Planifie ou annule le passage quotidien de [AutoBackupWorker] selon les réglages courants. */
class AutoBackupScheduler(
    private val context: Context,
) {
    fun apply(settings: AutoBackupSettings) {
        val workManager = WorkManager.getInstance(context)
        if (settings.isActive) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build()
            // UPDATE : réapplique sans repousser l'échéance à chaque lancement de l'app.
            workManager.enqueueUniquePeriodicWork(
                AutoBackupWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        } else {
            workManager.cancelUniqueWork(AutoBackupWorker.UNIQUE_WORK_NAME)
        }
    }
}
