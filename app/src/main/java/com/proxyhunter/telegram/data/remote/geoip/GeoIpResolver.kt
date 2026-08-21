package com.proxyhunter.telegram.data.remote.geoip

import android.content.Context
import com.maxmind.geoip2.DatabaseReader
import com.proxyhunter.telegram.data.local.GeoCacheDao
import com.proxyhunter.telegram.data.local.entity.GeoCacheEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

private const val GEOLITE_ASSET_PATH = "GeoLite2-Country.mmdb"
private const val FALLBACK_API_URL = "https://ip-api.com/json/%s?fields=countryCode"
private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 дней — страна IP почти не меняется

// Определяет страну по IP: сперва пытается офлайн через MaxMind GeoLite2 (если .mmdb
// положен в assets — быстро, без сети, без лимитов), иначе идёт fallback на публичный
// API с кэшированием результата в Room, чтобы не делать повторных запросов для одного IP.
@Singleton
class GeoIpResolver @Inject constructor(
    @ApplicationContext context: Context,
    private val httpClient: OkHttpClient,
    private val cacheDao: GeoCacheDao,
) {
    private val appContext = context.applicationContext

    // DatabaseReader из MaxMind — тяжёлый объект, инициализируем один раз лениво.
    // Если файла в assets нет (не скачан разработчиком/пользователем), остаётся null
    // и резолвер молча переходит на онлайн-fallback.
    private val offlineReader: DatabaseReader? by lazy {
        runCatching {
            appContext.assets.open(GEOLITE_ASSET_PATH).use { stream ->
                DatabaseReader.Builder(stream).build()
            }
        }.getOrNull()
    }

    suspend fun resolveCountry(ip: String): String? = withContext(Dispatchers.IO) {
        cacheDao.get(ip)?.let { cached ->
            if (System.currentTimeMillis() - cached.resolvedAt < CACHE_TTL_MS) {
                return@withContext cached.countryCode
            }
        }

        val country = resolveOffline(ip) ?: resolveOnline(ip)
        if (country != null) {
            cacheDao.upsert(GeoCacheEntity(ip = ip, countryCode = country, resolvedAt = System.currentTimeMillis()))
        }
        country
    }

    private fun resolveOffline(ip: String): String? = runCatching {
        val reader = offlineReader ?: return null
        reader.country(InetAddress.getByName(ip)).country.isoCode
    }.getOrNull()

    private fun resolveOnline(ip: String): String? = runCatching {
        val request = Request.Builder().url(FALLBACK_API_URL.format(ip)).build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return null
            JSONObject(body).optString("countryCode").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    // Пакетное разрешение для списка свежеспарсенных прокси — используется в ParsingWorker,
    // чтобы не резолвить страну по одной в UI-потоке при отображении списка.
    suspend fun resolveBatch(ips: List<String>): Map<String, String?> = withContext(Dispatchers.IO) {
        ips.distinct().associateWith { resolveCountry(it) }
    }
}
