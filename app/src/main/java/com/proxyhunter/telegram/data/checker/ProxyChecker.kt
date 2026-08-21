package com.proxyhunter.telegram.data.checker

import com.proxyhunter.telegram.data.checker.mtproto.MtProtoObfuscatedHandshakeBuilder
import com.proxyhunter.telegram.data.checker.mtproto.MtProtoSecretUnsupportedException
import com.proxyhunter.telegram.data.checker.mtproto.MtProtoTransport
import com.proxyhunter.telegram.domain.model.CheckResult
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.model.ProxyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy as JavaProxy
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

private const val CHECK_TIMEOUT_MS = 8000L
private const val TCP_CONNECT_TIMEOUT_MS = 5000
// Сколько ждём ответа/закрытия соединения после отправки handshake-заголовка. Реальный
// MTProxy, приняв корректный заголовок, обычно молчит в ожидании дальнейших данных —
// поэтому таймаут здесь интерпретируется как "принял", а не как ошибка (см. checkMtProto).
private const val MTPROTO_READ_TIMEOUT_MS = 4000
private const val MAX_PARALLEL_CHECKS = 20
private const val DEFAULT_TELEGRAM_API_PROBE_URL = "https://api.telegram.org"

@Singleton
class ProxyChecker @Inject constructor() {

    // Открыт для подмены в unit-тестах (MockWebServer) — в проде всегда api.telegram.org.
    var telegramApiProbeUrl: String = DEFAULT_TELEGRAM_API_PROBE_URL

    // Открыт для подмены в unit-тестах, чтобы не ждать реальные MTPROTO_READ_TIMEOUT_MS
    // при проверке сценария "сервер молчит, но не закрывает соединение → WORKING".
    var mtprotoReadTimeoutMs: Int = MTPROTO_READ_TIMEOUT_MS

    private val semaphore = Semaphore(MAX_PARALLEL_CHECKS)

    // Проверяет один прокси: для SOCKS5/HTTP/HTTPS — TCP + доступность Telegram API через
    // прокси; для MTProto — обфусцированный transport-handshake (см. checkMtProto).
    // Semaphore ограничивает число одновременных проверок, чтобы не перегружать сеть устройства.
    suspend fun check(proxy: Proxy): CheckResult = semaphore.withPermit {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            if (proxy.protocol == ProxyProtocol.MTPROTO) {
                return@withContext withTimeoutOrNull(CHECK_TIMEOUT_MS) { checkMtProto(proxy, now) }
                    ?: CheckResult(
                        proxyId = proxy.id, checkedAt = now, status = ProxyStatus.TIMEOUT,
                        latencyMs = null, telegramApiReachable = false,
                        errorMessage = "Таймаут MTProto-проверки (${CHECK_TIMEOUT_MS}ms)",
                    )
            }

            val latencyStart = System.currentTimeMillis()
            val result = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                probeTelegramApi(proxy)
            }
            val latency = (System.currentTimeMillis() - latencyStart).toInt()

