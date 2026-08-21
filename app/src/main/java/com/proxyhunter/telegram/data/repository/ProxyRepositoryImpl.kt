package com.proxyhunter.telegram.data.repository

import com.proxyhunter.telegram.data.checker.ProxyChecker
import com.proxyhunter.telegram.data.local.CheckHistoryDao
import com.proxyhunter.telegram.data.local.ProxyDao
import com.proxyhunter.telegram.data.local.entity.CheckResultEntity
import com.proxyhunter.telegram.data.local.entity.ProxyEntity
import com.proxyhunter.telegram.data.remote.geoip.GeoIpResolver
import com.proxyhunter.telegram.data.remote.source.ProxySourceRegistry
import com.proxyhunter.telegram.domain.model.CheckResult
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyFilter
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.model.ProxyStatus
import com.proxyhunter.telegram.domain.repository.ProxyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ProxyRepositoryImpl @Inject constructor(
    private val dao: ProxyDao,
    private val checkHistoryDao: CheckHistoryDao,
    private val sourceRegistry: ProxySourceRegistry,
    private val checker: ProxyChecker,
    private val geoIpResolver: GeoIpResolver,
    private val crypto: com.proxyhunter.telegram.data.local.CryptoManager,
) : ProxyRepository {

    override fun observeProxies(filter: ProxyFilter): Flow<List<Proxy>> =
        dao.observeProxies(
            protocol = filter.protocol?.name,
            country = filter.country,
            onlyWorking = filter.onlyWorking,
            onlyFavorites = filter.onlyFavorites,
            query = filter.query,
            sortBy = filter.sortBy.name,
        ).map { entities -> entities.map { it.toDomain(crypto) } }

    // Парсит все включённые источники параллельно, объединяет и сохраняет новые прокси.
    // Дубликаты (тот же ip:port) игнорируются на уровне Room (OnConflictStrategy.IGNORE
    // требует unique index на ip+port — см. миграцию схемы).
    override suspend fun refreshFromSources(): Int = kotlinx.coroutines.coroutineScope {
        val sources = sourceRegistry.builtInSources()
        val fetched = sources.map { source ->
            kotlinx.coroutines.async { runCatching { source.fetch() }.getOrDefault(emptyList()) }
        }.let { kotlinx.coroutines.awaitAll(*it.toTypedArray()) }.flatten()

        val withCountries = resolveMissingCountries(fetched)

        val entities = withCountries.map { it.toEntity(crypto) }
        val insertedIds = dao.insertAll(entities)
        insertedIds.count { it != -1L }
    }

    // Некоторые источники (например, JsonListSource) уже присылают страну в самих данных —
    // такие прокси не трогаем. Для остальных резолвим страну по IP пачкой через GeoIpResolver
    // (сначала офлайн через MaxMind, иначе fallback на онлайн-API с кэшем — см. GeoIpResolver),
    // чтобы не делать по одному сетевому запросу на прокси и не блокировать вставку в БД.
    private suspend fun resolveMissingCountries(proxies: List<Proxy>): List<Proxy> {
        val ipsNeedingLookup = proxies.filter { it.country == null }.map { it.ip }
        if (ipsNeedingLookup.isEmpty()) return proxies

        val resolved = geoIpResolver.resolveBatch(ipsNeedingLookup)
        return proxies.map { proxy ->
            if (proxy.country != null) proxy else proxy.copy(country = resolved[proxy.ip])
        }
    }

    override suspend fun checkAll() {
        val proxies = dao.getAllCheckable().map { it.toDomain(crypto) }
        val results = checker.checkAll(proxies)
        results.forEach { result ->
            dao.updateLatestCheck(result.proxyId, result.status.name, result.latencyMs, result.checkedAt)
        }
    }

    override suspend fun checkSingle(proxyId: Long) {
        val entity = dao.getById(proxyId) ?: return
        val result = checker.check(entity.toDomain(crypto))
        dao.updateLatestCheck(result.proxyId, result.status.name, result.latencyMs, result.checkedAt)
    }

    override suspend fun toggleFavorite(proxyId: Long, isFavorite: Boolean) =
        dao.setFavorite(proxyId, isFavorite)

    override suspend fun addCustomProxy(proxy: Proxy) {
        dao.insertCustom(proxy.copy(isCustom = true).toEntity(crypto))
    }

    override suspend fun getStatusSnapshot(proxyId: Long): ProxyStatus? =
        dao.getById(proxyId)?.let { ProxyStatus.valueOf(it.latestStatus) }

    // Лучший рабочий прокси кроме исключённого — минимальная латентность среди WORKING.
    // Используется CheckWorker, чтобы предложить замену, когда активный прокси падает.
    override suspend fun findBestWorkingProxy(excludeId: Long?): Proxy? =
        dao.getBestWorking(excludeId ?: -1L)?.toDomain(crypto)

    override fun observeProxy(proxyId: Long): Flow<Proxy?> =
        dao.observeById(proxyId).map { it?.toDomain(crypto) }

    override fun observeCheckHistory(proxyId: Long): Flow<List<CheckResult>> =
        checkHistoryDao.observeHistory(proxyId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getAllProxies(): List<Proxy> =
        dao.getAll().map { it.toDomain(crypto) }

    // Импорт переиспользует ту же дедупликацию по (ip, port) через OnConflictStrategy.IGNORE,
    // что и refreshFromSources, и ту же логику докладывания страны для записей без неё
    // (например, CSV без колонки country, или JSON, где её не заполнили вручную).
    override suspend fun importProxies(proxies: List<Proxy>): Int {
        val withCountries = resolveMissingCountries(proxies)
        val entities = withCountries.map { it.copy(isCustom = true).toEntity(crypto) }
        val insertedIds = dao.insertAll(entities)
        return insertedIds.count { it != -1L }
    }
}

private fun CheckResultEntity.toDomain() = CheckResult(
    id = id,
    proxyId = proxyId,
    checkedAt = checkedAt,
    status = ProxyStatus.valueOf(status),
    latencyMs = latencyMs,
    telegramApiReachable = telegramApiReachable,
    errorMessage = errorMessage,
)

private fun ProxyEntity.toDomain(crypto: com.proxyhunter.telegram.data.local.CryptoManager) = Proxy(
    id = id,
    ip = ip,
    port = port,
    protocol = ProxyProtocol.valueOf(protocol),
    username = usernameEncrypted?.let { crypto.decrypt(it) },
    password = passwordEncrypted?.let { crypto.decrypt(it) },
    mtprotoSecret = mtprotoSecretEncrypted?.let { crypto.decrypt(it) },
    country = country,
    sourceUrl = sourceUrl,
    addedAt = addedAt,
    isFavorite = isFavorite,
    isCustom = isCustom,
    latestStatus = ProxyStatus.valueOf(latestStatus),
    latestLatencyMs = latestLatencyMs,
    lastCheckedAt = lastCheckedAt,
)

private fun Proxy.toEntity(crypto: com.proxyhunter.telegram.data.local.CryptoManager) = ProxyEntity(
    id = id,
    ip = ip,
    port = port,
    protocol = protocol.name,
    usernameEncrypted = username?.let { crypto.encrypt(it) },
    passwordEncrypted = password?.let { crypto.encrypt(it) },
    mtprotoSecretEncrypted = mtprotoSecret?.let { crypto.encrypt(it) },
    country = country,
    sourceUrl = sourceUrl,
    addedAt = addedAt,
    isFavorite = isFavorite,
    isCustom = isCustom,
    latestStatus = latestStatus.name,
    latestLatencyMs = latestLatencyMs,
    lastCheckedAt = lastCheckedAt,
)
