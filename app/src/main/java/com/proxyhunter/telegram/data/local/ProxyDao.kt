package com.proxyhunter.telegram.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.proxyhunter.telegram.data.local.entity.CheckResultEntity
import com.proxyhunter.telegram.data.local.entity.GeoCacheEntity
import com.proxyhunter.telegram.data.local.entity.ProxyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyDao {

    // Реактивный список с фильтрацией — Compose UI подписывается через Flow
    // и автоматически перерисовывается при изменении данных (новая проверка, избранное и т.д.)
    @Query(
        """
        SELECT * FROM proxies
        WHERE (:protocol IS NULL OR protocol = :protocol)
          AND (:country IS NULL OR country = :country)
          AND (:onlyWorking = 0 OR latestStatus = 'WORKING')
          AND (:onlyFavorites = 0 OR isFavorite = 1)
          AND (ip LIKE '%' || :query || '%')
        ORDER BY
          CASE WHEN :sortBy = 'LATENCY' THEN latestLatencyMs END ASC,
          CASE WHEN :sortBy = 'ADDED_DATE' THEN addedAt END DESC,
          CASE WHEN :sortBy = 'COUNTRY' THEN country END ASC
        """
    )
    fun observeProxies(
        protocol: String?,
        country: String?,
        onlyWorking: Boolean,
        onlyFavorites: Boolean,
        query: String,
        sortBy: String,
    ): Flow<List<ProxyEntity>>

    @Query("SELECT * FROM proxies WHERE id = :id")
    suspend fun getById(id: Long): ProxyEntity?

    @Query("SELECT * FROM proxies WHERE id = :id")
    fun observeById(id: Long): Flow<ProxyEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(proxies: List<ProxyEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustom(proxy: ProxyEntity): Long

    @Update
    suspend fun update(proxy: ProxyEntity)

    @Query("UPDATE proxies SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query(
        """
        UPDATE proxies
        SET latestStatus = :status, latestLatencyMs = :latencyMs, lastCheckedAt = :checkedAt
        WHERE id = :proxyId
        """
    )
    suspend fun updateLatestCheck(proxyId: Long, status: String, latencyMs: Int?, checkedAt: Long)

    @Query("SELECT * FROM proxies WHERE latestStatus != 'NOT_CHECKED'")
    suspend fun getAllCheckable(): List<ProxyEntity>

    // Полный снапшот списка — для экспорта (в отличие от observeProxies, не Flow и без
    // фильтров: пользователь экспортирует весь список, а не текущий отфильтрованный вид).
    @Query("SELECT * FROM proxies")
    suspend fun getAll(): List<ProxyEntity>

    @Query(
        """
        SELECT * FROM proxies
        WHERE latestStatus = 'WORKING' AND id != :excludeId
        ORDER BY latestLatencyMs ASC
        LIMIT 1
        """
    )
    suspend fun getBestWorking(excludeId: Long): ProxyEntity?

    @Query("DELETE FROM proxies WHERE isCustom = 0 AND lastCheckedAt < :olderThan")
    suspend fun purgeStale(olderThan: Long)
}

@Dao
interface CheckHistoryDao {
    @Insert
    suspend fun insert(result: CheckResultEntity): Long

    @Query("SELECT * FROM check_results WHERE proxyId = :proxyId ORDER BY checkedAt DESC LIMIT :limit")
    fun observeHistory(proxyId: Long, limit: Int = 50): Flow<List<CheckResultEntity>>
}

@Dao
interface GeoCacheDao {
    @Query("SELECT * FROM geo_cache WHERE ip = :ip")
    suspend fun get(ip: String): GeoCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GeoCacheEntity)
}
