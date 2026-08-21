package com.proxyhunter.telegram.data.remote.source

import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import javax.inject.Inject

interface ProxySource {
    val sourceUrl: String
    suspend fun fetch(): List<Proxy>
}

// Источник в формате обычного построчного списка ip:port или ip:port:protocol,
// как часто публикуют open-source репозитории на GitHub (raw.githubusercontent.com/.../proxies.txt)
class PlainTextListSource(
    override val sourceUrl: String,
    private val defaultProtocol: ProxyProtocol,
    private val httpClient: OkHttpClient,
) : ProxySource {

    private val lineRegex = Regex("""^([\d.]+):(\d{2,5})(?::(\w+))?$""")

    override suspend fun fetch(): List<Proxy> {
        val body = httpClient.newCall(Request.Builder().url(sourceUrl).build())
            .execute().use { it.body?.string() } ?: return emptyList()

        val now = System.currentTimeMillis()
        return body.lineSequence()
            .mapNotNull { line -> lineRegex.find(line.trim()) }
            .mapNotNull { match ->
                val (ip, portStr, protoStr) = match.destructured
                val port = portStr.toIntOrNull() ?: return@mapNotNull null
                val protocol = protoStr.takeIf { it.isNotBlank() }
                    ?.let { runCatching { ProxyProtocol.valueOf(it.uppercase()) }.getOrNull() }
                    ?: defaultProtocol
                Proxy(
                    ip = ip,
                    port = port,
                    protocol = protocol,
                    sourceUrl = sourceUrl,
                    addedAt = now,
                )
            }
            .toList()
    }
}

// Источник в формате JSON-массива объектов { ip, port, protocol, country? }
class JsonListSource(
    override val sourceUrl: String,
    private val httpClient: OkHttpClient,
    private val moshiAdapter: com.squareup.moshi.JsonAdapter<List<JsonProxyDto>>,
) : ProxySource {

    override suspend fun fetch(): List<Proxy> {
        val body = httpClient.newCall(Request.Builder().url(sourceUrl).build())
            .execute().use { it.body?.string() } ?: return emptyList()

        val now = System.currentTimeMillis()
        val dtos = runCatching { moshiAdapter.fromJson(body) }.getOrNull() ?: return emptyList()

        return dtos.mapNotNull { dto ->
            val protocol = runCatching { ProxyProtocol.valueOf(dto.protocol.uppercase()) }.getOrNull()
                ?: return@mapNotNull null
            Proxy(
                ip = dto.ip,
                port = dto.port,
                protocol = protocol,
                country = dto.country,
                mtprotoSecret = dto.secret,
                sourceUrl = sourceUrl,
                addedAt = now,
            )
        }
    }
}

data class JsonProxyDto(
    val ip: String,
    val port: Int,
    val protocol: String,
    val country: String? = null,
    val secret: String? = null,
)

// Источник в формате HTML-таблицы на специализированных сайтах со списками прокси —
// парсится через Jsoup по CSS-селектору таблицы. Селектор настраивается на источник,
// т.к. разметка у разных публичных списков отличается.
class HtmlTableSource(
    override val sourceUrl: String,
    private val rowSelector: String,
    private val defaultProtocol: ProxyProtocol,
    private val httpClient: OkHttpClient,
) : ProxySource {

    override suspend fun fetch(): List<Proxy> {
        val html = httpClient.newCall(Request.Builder().url(sourceUrl).build())
            .execute().use { it.body?.string() } ?: return emptyList()

        val now = System.currentTimeMillis()
        val doc = Jsoup.parse(html)
        return doc.select(rowSelector).mapNotNull { row ->
            val cells = row.select("td").map { it.text().trim() }
            if (cells.size < 2) return@mapNotNull null
            val ip = cells[0]
            val port = cells.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            val country = cells.getOrNull(2)?.takeIf { it.isNotBlank() }
            Proxy(
                ip = ip,
                port = port,
                protocol = defaultProtocol,
                country = country,
                sourceUrl = sourceUrl,
                addedAt = now,
            )
        }
    }
}

// Реестр встроенных источников + пользовательские URL из настроек (SettingsRepository)
@javax.inject.Singleton
class ProxySourceRegistry @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    fun builtInSources(): List<ProxySource> = listOf(
        PlainTextListSource(
            sourceUrl = "https://raw.githubusercontent.com/example/proxy-list/main/socks5.txt",
            defaultProtocol = ProxyProtocol.SOCKS5,
            httpClient = httpClient,
        ),
        PlainTextListSource(
            sourceUrl = "https://raw.githubusercontent.com/example/proxy-list/main/http.txt",
            defaultProtocol = ProxyProtocol.HTTP,
            httpClient = httpClient,
        ),
        // MTProto-прокси обычно публикуются в формате JSON с секретом — см. JsonListSource
    )

    fun customSource(url: String): ProxySource =
        PlainTextListSource(url, ProxyProtocol.HTTP, httpClient)
}
