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
import com.lielu.todo.data.notes.NotesSync
import java.util.concurrent.TimeUnit

/**
 * Synchronisation périodique des notes avec Joplin Server en arrière-plan, comme [SyncWorker] pour les
 * tâches : récupère les notes créées ailleurs et pousse les modifications locales en attente, sans que
 * l'utilisateur ait à ouvrir l'écran des notes.
 */
class NotesSyncWorker(
    context: Context,
    params: WorkerParameters,
    private val notesSync: NotesSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { notesSync.syncNow() }
        // Serveur injoignable : pas un échec à rejouer en boucle, le prochain passage réessaiera.
        return Result.success()
    }

    class Factory(
        private val notesSync: NotesSync,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == NotesSyncWorker::class.java.name) {
                NotesSyncWorker(appContext, workerParameters, notesSync)
            } else {
                null
            }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "notes_sync"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<NotesSyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
