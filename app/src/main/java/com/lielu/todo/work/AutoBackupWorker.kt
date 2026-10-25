package com.lielu.todo.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lielu.todo.backup.AutoBackupRunner

/** Passage quotidien (voir [AutoBackupScheduler]) : écrit une sauvegarde datée dans le dossier choisi. */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: AutoBackupRunner,
) : CoroutineWorker(context, params) {
    // Un échec (dossier retiré, support absent) est enregistré dans les réglages et affiché dans Paramètres ;
    // le passage suivant retentera. Renvoyer retry() relancerait en boucle une cause qui ne se corrige pas seule.
    override suspend fun doWork(): Result {
        runner.runScheduled()
        return Result.success()
    }

    class Factory(
        private val runner: AutoBackupRunner,
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? =
            if (workerClassName == AutoBackupWorker::class.java.name) {
                AutoBackupWorker(appContext, workerParameters, runner)
            } else {
                null
            }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto_backup"
    }
}
