package com.proxyhunter.telegram.checker

import com.proxyhunter.telegram.data.checker.ProxyChecker
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.proxyhunter.telegram.domain.model.ProxyStatus
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class ProxyCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var checker: ProxyChecker

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        checker = ProxyChecker().apply {
            // Тест "сервер молчит" не должен реально ждать production-таймаут (4с) —
            // сокращаем его здесь же, аналогично telegramApiProbeUrl.
            mtprotoReadTimeoutMs = 500
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // MockWebServer, при плейн-HTTP запросе через java.net.Proxy(Type.HTTP, ...),
    // получает запрос напрямую как обычный HTTP-сервер (OkHttp шлёт absolute-URI на прокси
    // без CONNECT-туннеля для http:// целей) — этого достаточно, чтобы протестировать
    // сценарий "прокси пропускает трафик к Telegram API".
    @Test
    fun `http proxy that forwards traffic is marked WORKING`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        checker.telegramApiProbeUrl = "http://api.telegram.org/"

        val proxy = fakeProxy(protocol = ProxyProtocol.HTTP, port = server.port)
        val result = checker.check(proxy)

        assertEquals(ProxyStatus.WORKING, result.status)
        assertTrue(result.telegramApiReachable)
        assertTrue((result.latencyMs ?: -1) >= 0)
    }

    @Test
    fun `unreachable proxy port is marked FAILED`() = runTest {
        val closedPort = findFreePort()
        checker.telegramApiProbeUrl = "http://api.telegram.org/"

        val proxy = fakeProxy(protocol = ProxyProtocol.HTTP, port = closedPort)
        val result = checker.check(proxy)

        assertEquals(ProxyStatus.FAILED, result.status)
        assertEquals(false, result.telegramApiReachable)
    }

    // Стаб живого MTProxy: принимает соединение, читает наш 64-байтовый handshake
    // и молча держит сокет открытым (реальное поведение — сервер ждёт настоящих
    // MTProto-данных). Таймаут чтения без разрыва соединения должен трактоваться как WORKING.
    @Test
    fun `mtproto proxy that accepts handshake and stays silent is WORKING`() = runTest {
        val fakeMtProxy = FakeRawTcpServer(behavior = FakeServerBehavior.ACCEPT_AND_STAY_SILENT)
        fakeMtProxy.start()

        val proxy = fakeProxy(protocol = ProxyProtocol.MTPROTO, port = fakeMtProxy.port)
        val result = checker.check(proxy)

        assertEquals(ProxyStatus.WORKING, result.status)
        assertEquals(false, result.telegramApiReachable)

        fakeMtProxy.stop()
    }

    // Стаб, который сразу закрывает соединение после получения handshake (типичное
    // поведение для порта, за которым нет настоящего MTProxy, либо неверного секрета).
    @Test
    fun `mtproto proxy that closes connection right after handshake is FAILED`() = runTest {
        val fakeServer = FakeRawTcpServer(behavior = FakeServerBehavior.ACCEPT_AND_CLOSE)
        fakeServer.start()

        val proxy = fakeProxy(protocol = ProxyProtocol.MTPROTO, port = fakeServer.port)
        val result = checker.check(proxy)

        assertEquals(ProxyStatus.FAILED, result.status)

        fakeServer.stop()
    }

    // Стаб, который отвечает произвольным байтом сразу после handshake — тоже
    // валидный признак живого сервера (не обязательно тишина).
    @Test
    fun `mtproto proxy that replies with any byte is WORKING`() = runTest {
        val fakeServer = FakeRawTcpServer(behavior = FakeServerBehavior.ACCEPT_AND_REPLY)
        fakeServer.start()

        val proxy = fakeProxy(protocol = ProxyProtocol.MTPROTO, port = fakeServer.port)
        val result = checker.check(proxy)

        assertEquals(ProxyStatus.WORKING, result.status)

        fakeServer.stop()
    }

    @Test
    fun `mtproto proxy with closed port is FAILED`() = runTest {
        val proxy = fakeProxy(protocol = ProxyProtocol.MTPROTO, port = findFreePort())

        val result = checker.check(proxy)

        assertEquals(ProxyStatus.FAILED, result.status)
    }

    // fake-TLS ("ee-") секрет не поддерживается построителем handshake'а — чекер должен
    // явно откатиться на TCP-only проверку, а не тихо считать прокси нерабочим.
    @Test
    fun `mtproto proxy with fake-tls secret falls back to TCP-only check`() = runTest {
        val fakeMtProxy = FakeRawTcpServer(behavior = FakeServerBehavior.ACCEPT_AND_STAY_SILENT)
        fakeMtProxy.start()

        val proxy = fakeProxy(protocol = ProxyProtocol.MTPROTO, port = fakeMtProxy.port)
            .copy(mtprotoSecret = "ee" + "11".repeat(16))
        val result = checker.check(proxy)

        assertEquals(ProxyStatus.WORKING, result.status)
        assertTrue(result.errorMessage?.contains("не поддерживается") == true || result.errorMessage?.contains("handshake") == true)

        fakeMtProxy.stop()
    }

    @Test
    fun `checkAll runs checks for every proxy in the list`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        checker.telegramApiProbeUrl = "http://api.telegram.org/"

        val proxies = listOf(
            fakeProxy(id = 1, protocol = ProxyProtocol.HTTP, port = server.port),
            fakeProxy(id = 2, protocol = ProxyProtocol.HTTP, port = server.port),
        )

        val results = checker.checkAll(proxies)

        assertEquals(2, results.size)
        assertEquals(setOf(1L, 2L), results.map { it.proxyId }.toSet())
    }

    private fun fakeProxy(id: Long = 1, protocol: ProxyProtocol, port: Int) = Proxy(
        id = id,
        ip = "127.0.0.1",
        port = port,
        protocol = protocol,
        sourceUrl = "test",
        addedAt = System.currentTimeMillis(),
    )

    // Находит свободный локальный порт и сразу закрывает сокет — с высокой вероятностью
    // соединение на него будет отклонено на момент проверки в тесте.
    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }
}

private enum class FakeServerBehavior { ACCEPT_AND_STAY_SILENT, ACCEPT_AND_CLOSE, ACCEPT_AND_REPLY }

// Минимальный сырой TCP-сервер на фоновом потоке — имитирует разные типы реакции
// MTProxy на handshake, без завязки на HTTP-семантику MockWebServer (которая не
// подходит для тестирования бинарного протокола поверх голого TCP).
private class FakeRawTcpServer(private val behavior: FakeServerBehavior) {
    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort
    private var thread: Thread? = null

    fun start() {
        thread = Thread {
            runCatching {
                val client: Socket = serverSocket.accept()
                client.use {
                    // Прочитать (и отбросить) 64-байтовый handshake, который прислал чекер.
                    val header = ByteArray(64)
                    var read = 0
                    while (read < 64) {
                        val n = it.getInputStream().read(header, read, 64 - read)
                        if (n == -1) break
                        read += n
                    }

                    when (behavior) {
                        FakeServerBehavior.ACCEPT_AND_STAY_SILENT -> Thread.sleep(2000)
                        FakeServerBehavior.ACCEPT_AND_CLOSE -> { /* просто выходим из use{} → close() */ }
                        FakeServerBehavior.ACCEPT_AND_REPLY -> it.getOutputStream().write(byteArrayOf(0x01))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { serverSocket.close() }
        thread?.interrupt()
    }
}
