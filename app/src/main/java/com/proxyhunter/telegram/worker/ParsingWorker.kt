package com.proxyhunter.telegram.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

// Фоновый парсинг источников. Запускается по расписанию (см. WorkScheduler) или вручную
// из UI ("Обновить"). CoroutineWorker + @HiltWorker даёт репозиторию через DI, минуя
// ручную инициализацию зависимостей внутри Worker (Android создаёт Worker сам, поэтому
// обычный конструкторный DI недоступен без HiltWorkerFactory).
@HiltWorker
class ParsingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ProxyRepository,
    private val notifier: ProxyHunterNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val newCount = repository.refreshFromSources()
        if (newCount > 0) {
            notifier.notifyParsingFinished(newCount)
        }
        Result.success()
    }.getOrElse { throwable ->
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "parsing_periodic_work"
        const val UNIQUE_ONE_TIME_NAME = "parsing_manual_work"
        private const val MAX_RETRIES = 3
    }
}
