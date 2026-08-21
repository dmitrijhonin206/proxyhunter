package com.proxyhunter.telegram.repository

import com.proxyhunter.telegram.data.checker.ProxyChecker
import com.proxyhunter.telegram.data.local.CheckHistoryDao
import com.proxyhunter.telegram.data.local.CryptoManager
import com.proxyhunter.telegram.data.local.ProxyDao
import com.proxyhunter.telegram.data.local.entity.ProxyEntity
import com.proxyhunter.telegram.data.remote.geoip.GeoIpResolver
import com.proxyhunter.telegram.data.remote.source.ProxySource
import com.proxyhunter.telegram.data.remote.source.ProxySourceRegistry
import com.proxyhunter.telegram.data.repository.ProxyRepositoryImpl
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Мокает все зависимости ProxyRepositoryImpl напрямую (MockK, не Mockito — работает с
// финальными Kotlin-классами без "open" и без реального Android-окружения). CryptoManager
// и GeoIpResolver в реальности завязаны на Android Keystore / Context — здесь их
// конструкторы не вызываются вовсе (mockk() создаёт proxy-подмену), поэтому тест работает
// на чистом JVM, как и остальные unit-тесты в проекте.
class ProxyRepositoryImplTest {

    private lateinit var dao: ProxyDao
    private lateinit var checkHistoryDao: CheckHistoryDao
    private lateinit var sourceRegistry: ProxySourceRegistry
    private lateinit var checker: ProxyChecker
    private lateinit var geoIpResolver: GeoIpResolver
    private lateinit var crypto: CryptoManager
    private lateinit var repository: ProxyRepositoryImpl

    @Before
    fun setUp() {
        dao = mockk()
        checkHistoryDao = mockk()
        sourceRegistry = mockk()
        checker = mockk()
        geoIpResolver = mockk()
        crypto = mockk()

        repository = ProxyRepositoryImpl(dao, checkHistoryDao, sourceRegistry, checker, geoIpResolver, crypto)
    }

    @Test
    fun `refreshFromSources resolves country only for proxies missing it`() = runTest {
        val proxyWithoutCountry = fakeProxy(ip = "1.1.1.1")
        val proxyWithCountry = fakeProxy(ip = "2.2.2.2", country = "DE")
        stubSingleSource(listOf(proxyWithoutCountry, proxyWithCountry))

        coEvery { geoIpResolver.resolveBatch(listOf("1.1.1.1")) } returns mapOf("1.1.1.1" to "US")

        val insertedSlot = slot<List<ProxyEntity>>()
        coEvery { dao.insertAll(capture(insertedSlot)) } returns listOf(1L, 2L)

        val result = repository.refreshFromSources()

        assertEquals(2, result)
        val countryByIp = insertedSlot.captured.associate { it.ip to it.country }
        assertEquals("US", countryByIp["1.1.1.1"]) // резолвнута через GeoIpResolver
        assertEquals("DE", countryByIp["2.2.2.2"]) // пришла от источника, не тронута
        coVerify(exactly = 1) { geoIpResolver.resolveBatch(listOf("1.1.1.1")) }
    }

    @Test
    fun `refreshFromSources skips geo lookup entirely when every proxy already has a country`() = runTest {
        stubSingleSource(listOf(fakeProxy(ip = "3.3.3.3", country = "NL")))
        coEvery { dao.insertAll(any()) } returns listOf(1L)

        repository.refreshFromSources()

        coVerify(exactly = 0) { geoIpResolver.resolveBatch(any()) }
    }

    @Test
    fun `refreshFromSources batches all missing-country ips into a single resolver call`() = runTest {
        stubSingleSource(
            listOf(
                fakeProxy(ip = "1.1.1.1"),
                fakeProxy(ip = "2.2.2.2"),
                fakeProxy(ip = "3.3.3.3", country = "FR"), // уже есть — не должен попасть в батч
            ),
        )
        coEvery { geoIpResolver.resolveBatch(listOf("1.1.1.1", "2.2.2.2")) } returns
            mapOf("1.1.1.1" to "US", "2.2.2.2" to "DE")
        coEvery { dao.insertAll(any()) } returns listOf(1L, 2L, 3L)

        repository.refreshFromSources()

        coVerify(exactly = 1) { geoIpResolver.resolveBatch(listOf("1.1.1.1", "2.2.2.2")) }
    }

    @Test
    fun `refreshFromSources counts only successfully inserted proxies, ignoring duplicates`() = runTest {
        // Room возвращает -1 для строк, пропущенных OnConflictStrategy.IGNORE (дубликат по ip+port).
        stubSingleSource(listOf(fakeProxy(ip = "1.1.1.1", country = "US"), fakeProxy(ip = "2.2.2.2", country = "US")))
        coEvery { dao.insertAll(any()) } returns listOf(5L, -1L)

        val result = repository.refreshFromSources()

        assertEquals(1, result)
    }

    @Test
    fun `refreshFromSources merges proxies fetched from multiple sources`() = runTest {
        val sourceA = mockk<ProxySource>()
        val sourceB = mockk<ProxySource>()
        coEvery { sourceA.fetch() } returns listOf(fakeProxy(ip = "1.1.1.1", country = "US"))
        coEvery { sourceB.fetch() } returns listOf(fakeProxy(ip = "2.2.2.2", country = "DE"))
        every { sourceRegistry.builtInSources() } returns listOf(sourceA, sourceB)
        coEvery { dao.insertAll(any()) } returns listOf(1L, 2L)

        val result = repository.refreshFromSources()

        assertEquals(2, result)
    }

    @Test
    fun `refreshFromSources tolerates one source failing without failing the whole refresh`() = runTest {
        val healthySource = mockk<ProxySource>()
        val brokenSource = mockk<ProxySource>()
        coEvery { healthySource.fetch() } returns listOf(fakeProxy(ip = "1.1.1.1", country = "US"))
        coEvery { brokenSource.fetch() } throws RuntimeException("network error")
        every { sourceRegistry.builtInSources() } returns listOf(healthySource, brokenSource)
        coEvery { dao.insertAll(any()) } returns listOf(1L)

        val result = repository.refreshFromSources()

        assertEquals(1, result)
    }

    @Test
    fun `getAllProxies maps every stored entity without touching the geo resolver`() = runTest {
        val entity = ProxyEntity(
            id = 1, ip = "1.1.1.1", port = 1080, protocol = "SOCKS5",
            country = "US", sourceUrl = "test", addedAt = 0L,
        )
        coEvery { dao.getAll() } returns listOf(entity)

        val result = repository.getAllProxies()

        assertEquals(1, result.size)
        assertEquals("1.1.1.1", result.single().ip)
        coVerify(exactly = 0) { geoIpResolver.resolveBatch(any()) }
    }

    @Test
    fun `importProxies resolves missing country and marks proxies as custom`() = runTest {
        val proxy = fakeProxy(ip = "5.5.5.5") // без страны
        coEvery { geoIpResolver.resolveBatch(listOf("5.5.5.5")) } returns mapOf("5.5.5.5" to "FR")
        val insertedSlot = slot<List<ProxyEntity>>()
        coEvery { dao.insertAll(capture(insertedSlot)) } returns listOf(10L)

        val count = repository.importProxies(listOf(proxy))

        assertEquals(1, count)
        val inserted = insertedSlot.captured.single()
        assertEquals("FR", inserted.country)
        assertTrue(inserted.isCustom)
    }

    @Test
    fun `importProxies counts only successful inserts, ignoring duplicates`() = runTest {
        val proxies = listOf(fakeProxy(ip = "1.1.1.1", country = "US"), fakeProxy(ip = "2.2.2.2", country = "US"))
        coEvery { dao.insertAll(any()) } returns listOf(1L, -1L)

        val count = repository.importProxies(proxies)

        assertEquals(1, count)
    }

    private fun stubSingleSource(proxies: List<Proxy>) {
        val source = mockk<ProxySource>()
        coEvery { source.fetch() } returns proxies
        every { sourceRegistry.builtInSources() } returns listOf(source)
    }

    private fun fakeProxy(ip: String, country: String? = null) = Proxy(
        ip = ip,
        port = 1080,
        protocol = ProxyProtocol.SOCKS5,
        country = country,
        sourceUrl = "test",
        addedAt = System.currentTimeMillis(),
    )
}
