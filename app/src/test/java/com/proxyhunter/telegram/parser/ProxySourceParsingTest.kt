package com.proxyhunter.telegram.parser

import com.proxyhunter.telegram.data.remote.source.HtmlTableSource
import com.proxyhunter.telegram.data.remote.source.JsonListSource
import com.proxyhunter.telegram.data.remote.source.JsonProxyDto
import com.proxyhunter.telegram.data.remote.source.PlainTextListSource
import com.proxyhunter.telegram.domain.model.ProxyProtocol
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ProxySourceParsingTest {

    private lateinit var server: MockWebServer
    private val httpClient = OkHttpClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `plain text source parses ip colon port lines`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                192.168.1.1:1080
                10.0.0.5:8080
                not-a-valid-line
                203.0.113.9:443:https
                """.trimIndent()
            )
        )
        val source = PlainTextListSource(server.url("/list.txt").toString(), ProxyProtocol.SOCKS5, httpClient)

        val result = source.fetch()

        assertEquals(3, result.size)
        assertEquals("192.168.1.1", result[0].ip)
        assertEquals(1080, result[0].port)
        assertEquals(ProxyProtocol.SOCKS5, result[0].protocol) // default applied
        assertEquals(ProxyProtocol.HTTPS, result[2].protocol)  // explicit protocol in line overrides default
    }

    @Test
    fun `plain text source ignores malformed lines without crashing`() = runTest {
        server.enqueue(MockResponse().setBody("garbage\n\n:::\n1.2.3.4:99999999"))
        val source = PlainTextListSource(server.url("/list.txt").toString(), ProxyProtocol.HTTP, httpClient)

        val result = source.fetch()

        assertEquals(0, result.size)
    }

    @Test
    fun `json source parses proxy objects including mtproto secret`() = runTest {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, JsonProxyDto::class.java)
        val adapter = moshi.adapter<List<JsonProxyDto>>(type)

        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"ip":"1.2.3.4","port":443,"protocol":"mtproto","secret":"ee1234567890"},
                  {"ip":"5.6.7.8","port":1080,"protocol":"socks5","country":"NL"}
                ]
                """.trimIndent()
            )
        )
        val source = JsonListSource(server.url("/list.json").toString(), httpClient, adapter)

        val result = source.fetch()

        assertEquals(2, result.size)
        assertEquals(ProxyProtocol.MTPROTO, result[0].protocol)
        assertEquals("ee1234567890", result[0].mtprotoSecret)
        assertEquals("NL", result[1].country)
    }

    @Test
    fun `json source returns empty list on malformed json`() = runTest {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(List::class.java, JsonProxyDto::class.java)
        val adapter = moshi.adapter<List<JsonProxyDto>>(type)

        server.enqueue(MockResponse().setBody("{not valid json"))
        val source = JsonListSource(server.url("/list.json").toString(), httpClient, adapter)

        assertEquals(0, source.fetch().size)
    }

    @Test
    fun `html table source extracts rows via css selector`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                <html><body>
                <table>
                  <tr class="proxy-row"><td>8.8.8.8</td><td>3128</td><td>US</td></tr>
                  <tr class="proxy-row"><td>9.9.9.9</td><td>invalid-port</td><td>DE</td></tr>
                </table>
                </body></html>
                """.trimIndent()
            )
        )
        val source = HtmlTableSource(
            sourceUrl = server.url("/list.html").toString(),
            rowSelector = "tr.proxy-row",
            defaultProtocol = ProxyProtocol.HTTP,
            httpClient = httpClient,
        )

        val result = source.fetch()

        assertEquals(1, result.size) // second row skipped: invalid port
        assertEquals("8.8.8.8", result[0].ip)
        assertEquals(3128, result[0].port)
        assertEquals("US", result[0].country)
    }
}
