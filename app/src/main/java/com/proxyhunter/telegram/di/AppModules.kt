package com.proxyhunter.telegram.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.proxyhunter.telegram.data.local.CheckHistoryDao
import com.proxyhunter.telegram.data.local.GeoCacheDao
import com.proxyhunter.telegram.data.local.ProxyDao
import com.proxyhunter.telegram.data.local.ProxyDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ProxyDatabase =
        Room.databaseBuilder(context, ProxyDatabase::class.java, "proxyhunter.db")
            // fallbackToDestructiveMigration оставлен для MVP; перед релизом со сменой схемы
            // заменить на явные Migration-объекты, чтобы не терять избранное/историю пользователей.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideProxyDao(db: ProxyDatabase): ProxyDao = db.proxyDao()

    @Provides
    fun provideCheckHistoryDao(db: ProxyDatabase): CheckHistoryDao = db.checkHistoryDao()

    @Provides
    fun provideGeoCacheDao(db: ProxyDatabase): GeoCacheDao = db.geoCacheDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    // ProxySourceRegistry и ProxyChecker НЕ провайдятся здесь явно — у обоих уже есть
    // @Inject-конструктор (см. ProxySource.kt / ProxyChecker.kt), Hilt строит их сам.
    // Дублирующий @Provides для класса с @Inject-конструктором — ошибка компиляции
    // Dagger ("... is bound multiple times"), а не безобидная избыточность.
}

@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
