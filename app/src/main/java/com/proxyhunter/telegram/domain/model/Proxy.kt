package com.proxyhunter.telegram.domain.model

enum class ProxyProtocol {
    SOCKS5, HTTP, HTTPS, MTPROTO
}

enum class ProxyStatus {
    WORKING, FAILED, TIMEOUT, NOT_CHECKED
}

data class Proxy(
    val id: Long = 0,
    val ip: String,
    val port: Int,
    val protocol: ProxyProtocol,
    val username: String? = null,
    val password: String? = null,       // хранится расшифрованным только в памяти в runtime
    val mtprotoSecret: String? = null,
    val country: String? = null,
    val sourceUrl: String,
    val addedAt: Long,
    val isFavorite: Boolean = false,
    val isCustom: Boolean = false,
    val latestStatus: ProxyStatus = ProxyStatus.NOT_CHECKED,
    val latestLatencyMs: Int? = null,
    val lastCheckedAt: Long? = null,
)

data class CheckResult(
    val id: Long = 0,
    val proxyId: Long,
    val checkedAt: Long,
    val status: ProxyStatus,
    val latencyMs: Int?,
    val telegramApiReachable: Boolean,
    val errorMessage: String? = null,
)

data class ProxyFilter(
    val protocol: ProxyProtocol? = null,
    val country: String? = null,
    val onlyWorking: Boolean = false,
    val onlyFavorites: Boolean = false,
    val query: String = "",
    val sortBy: SortOption = SortOption.LATENCY,
)

enum class SortOption { LATENCY, ADDED_DATE, COUNTRY }