            when (result) {
                true -> CheckResult(
                    proxyId = proxy.id, checkedAt = now, status = ProxyStatus.WORKING,
                    latencyMs = latency, telegramApiReachable = true,
                )
                false -> CheckResult(
                    proxyId = proxy.id, checkedAt = now, status = ProxyStatus.FAILED,
                    latencyMs = null, telegramApiReachable = false,
                    errorMessage = "Прокси не пропускает запрос к Telegram API",
                )
                null -> CheckResult(
                    proxyId = proxy.id, checkedAt = now, status = ProxyStatus.TIMEOUT,
                    latencyMs = null, telegramApiReachable = false,
                    errorMessage = "Таймаут проверки (${CHECK_TIMEOUT_MS}ms)",
                )
            }
        }
    }

    // Массовая проверка списка с ограниченным параллелизмом через async + Semaphore внутри check().
    suspend fun checkAll(proxies: List<Proxy>): List<CheckResult> = coroutineScope {
        proxies.map { proxy -> async { check(proxy) } }.awaitAll()
    }

    private fun tcpConnect(ip: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), TCP_CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    // Отправляет обфусцированный MTProto handshake-заголовок и интерпретирует реакцию сервера:
    //  - сокет закрылся сразу (EOF/RST)            → FAILED, прокси отверг заголовок
    //  - сервер что-то прислал в ответ              → WORKING
    //  - таймаут чтения без разрыва соединения      → WORKING (ожидаемое поведение живого
    //                                                  MTProxy — молчит, ждёт настоящих данных)
    // Секреты fake-TLS ("ee-") не поддерживаются построителем handshake'а — в этом случае
    // честно откатываемся на TCP-only проверку вместо ложного "не рабочий".
    private fun checkMtProto(proxy: Proxy, now: Long): CheckResult {
        val handshake = try {
            MtProtoObfuscatedHandshakeBuilder.build(MtProtoTransport.INTERMEDIATE, proxy.mtprotoSecret)
        } catch (unsupported: MtProtoSecretUnsupportedException) {
            val tcpOk = tcpConnect(proxy.ip, proxy.port)
            return CheckResult(
                proxyId = proxy.id, checkedAt = now,
                status = if (tcpOk) ProxyStatus.WORKING else ProxyStatus.FAILED,
                latencyMs = null, telegramApiReachable = false,
                errorMessage = if (tcpOk) {
                    "TCP OK, handshake не проверен: ${unsupported.message}"
                } else {
                    "TCP-порт недоступен"
                },
            )
        }

        return runCatching {
            Socket().use { socket ->
                val start = System.currentTimeMillis()
                socket.connect(InetSocketAddress(proxy.ip, proxy.port), TCP_CONNECT_TIMEOUT_MS)
                socket.soTimeout = mtprotoReadTimeoutMs

                socket.outputStream.write(handshake.packetToSend)
                socket.outputStream.flush()

                val latency = (System.currentTimeMillis() - start).toInt()

                val accepted = try {
                    val probe = ByteArray(1)
                    socket.getInputStream().read(probe) != -1 // -1 = сервер закрыл соединение (graceful EOF)
                } catch (timeout: SocketTimeoutException) {
                    true
                }

                if (accepted) {
                    CheckResult(
                        proxyId = proxy.id, checkedAt = now, status = ProxyStatus.WORKING,
                        latencyMs = latency, telegramApiReachable = false,
                    )
                } else {
                    CheckResult(
                        proxyId = proxy.id, checkedAt = now, status = ProxyStatus.FAILED,
                        latencyMs = null, telegramApiReachable = false,
                        errorMessage = "Прокси закрыл соединение сразу после handshake",
                    )
                }
            }
        }.getOrElse { error ->
            CheckResult(
                proxyId = proxy.id, checkedAt = now, status = ProxyStatus.FAILED,
                latencyMs = null, telegramApiReachable = false,
                errorMessage = error.message ?: "Ошибка сети при MTProto handshake",
            )
        }
    }

    // Запрос к api.telegram.org через проверяемый прокси — если ответ приходит
    // (даже 404 на пустой путь — это ОК, важно само сетевое прохождение), считаем прокси рабочим для Telegram.
    private fun probeTelegramApi(proxy: Proxy): Boolean = runCatching {
        val javaProxyType = if (proxy.protocol == ProxyProtocol.SOCKS5) JavaProxy.Type.SOCKS else JavaProxy.Type.HTTP
        val javaProxy = JavaProxy(javaProxyType, InetSocketAddress(proxy.ip, proxy.port))

        val clientBuilder = OkHttpClient.Builder().proxy(javaProxy)

        if (!proxy.username.isNullOrBlank()) {
            clientBuilder.proxyAuthenticator { _, response ->
                val credential = Credentials.basic(proxy.username, proxy.password.orEmpty())
                response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
        }

        val client = clientBuilder
            .connectTimeout(java.time.Duration.ofMillis(TCP_CONNECT_TIMEOUT_MS.toLong()))
            .readTimeout(java.time.Duration.ofMillis(CHECK_TIMEOUT_MS))
            .build()

        val request = Request.Builder().url(telegramApiProbeUrl).head().build()
        client.newCall(request).execute().use { response -> response.code < 500 }
    }.getOrDefault(false)
}
