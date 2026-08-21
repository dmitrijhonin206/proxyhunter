package com.proxyhunter.telegram.export

import com.proxyhunter.telegram.data.export.ProxyExportSerializer
import com.proxyhunter.telegram.domain.model.Proxy
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProxyExportSerializerTest {

    private lateinit var serializer: ProxyExportSerializer

    @Before
    fun setUp() {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        serializer = ProxyExportSerializer(moshi)
    }

    @Test
    fun `json round-trip preserves all fields`() {
        val proxies = listOf(
            fakeProxy(ip = "1.1.1.1", protocol = ProxyProtocol.SOCKS5, username = "bob", password = "secret", country = "US", favorite = true),
            fakeProxy(ip = "2.2.2.2", protocol = ProxyProtocol.MTPROTO, secret = "ee" + "ab".repeat(16)),
        )

        val json = serializer.toJson(proxies)
        val result = serializer.fromJson(json)

        assertEquals(0, result.skippedRows)
        assertEquals(2, result.proxies.size)
        val first = result.proxies.first { it.ip == "1.1.1.1" }
        assertEquals(ProxyProtocol.SOCKS5, first.protocol)
        assertEquals("bob", first.username)
        assertEquals("secret", first.password)
        assertEquals("US", first.country)
        assertTrue(first.isFavorite)
        val second = result.proxies.first { it.ip == "2.2.2.2" }
        assertEquals(ProxyProtocol.MTPROTO, second.protocol)
        assertEquals("ee" + "ab".repeat(16), second.mtprotoSecret)
    }

    @Test
    fun `json import marks proxies as custom with import source`() {
        val json = serializer.toJson(listOf(fakeProxy(ip = "1.1.1.1")))

        val result = serializer.fromJson(json)

        assertTrue(result.proxies.single().isCustom)
        assertEquals("import", result.proxies.single().sourceUrl)
    }

    @Test
    fun `json with unknown protocol is skipped and counted`() {
        val json = """[{"ip":"1.1.1.1","port":1080,"protocol":"WIREGUARD"}]"""

        val result = serializer.fromJson(json)

        assertEquals(0, result.proxies.size)
        assertEquals(1, result.skippedRows)
    }

    @Test
    fun `malformed json returns empty result without throwing`() {
        val result = serializer.fromJson("{not valid json at all")

        assertEquals(0, result.proxies.size)
        assertEquals(0, result.skippedRows)
    }

    @Test
    fun `csv round-trip preserves fields including header`() {
        val proxies = listOf(
            fakeProxy(ip = "8.8.8.8", protocol = ProxyProtocol.HTTP, country = "DE"),
            fakeProxy(ip = "9.9.9.9", protocol = ProxyProtocol.SOCKS5, username = "alice"),
        )

        val csv = serializer.toCsv(proxies)
        assertTrue(csv.lines().first().startsWith("ip,port,protocol"))

        val result = serializer.fromCsv(csv)

        assertEquals(0, result.skippedRows)
        assertEquals(2, result.proxies.size)
        assertEquals("DE", result.proxies.first { it.ip == "8.8.8.8" }.country)
        assertEquals("alice", result.proxies.first { it.ip == "9.9.9.9" }.username)
    }

    @Test
    fun `csv field containing a comma is quoted on export and parsed back correctly`() {
        val proxy = fakeProxy(ip = "1.1.1.1", username = "user,with,commas")

        val csv = serializer.toCsv(listOf(proxy))
        val result = serializer.fromCsv(csv)

        assertEquals("user,with,commas", result.proxies.single().username)
    }

    @Test
    fun `csv field containing quotes is escaped on export and parsed back correctly`() {
        val proxy = fakeProxy(ip = "1.1.1.1", username = """say "hi" please""")

        val csv = serializer.toCsv(listOf(proxy))
        val result = serializer.fromCsv(csv)

        assertEquals("""say "hi" please""", result.proxies.single().username)
    }

    @Test
    fun `csv without header is still parsed`() {
        val csv = "1.1.1.1,1080,SOCKS5,,,,,false\n"

        val result = serializer.fromCsv(csv)

        assertEquals(1, result.proxies.size)
        assertEquals("1.1.1.1", result.proxies.single().ip)
    }

    @Test
    fun `csv row with invalid port is skipped and counted`() {
        val csv = "ip,port,protocol,username,password,secret,country,favorite\n1.1.1.1,not-a-port,SOCKS5,,,,,false\n"

        val result = serializer.fromCsv(csv)

        assertEquals(0, result.proxies.size)
        assertEquals(1, result.skippedRows)
    }

    @Test
    fun `csv row with too few columns is skipped and counted`() {
        val csv = "ip,port,protocol,username,password,secret,country,favorite\n1.1.1.1,1080\n"

        val result = serializer.fromCsv(csv)

        assertEquals(0, result.proxies.size)
        assertEquals(1, result.skippedRows)
    }

    @Test
    fun `csv row with unknown protocol is skipped and counted`() {
        val csv = "ip,port,protocol,username,password,secret,country,favorite\n1.1.1.1,1080,WIREGUARD,,,,,false\n"

        val result = serializer.fromCsv(csv)

        assertEquals(0, result.proxies.size)
        assertEquals(1, result.skippedRows)
    }

    @Test
    fun `empty csv content produces empty result`() {
        val result = serializer.fromCsv("")

        assertEquals(0, result.proxies.size)
        assertEquals(0, result.skippedRows)
    }

    private fun fakeProxy(
        ip: String,
        protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
        username: String? = null,
        password: String? = null,
        secret: String? = null,
        country: String? = null,
        favorite: Boolean = false,
    ) = Proxy(
        ip = ip,
        port = 1080,
        protocol = protocol,
        username = username,
        password = password,
        mtprotoSecret = secret,
        country = country,
        isFavorite = favorite,
        sourceUrl = "test",
        addedAt = System.currentTimeMillis(),
    )
}
