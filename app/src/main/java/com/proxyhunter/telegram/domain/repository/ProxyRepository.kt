package com.proxyhunter.telegram.domain.repository

import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyFilter
import com.proxyhunter.telegram.domain.model.ProxyStatus
import kotlinx.coroutines.flow.Flow

interface ProxyRepository {
    fun observeProxies(filter: ProxyFilter): Flow<List<Proxy>>
    suspend fun refreshFromSources(): Int
    suspend fun checkAll()
    suspend fun checkSingle(proxyId: Long)
    suspend fun toggleFavorite(proxyId: Long, isFavorite: Boolean)
    suspend fun addCustomProxy(proxy: Proxy)

    // Используются CheckWorker для логики автопереключения при падении активного прокси.
    suspend fun getStatusSnapshot(proxyId: Long): ProxyStatus?
    suspend fun findBestWorkingProxy(excludeId: Long?): Proxy?

    // Для экрана деталей: карточка прокси и график истории проверок реактивно
    // обновляются, пока пользователь на экране (например, после ручного "Проверить").
    fun observeProxy(proxyId: Long): Flow<Proxy?>
    fun observeCheckHistory(proxyId: Long): Flow<List<com.proxyhunter.telegram.domain.model.CheckResult>>

    // Экспорт/импорт списка (JSON/CSV) — см. ProxyExportSerializer для формата.
    suspend fun getAllProxies(): List<Proxy>
    suspend fun importProxies(proxies: List<Proxy>): Int
}
