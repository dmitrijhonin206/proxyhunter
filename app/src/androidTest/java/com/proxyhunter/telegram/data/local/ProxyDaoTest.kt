package com.proxyhunter.telegram.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proxyhunter.telegram.data.local.entity.ProxyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Инструментальные тесты Room DAO — требуют эмулятор/устройство (JUnit4 android test runner),
// в отличие от чисто-JVM тестов парсера/чекера в src/test. Используем in-memory базу,
// чтобы не трогать реальный файл БД и не оставлять состояние между тестами.
@RunWith(AndroidJUnit4::class)
class ProxyDaoTest {

    private lateinit var db: ProxyDatabase
    private lateinit var proxyDao: ProxyDao
    private lateinit var checkHistoryDao: CheckHistoryDao
    private lateinit var geoCacheDao: GeoCacheDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ProxyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        proxyDao = db.proxyDao()
        checkHistoryDao = db.checkHistoryDao()
        geoCacheDao = db.geoCacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAll_thenObserveProxies_returnsInsertedRows() = runTest {
        proxyDao.insertAll(listOf(proxy(ip = "1.1.1.1", port = 80), proxy(ip = "2.2.2.2", port = 8080)))

        val result = proxyDao.observeProxies(
            protocol = null, country = null, onlyWorking = false,
            onlyFavorites = false, query = "", sortBy = "ADDED_DATE",
        ).first()

        assertEquals(2, result.size)
    }

    @Test
    fun insertAll_ignoresDuplicateIpPort_onConflict() = runTest {
        // OnConflictStrategy.IGNORE в DAO полагается на unique index (ip, port) в схеме —
        // тест фиксирует ожидаемое поведение: повторная вставка того же ip:port не плодит дубликаты.
        val duplicate = proxy(ip = "9.9.9.9", port = 1080)
        proxyDao.insertAll(listOf(duplicate))
        proxyDao.insertAll(listOf(duplicate.copy(sourceUrl = "different-source")))

        val result = proxyDao.observeProxies(
            protocol = null, country = null, onlyWorking = false,
            onlyFavorites = false, query = "", sortBy = "ADDED_DATE",
        ).first()

        assertEquals(1, result.size)
    }

    @Test
    fun observeProxies_filtersByProtocol() = runTest {
        proxyDao.insertAll(
            listOf(
                proxy(ip = "1.1.1.1", port = 1080, protocol = "SOCKS5"),
                proxy(ip = "2.2.2.2", port = 8080, protocol = "HTTP"),
            )
        )

        val result = proxyDao.observeProxies(
            protocol = "SOCKS5", country = null, onlyWorking = false,
            onlyFavorites = false, query = "", sortBy = "ADDED_DATE",
        ).first()

        assertEquals(1, result.size)
        assertEquals("1.1.1.1", result[0].ip)
    }

    @Test
    fun observeProxies_filtersByOnlyWorking() = runTest {
        val ids = proxyDao.insertAll(
            listOf(proxy(ip = "1.1.1.1", port = 80), proxy(ip = "2.2.2.2", port = 80))
        )
        proxyDao.updateLatestCheck(ids[0], "WORKING", 120, System.currentTimeMillis())
        proxyDao.updateLatestCheck(ids[1], "FAILED", null, System.currentTimeMillis())

        val result = proxyDao.observeProxies(
            protocol = null, country = null, onlyWorking = true,
            onlyFavorites = false, query = "", sortBy = "LATENCY",
        ).first()

        assertEquals(1, result.size)
        assertEquals("WORKING", result[0].latestStatus)
    }

    @Test
    fun observeProxies_filtersByFavorites() = runTest {
        val ids = proxyDao.insertAll(
            listOf(proxy(ip = "1.1.1.1", port = 80), proxy(ip = "2.2.2.2", port = 80))
        )
        proxyDao.setFavorite(ids[0], true)

        val result = proxyDao.observeProxies(
            protocol = null, country = null, onlyWorking = false,
            onlyFavorites = true, query = "", sortBy = "ADDED_DATE",
        ).first()

        assertEquals(1, result.size)
        assertTrue(result[0].isFavorite)
    }

