package com.proxyhunter.telegram.data.export

import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat { JSON, CSV }

// Запись экспорта сознательно НЕ повторяет Proxy 1:1 — без id/latestStatus/lastCheckedAt/
// isCustom и т.д., это runtime-состояние конкретной установки приложения, переносить между
// устройствами его не имеет смысла. Импортированные прокси всегда попадают в базу как
// новые, непроверенные записи (latestStatus = NOT_CHECKED по умолчанию в Proxy).
data class ProxyExportRecord(
    val ip: String,
    val port: Int,
    val protocol: String,
    val username: String? = null,
    val password: String? = null,
    val secret: String? = null,
    val country: String? = null,
    val favorite: Boolean = false,
)

data class ImportParseResult(
    val proxies: List<Proxy>,
    // Строки/записи, которые не удалось разобрать (неизвестный протокол, некорректный порт,
    // недостаточно колонок в CSV-строке) — считаются, а не молча выбрасываются, чтобы UI
    // мог честно показать "импортировано N, пропущено M", а не тихо потерять часть данных.
    val skippedRows: Int,
)

private val CSV_HEADER = listOf("ip", "port", "protocol", "username", "password", "secret", "country", "favorite")
private val CSV_HEADER_LINE = CSV_HEADER.joinToString(",")

@Singleton
class ProxyExportSerializer @Inject constructor(private val moshi: Moshi) {

    private val listAdapter = moshi.adapter<List<ProxyExportRecord>>(
        Types.newParameterizedType(List::class.java, ProxyExportRecord::class.java),
    ).indent("  ")

    fun toJson(proxies: List<Proxy>): String = listAdapter.toJson(proxies.map { it.toRecord() })

    fun fromJson(content: String): ImportParseResult {
        val records = runCatching { listAdapter.fromJson(content) }.getOrNull() ?: emptyList()
        return recordsToProxies(records)
    }

    fun toCsv(proxies: List<Proxy>): String = buildString {
        appendLine(CSV_HEADER_LINE)
        proxies.forEach { proxy ->
            val record = proxy.toRecord()
            appendLine(
                listOf(
                    record.ip, record.port.toString(), record.protocol,
                    record.username.orEmpty(), record.password.orEmpty(), record.secret.orEmpty(),
                    record.country.orEmpty(), record.favorite.toString(),
                ).joinToString(",") { csvEscape(it) },
            )
        }
    }

    fun fromCsv(content: String): ImportParseResult {
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ImportParseResult(emptyList(), 0)

        // Файл без заголовка (например, экспорт из другого инструмента) — пробуем разобрать
        // как есть, вместо того чтобы требовать точного совпадения заголовка.
        val dataLines = if (lines.first().trim().equals(CSV_HEADER_LINE, ignoreCase = true)) {
            lines.drop(1)
        } else {
            lines
        }

        var malformedRows = 0
        val records = dataLines.mapNotNull { line ->
            val cells = parseCsvLine(line)
            val port = cells.getOrNull(1)?.toIntOrNull()
            if (cells.size < 3 || port == null) {
                malformedRows++
                return@mapNotNull null
            }
            ProxyExportRecord(
                ip = cells[0],
                port = port,
                protocol = cells[2],
                username = cells.getOrNull(3)?.takeIf { it.isNotBlank() },
                password = cells.getOrNull(4)?.takeIf { it.isNotBlank() },
                secret = cells.getOrNull(5)?.takeIf { it.isNotBlank() },
                country = cells.getOrNull(6)?.takeIf { it.isNotBlank() },
                favorite = cells.getOrNull(7)?.toBooleanStrictOrNull() ?: false,
            )
        }

        val result = recordsToProxies(records)
        return result.copy(skippedRows = result.skippedRows + malformedRows)
    }

    private fun recordsToProxies(records: List<ProxyExportRecord>): ImportParseResult {
        var skipped = 0
        val proxies = records.mapNotNull { record ->
            val protocol = runCatching { ProxyProtocol.valueOf(record.protocol.trim().uppercase()) }.getOrNull()
            if (protocol == null) {
                skipped++
                return@mapNotNull null
            }
            Proxy(
                ip = record.ip,
                port = record.port,
                protocol = protocol,
                username = record.username,
                password = record.password,
                mtprotoSecret = record.secret,
                country = record.country,
                isFavorite = record.favorite,
                sourceUrl = "import",
                addedAt = System.currentTimeMillis(),
                isCustom = true,
            )
        }
        return ImportParseResult(proxies, skipped)
    }

    private fun Proxy.toRecord() = ProxyExportRecord(
        ip = ip, port = port, protocol = protocol.name,
        username = username, password = password, secret = mtprotoSecret,
        country = country, favorite = isFavorite,
    )

    // Минимальный RFC4180-совместимый разбор: поле в кавычках может содержать запятые и
    // экранированные кавычки (`""` внутри `"..."`). Без этого пароль с запятой внутри
    // сломал бы разбор при наивном split(",").
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun csvEscape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
