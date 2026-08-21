package com.proxyhunter.telegram.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

// Единая точка постановки задач в WorkManager: периодические (фоновое расписание,
// интервал берётся из SettingsRepository) и разовые (ручной запуск из UI кнопками
// "Обновить" / "Проверить все"). ExistingPeriodicWorkPolicy.UPDATE позволяет менять
// интервал в настройках без дублирования работ.
@Singleton
class WorkScheduler @Inject constructor(private val workManager: WorkManager) {

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodicParsing(intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<ParsingWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            ParsingWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun schedulePeriodicCheck(intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<CheckWorker>(intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            CheckWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun runParsingNow() {
        val request = OneTimeWorkRequestBuilder<ParsingWorker>()
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(ParsingWorker.UNIQUE_ONE_TIME_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun runCheckNow() {
        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setConstraints(networkConstraints)
            .build()
        workManager.enqueueUniqueWork(CheckWorker.UNIQUE_ONE_TIME_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(ParsingWorker.UNIQUE_PERIODIC_NAME)
        workManager.cancelUniqueWork(CheckWorker.UNIQUE_PERIODIC_NAME)
    }
}
