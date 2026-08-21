package com.proxyhunter.telegram.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.proxyhunter.telegram.data.local.SettingsRepository
import com.proxyhunter.telegram.domain.model.ProxyStatus
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

// Фоновая проверка всего списка прокси. После проверки:
// 1) если прокси, который пользователь пометил как "активный" (используемый сейчас
//    в Telegram), стал нерабочим — уведомляем и предлагаем автопереключение;
// 2) если появились новые рабочие прокси среди ранее непроверенных/неработавших —
//    отдельное уведомление, чтобы не пропустить свежий вариант.
@HiltWorker
class CheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ProxyRepository,
    private val settings: SettingsRepository,
    private val notifier: ProxyHunterNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatching {
        val activeProxyId = settings.getActiveProxyId()
        val statusBefore = activeProxyId?.let { repository.getStatusSnapshot(it) }

        repository.checkAll()

        if (activeProxyId != null && statusBefore == ProxyStatus.WORKING) {
            val statusAfter = repository.getStatusSnapshot(activeProxyId)
            if (statusAfter != ProxyStatus.WORKING) {
                val replacement = repository.findBestWorkingProxy(excludeId = activeProxyId)
                notifier.notifyActiveProxyDown(replacementAvailable = replacement != null)
            }
        }

        Result.success()
    }.getOrElse { if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure() }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "check_periodic_work"
        const val UNIQUE_ONE_TIME_NAME = "check_manual_work"
        private const val MAX_RETRIES = 3
    }
}
