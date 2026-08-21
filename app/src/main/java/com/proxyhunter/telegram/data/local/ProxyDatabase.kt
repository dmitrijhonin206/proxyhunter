package com.proxyhunter.telegram.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.proxyhunter.telegram.data.local.entity.CheckResultEntity
import com.proxyhunter.telegram.data.local.entity.GeoCacheEntity
import com.proxyhunter.telegram.data.local.entity.ProxyEntity
import com.proxyhunter.telegram.data.local.entity.ProxySourceEntity

@Database(
    entities = [
        ProxyEntity::class,
        CheckResultEntity::class,
        ProxySourceEntity::class,
        GeoCacheEntity::class,
    ],
    version = 1,
    // false, т.к. room.schemaLocation не настроен в build.gradle.kts — с true Room
    // annotation processor выдаёт build-warning на каждую сборку. Включить обратно
    // и настроить room.schemaLocation, когда появятся реальные миграции схемы.
    exportSchema = false,
)
abstract class ProxyDatabase : RoomDatabase() {
    abstract fun proxyDao(): ProxyDao
    abstract fun checkHistoryDao(): CheckHistoryDao
    abstract fun geoCacheDao(): GeoCacheDao
}