    @Test
    fun observeProxies_filtersByIpSearchQuery() = runTest {
        proxyDao.insertAll(
            listOf(proxy(ip = "192.168.1.1", port = 80), proxy(ip = "10.0.0.5", port = 80))
        )

        val result = proxyDao.observeProxies(
            protocol = null, country = null, onlyWorking = false,
            onlyFavorites = false, query = "192.168", sortBy = "ADDED_DATE",
        ).first()

        assertEquals(1, result.size)
        assertEquals("192.168.1.1", result[0].ip)
    }

    @Test
    fun observeProxies_sortsByLatencyAscending() = runTest {
        val ids = proxyDao.insertAll(
            listOf(proxy(ip = "1.1.1.1", port = 80), proxy(ip = "2.2.2.2", port = 80), proxy(ip = "3.3.3.3", port = 80))
        )
        proxyDao.updateLatestCheck(ids[0], "WORKING", 300, System.currentTimeMillis())
        proxyDao.updateLatestCheck(ids[1], "WORKING", 50, System.currentTimeMillis())
        proxyDao.updateLatestCheck(ids[2], "WORKING", 150, System.currentTimeMillis())

        val result = proxyDao.observeProxies(
            protocol = null, country = null, onlyWorking = false,
            onlyFavorites = false, query = "", sortBy = "LATENCY",
        ).first()

        assertEquals(listOf("2.2.2.2", "3.3.3.3", "1.1.1.1"), result.map { it.ip })
    }

    @Test
    fun updateLatestCheck_updatesStatusLatencyAndTimestamp() = runTest {
        val ids = proxyDao.insertAll(listOf(proxy(ip = "1.1.1.1", port = 80)))
        val checkedAt = System.currentTimeMillis()

        proxyDao.updateLatestCheck(ids[0], "WORKING", 42, checkedAt)

        val updated = proxyDao.getById(ids[0])
        assertEquals("WORKING", updated?.latestStatus)
        assertEquals(42, updated?.latestLatencyMs)
        assertEquals(checkedAt, updated?.lastCheckedAt)
    }

    @Test
    fun getBestWorking_excludesGivenIdAndReturnsLowestLatency() = runTest {
        val ids = proxyDao.insertAll(
            listOf(proxy(ip = "1.1.1.1", port = 80), proxy(ip = "2.2.2.2", port = 80), proxy(ip = "3.3.3.3", port = 80))
        )
        proxyDao.updateLatestCheck(ids[0], "WORKING", 20, System.currentTimeMillis())  // lowest latency but excluded
        proxyDao.updateLatestCheck(ids[1], "WORKING", 90, System.currentTimeMillis())
        proxyDao.updateLatestCheck(ids[2], "FAILED", null, System.currentTimeMillis())

        val best = proxyDao.getBestWorking(excludeId = ids[0])

        assertEquals("2.2.2.2", best?.ip)
    }

    @Test
    fun getBestWorking_returnsNullWhenNoneWorking() = runTest {
        val ids = proxyDao.insertAll(listOf(proxy(ip = "1.1.1.1", port = 80)))
        proxyDao.updateLatestCheck(ids[0], "FAILED", null, System.currentTimeMillis())

        val best = proxyDao.getBestWorking(excludeId = -1L)

        assertEquals(null, best)
    }

    @Test
    fun setFavorite_persistsToggledState() = runTest {
        val ids = proxyDao.insertAll(listOf(proxy(ip = "1.1.1.1", port = 80)))

        proxyDao.setFavorite(ids[0], true)
        assertTrue(proxyDao.getById(ids[0])!!.isFavorite)

        proxyDao.setFavorite(ids[0], false)
        assertTrue(!proxyDao.getById(ids[0])!!.isFavorite)
    }

