package com.androidharness.app.ui.chat

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.ui.chat.components.AssistantText
import com.androidharness.app.ui.chat.components.HtmlArtifactActions
import com.androidharness.app.ui.chat.components.HtmlPreviewScreen
import com.androidharness.app.workspace.FileFs
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real completion action and native viewer; no model or inference is involved. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HtmlPreviewDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun completionActionRendersRelativeAssetsAndBlocksRemoteRequests() {
        val root = File(instrumentation.targetContext.cacheDir, "html-preview-test-${System.nanoTime()}")
        val workspace = FileFs(root)
        val receivedRequests = AtomicInteger()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 250 }
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        receivedRequests.incrementAndGet()
                        val body = "window.remoteScriptLoaded = true;"
                        socket.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n" +
                                "Access-Control-Allow-Origin: *\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                                "Connection: close\r\n\r\n$body").toByteArray(),
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    // Check whether teardown closed the listener.
                } catch (error: Exception) {
                    if (!server.isClosed) throw error
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            workspace.resolve("my site/index.html").writeText(
                """
                <!doctype html><html><head><meta charset="utf-8"><title>Loading fixture</title>
                <link rel="stylesheet" href="assets/style.css"></head><body>
                <h1 id="result">Waiting for JavaScript</h1><script src="assets/app.js"></script>
                </body></html>
                """.trimIndent(),
            )
            workspace.resolve("my site/assets/style.css").writeText("#result { color: rgb(17, 34, 51); }")
            workspace.resolve("my site/assets/app.js").writeText(
                """
                document.title = 'Local HTML ready';
                document.getElementById('result').textContent = 'Relative JavaScript ran';
                window.remoteScriptLoaded = false;
                window.remoteScriptBlocked = false;
                window.remoteFetchBlocked = false;
                const remote = 'http://127.0.0.1:${server.localPort}/remote.js';
                const script = document.createElement('script');
                script.src = remote;
                script.onerror = () => { window.remoteScriptBlocked = true; };
                script.onload = () => { window.remoteScriptLoaded = true; };
                document.head.appendChild(script);
                fetch(remote).then(() => { window.remoteFetchLoaded = true; })
                    .catch(() => { window.remoteFetchBlocked = true; });
                """.trimIndent(),
            )
            val messages = listOf(
                ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData(
                    "html-write", "write_file", """{"path":"my site/index.html","content":"fixture"}""",
                )), turnId = "turn"),
                ChatMessage(Role.TOOL, "Created my site/index.html", toolCallId = "html-write",
                    toolName = "write_file", turnId = "turn"),
                ChatMessage(Role.ASSISTANT, "Done. Your page is saved.", turnId = "turn"),
            )
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        MaterialTheme {
                            var previewPath by remember { mutableStateOf<String?>(null) }
                            Column {
                                AssistantText(messages.last().text)
                                HtmlArtifactActions(messages, workspace, onOpen = { previewPath = it })
                            }
                            previewPath?.let { HtmlPreviewScreen(it, workspace) { previewPath = null } }
                        }
                    }
                }
                clickAction("View HTML")
                var webView: WebView? = null
                awaitCondition("HTML action did not open the native WebView") {
                    instrumentation.runOnMainSync {
                        webView = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findWebView)
                    }
                    webView != null
                }
                var rendered: JsonObject? = null
                awaitCondition("Relative HTML/CSS/JavaScript did not render: $rendered") {
                    rendered = pageState(requireNotNull(webView))
                    rendered?.get("title")?.jsonPrimitive?.content == "Local HTML ready" &&
                        rendered?.get("text")?.jsonPrimitive?.content == "Relative JavaScript ran" &&
                        rendered?.get("color")?.jsonPrimitive?.content == "rgb(17, 34, 51)" &&
                        rendered?.get("scriptBlocked")?.jsonPrimitive?.content == "true" &&
                        rendered?.get("fetchBlocked")?.jsonPrimitive?.content == "true"
                }
                val state = requireNotNull(rendered)
                assertEquals("https://harness.workspace/ws/my%20site/index.html", state["url"]?.jsonPrimitive?.content)
                assertEquals("false", state["scriptLoaded"]?.jsonPrimitive?.content)
                assertEquals("Remote script/fetch reached the network", 0, receivedRequests.get())
                instrumentation.runOnMainSync {
                    assertTrue(requireNotNull(webView).settings.blockNetworkLoads)
                    assertFalse(requireNotNull(webView).settings.allowFileAccess)
                    assertFalse(requireNotNull(webView).settings.allowContentAccess)
                }
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(instrumentation.targetContext.cacheDir, "html-preview-verified.png").outputStream().use { output ->
                        assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally {
                    screenshot.recycle()
                }
            }
        } finally {
            server.close()
            serverThread.join(1_000)
            root.deleteRecursively()
        }
    }

    private fun pageState(webView: WebView): JsonObject? {
        val result = AtomicReference<String?>()
        val finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                """
                JSON.stringify({
                  title: document.title, url: location.href,
                  text: document.getElementById('result')?.textContent,
                  color: document.getElementById('result') ? getComputedStyle(document.getElementById('result')).color : '',
                  scriptBlocked: !!window.remoteScriptBlocked, fetchBlocked: !!window.remoteFetchBlocked,
                  scriptLoaded: !!window.remoteScriptLoaded
                })
                """.trimIndent(),
            ) { value -> result.set(value); finished.countDown() }
        }
        assertTrue("WebView evaluation did not return", finished.await(3, TimeUnit.SECONDS))
        return runCatching {
            Json.parseToJsonElement(Json.parseToJsonElement(requireNotNull(result.get())).jsonPrimitive.content).jsonObject
        }.getOrNull()
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun clickAction(text: String) {
        var node: AccessibilityNodeInfo? = null
        awaitCondition("Missing completion action: $text") {
            node = instrumentation.uiAutomation.rootInActiveWindow?.let { findAction(it, text) }
            node != null
        }
        var target = requireNotNull(node)
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue("Cannot click $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun findAction(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains(text) == true) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { findAction(it, text)?.let { match -> return match } }
        return null
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(message)
    }
}
