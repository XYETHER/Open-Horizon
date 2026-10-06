package com.androidharness.app.tools
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
class PublicWebPolicyTest {
    @Test fun `private device and LAN addresses are blocked`() {
        listOf("127.0.0.1","0.0.0.0","10.1.2.3","172.16.1.2","192.168.1.1","169.254.169.254","100.64.0.1","198.18.0.1","224.0.0.1","::1","fc00::1","fe80::1").forEach { assertFalse(it,PublicWebPolicy.isPublic(InetAddress.getByName(it))) }
    }
    @Test fun `public IPv4 and IPv6 remain reachable`() {
        listOf("1.1.1.1","8.8.8.8","2606:4700:4700::1111").forEach { assertTrue(it,PublicWebPolicy.isPublic(InetAddress.getByName(it))) }
    }
    @Test fun `numeric localhost is rejected before connection`() {
        MockWebServer().use { server ->
            server.start()
            val client=PublicWebPolicy.client(OkHttpClient())
            assertThrows(UnknownHostException::class.java) { client.newCall(Request.Builder().url("http://127.0.0.1:${server.port}/private").build()).execute().use { } }
            assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS))
        }
    }
    @Test fun `redirect to numeric localhost is rejected before next request`() {
        var requests=0
        val client=PublicWebPolicy.client(OkHttpClient()).newBuilder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location","http://127.0.0.1:9/private").body("".toResponseBody()).build()
        }.build()
        assertThrows(UnknownHostException::class.java) { client.newCall(Request.Builder().url("https://1.1.1.1/start").build()).execute().use { } }
        assertEquals(1,requests)
    }
}
