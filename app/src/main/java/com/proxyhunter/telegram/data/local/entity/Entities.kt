package com.proxyhunter.telegram.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "proxies",
    // Уникальность по ip+port — на неё опирается OnConflictStrategy.IGNORE в ProxyDao.insertAll,
    // чтобы повторный парсинг одного и того же прокси из разных источников не плодил дубликаты.
    indices = [Index(value = ["ip", "port"], unique = true)],
)
data class ProxyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ip: String,
    val port: Int,
    val protocol: String,               // ProxyProtocol.name
    val usernameEncrypted: String? = null,
    val passwordEncrypted: String? = null,   // AES-GCM через Android Keystore, см. CryptoManager
    val mtprotoSecretEncrypted: String? = null,
    val country: String? = null,
    val sourceUrl: String,
    val addedAt: Long,
    val isFavorite: Boolean = false,
    val isCustom: Boolean = false,
    // денормализованные поля для быстрого рендера списка без JOIN на каждый кадр
    @ColumnInfo(defaultValue = "NOT_CHECKED") val latestStatus: String = "NOT_CHECKED",
    val latestLatencyMs: Int? = null,
    val lastCheckedAt: Long? = null,
)

@Entity(
    tableName = "check_results",
    foreignKeys = [
        ForeignKey(
            entity = ProxyEntity::class,
            parentColumns = ["id"],
            childColumns = ["proxyId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("proxyId")],
)
data class CheckResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val proxyId: Long,
    val checkedAt: Long,
    val status: String,
    val latencyMs: Int?,
    val telegramApiReachable: Boolean,
    val errorMessage: String? = null,
)

@Entity(tableName = "sources")
data class ProxySourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val enabled: Boolean = true,
    val lastFetchedAt: Long? = null,
)

// Кэш результатов определения страны по IP (GeoIpResolver), чтобы не резолвить
// повторно один и тот же IP при каждом парсинге/отображении.
@Entity(tableName = "geo_cache")
data class GeoCacheEntity(
    @PrimaryKey val ip: String,
    val countryCode: String?,
    val resolvedAt: Long,
)
