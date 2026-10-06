package com.androidharness.app.tools
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebFetchPageTest {
    @get:Rule val tmp=TemporaryFolder()
    @Test fun `page tool retains actual URL title date and strips uppercase scripts`()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type","text/html").setBody("<TITLE>Artist &amp; Music</TITLE><SCRIPT>hidden()</SCRIPT><STYLE>hidden{}</STYLE><p>Actual artist credit</p>"))
            server.start()
            val url=server.url("/artist").toString()
            val result=WebFetchTool(OkHttpClient()).execute(buildJsonObject{put("url",url)},ToolContext(FileFs(tmp.root)))
            assertTrue(result.ok);assertTrue(result.output.contains("Source URL: $url"))
            assertTrue(result.output.contains("Page title: Artist & Music"));assertTrue(result.output.contains("Retrieved:"))
            assertTrue(result.output.contains("Actual artist credit"));assertFalse(result.output.contains("hidden"))
        }
    }
    @Test fun `oversized text is bounded before extraction`()=runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type","text/plain").setBody("x".repeat(4*1024*1024+10)))
            server.start()
            val result=WebFetchTool(OkHttpClient()).execute(buildJsonObject { put("url",server.url("/large").toString()) },ToolContext(FileFs(tmp.root)))
            assertFalse(result.ok);assertTrue(result.output.contains("4 MiB"))
        }
    }
}
