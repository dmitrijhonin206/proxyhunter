package com.proxyhunter.telegram

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ProxyHunterApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Без этого HiltWorker'ы (ParsingWorker, CheckWorker) не смогут получить
    // зависимости через конструкторный DI — WorkManager по умолчанию создаёт
    // Worker'ы через reflection с пустым конструктором.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