    @Test
    fun purgeStale_removesOnlyOldNonCustomProxies() = runTest {
        val now = System.currentTimeMillis()
        val oldNonCustom = proxy(ip = "1.1.1.1", port = 80, isCustom = false)
        val recentNonCustom = proxy(ip = "2.2.2.2", port = 80, isCustom = false)
        val oldCustom = proxy(ip = "3.3.3.3", port = 80, isCustom = true)

        val ids = proxyDao.insertAll(listOf(oldNonCustom, recentNonCustom, oldCustom))
        proxyDao.updateLatestCheck(ids[0], "FAILED", null, now - 100_000)
        proxyDao.updateLatestCheck(ids[1], "FAILED", null, now)
        proxyDao.updateLatestCheck(ids[2], "FAILED", null, now - 100_000)

        proxyDao.purgeStale(olderThan = now - 50_000)

        val remainingIps = proxyDao.observeProxies(
            protocol = null, country = null, onlyWorking = false,
            onlyFavorites = false, query = "", sortBy = "ADDED_DATE",
        ).first().map { it.ip }.toSet()

        // старый некастомный прокси удалён; недавний и кастомный (даже старый) — остались
        assertEquals(setOf("2.2.2.2", "3.3.3.3"), remainingIps)
    }

    @Test
    fun insertCustom_marksProxyAsCustom() = runTest {
        val id = proxyDao.insertCustom(proxy(ip = "7.7.7.7", port = 443, isCustom = true))

        val stored = proxyDao.getById(id)

        assertTrue(stored!!.isCustom)
    }

    @Test
    fun checkHistoryDao_observeHistory_returnsResultsNewestFirst() = runTest {
        val proxyId = proxyDao.insertAll(listOf(proxy(ip = "1.1.1.1", port = 80)))[0]
        val t1 = System.currentTimeMillis() - 2000
        val t2 = System.currentTimeMillis()

        checkHistoryDao.insert(checkResult(proxyId, checkedAt = t1, status = "FAILED"))
        checkHistoryDao.insert(checkResult(proxyId, checkedAt = t2, status = "WORKING"))

        val history = checkHistoryDao.observeHistory(proxyId).first()

        assertEquals(2, history.size)
        assertEquals("WORKING", history[0].status) // newest first
    }

    @Test
    fun geoCacheDao_upsertThenGet_returnsCachedCountry() = runTest {
        geoCacheDao.upsert(
            com.proxyhunter.telegram.data.local.entity.GeoCacheEntity(
                ip = "8.8.8.8", countryCode = "US", resolvedAt = System.currentTimeMillis(),
            )
        )

        val cached = geoCacheDao.get("8.8.8.8")

        assertEquals("US", cached?.countryCode)
    }

    @Test
    fun geoCacheDao_upsert_overwritesPreviousEntryForSameIp() = runTest {
        val ip = "8.8.8.8"
        geoCacheDao.upsert(
            com.proxyhunter.telegram.data.local.entity.GeoCacheEntity(ip, "US", System.currentTimeMillis())
        )
        geoCacheDao.upsert(
            com.proxyhunter.telegram.data.local.entity.GeoCacheEntity(ip, "DE", System.currentTimeMillis())
        )

        val cached = geoCacheDao.get(ip)

        assertEquals("DE", cached?.countryCode)
    }

    private fun proxy(
        ip: String,
        port: Int,
        protocol: String = "HTTP",
        isCustom: Boolean = false,
    ) = ProxyEntity(
        ip = ip,
        port = port,
        protocol = protocol,
        sourceUrl = "test-source",
        addedAt = System.currentTimeMillis(),
        isCustom = isCustom,
    )

    private fun checkResult(
        proxyId: Long,
        checkedAt: Long,
        status: String,
    ) = com.proxyhunter.telegram.data.local.entity.CheckResultEntity(
        proxyId = proxyId,
        checkedAt = checkedAt,
        status = status,
        latencyMs = null,
        telegramApiReachable = status == "WORKING",
    )
}
