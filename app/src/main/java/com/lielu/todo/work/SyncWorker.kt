package com.lielu.todo.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lielu.todo.data.api.SessionStore
import com.lielu.todo.data.todo.TodoRepository
import com.lielu.todo.widget.refreshWidgets
import java.util.concurrent.TimeUnit

/**
 * Synchronisation périodique en arrière-plan : récupère les tâches créées ailleurs (web, autre appareil) pour que
 * les rappels et les widgets restent justes sans ouvrir l'app, et envoie les écritures restées en attente.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: TodoRepository,
    private val session: SessionStore,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (session.token == null) return Result.success()
        repository.refresh()
        repository.saveNow()
        runCatching { refreshWidgets(applicationContext) }
        // Serveur injoignable : ce n'est pas un échec à rejouer en boucle, le prochain passage réessaiera.
        return Result.success()
    }

    class Factory(
        private val repository: TodoRepository,
        private val session: SessionStore,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == SyncWorker::class.java.name) {
                SyncWorker(appContext, workerParameters, repository, session)
            } else {
                null
            }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "todo_sync"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
