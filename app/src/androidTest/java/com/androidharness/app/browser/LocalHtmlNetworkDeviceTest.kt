package com.androidharness.app.browser

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ImageStore
import com.androidharness.app.workspace.FileFs
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real background WebView: an executed secure WebSocket attempt must make no TCP connection. */
@RunWith(AndroidJUnit4::class)
class LocalHtmlNetworkDeviceTest {
    @Test fun headlessLocalHtmlBlocksWebSocketsBeforeConnecting() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val root=File(context.cacheDir,"headless-network-test-${System.nanoTime()}")
        val workspace=FileFs(root)
        val connections=AtomicInteger()
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1")).apply { soTimeout=100 }
        val listener=Thread {
            while(!server.isClosed) {
                try {server.accept().use {connections.incrementAndGet()}}
                catch(_:SocketTimeoutException) {}
                catch(error:Exception) {if(!server.isClosed)throw error}
            }
        }.apply {isDaemon=true;start()}
        File(context.cacheDir,"headless-network-check.json").writeText("{\"state\":\"started\"}")
        val browser=BrowserController(context,ImageStore(context))
        val probePath="probe-${System.nanoTime()}.html"
        try {
            workspace.resolve(probePath).writeText("""
                <!doctype html><html><head><title>Offline network probe</title></head><body>
                <h1>Local script ran</h1><script>
                window.probeRan=true;window.violations=[];
                document.addEventListener('securitypolicyviolation',e=>window.violations.push(e.effectiveDirective));
                try {window.probeSocket=new WebSocket('wss://127.0.0.1:${server.localPort}/probe');}
                catch(e) {window.probeException=String(e);}
                </script></body></html>
            """.trimIndent())
            browser.navigate(probePath,workspace)
            var observed=JSONObject()
            for(attempt in 0 until 30) {
                observed=JSONObject(browser.inspectLocalForTest("JSON.stringify({ran:!!window.probeRan,violations:window.violations||[]})"))
                if(observed.getJSONArray("violations").toString().contains("connect-src"))break
                delay(100)
            }
            val blockedLoads=java.util.concurrent.atomic.AtomicBoolean()
            instrumentation.runOnMainSync {
                val field=BrowserController::class.java.getDeclaredField("headlessWebView").apply {isAccessible=true}
                blockedLoads.set((field.get(browser) as WebView).settings.blockNetworkLoads)
            }
            File(context.cacheDir,"headless-network-check.json").writeText(JSONObject()
                .put("probe",observed).put("connections",connections.get()).put("blockNetworkLoads",blockedLoads.get()).toString(2))
            assertTrue("Probe JavaScript did not execute",observed.getBoolean("ran"))
            assertEquals("Secure WebSocket reached localhost",0,connections.get())
            assertTrue("No connect-src policy violation recorded",observed.getJSONArray("violations").toString().contains("connect-src"))
            delay(250)
            assertEquals("Delayed secure WebSocket reached localhost",0,connections.get())
            instrumentation.runOnMainSync {
                val field=BrowserController::class.java.getDeclaredField("headlessWebView").apply {isAccessible=true}
                assertTrue((field.get(browser) as WebView).settings.blockNetworkLoads)
            }
        } finally {
            server.close();listener.join(1000)
            instrumentation.runOnMainSync {
                val field=BrowserController::class.java.getDeclaredField("headlessWebView").apply {isAccessible=true}
                (field.get(browser) as? WebView)?.destroy()
            }
            root.deleteRecursively()
        }
    }
    /** Independent tester rendering of the exact saved model file under the final offline policy. */
    @Test fun savedModelHtmlRendersUnderOfflinePolicy() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.getString("artifact_case") in setOf("menu","voxel"))
        val label=requireNotNull(args.getString("artifact_case"))
        val app=instrumentation.targetContext.applicationContext as com.androidharness.app.HarnessApp
        val record=JSONObject(File(app.cacheDir,"horizon-toolcheck-$label.json").readText())
        val root=File(record.getString("workspace")).canonicalFile
        assertEquals(File(app.container.workspace.appPrivateRoot,"_validation").canonicalFile,root.parentFile)
        assertTrue(root.name.startsWith("k2-$label-"))
        assertTrue(File(root,"single.html").isFile)
        val browser=BrowserController(app,ImageStore(app))
        try {
            browser.navigate("single.html",FileFs(root))
            val result=browser.inspectLocalForTest("JSON.stringify({title:document.title,text:document.body.innerText,canvas:document.querySelectorAll('canvas').length})")
            val errors=browser.getLogs().filter{it.level.equals("error",true)}
            File(app.cacheDir,"horizon-offline-audit-$label.json").writeText(JSONObject()
                .put("ok",true).put("value",result).put("error",JSONObject.NULL)
                .put("consoleErrors",org.json.JSONArray(errors.map{it.message})).toString(2))
            browser.screenshot(FileFs(root))?.cachedFile?.copyTo(File(app.cacheDir,"horizon-offline-$label.jpg"),overwrite=true)
            assertTrue("Saved HTML has console errors under offline policy: $errors",errors.isEmpty())
            if(label=="voxel")assertTrue("No canvas",JSONObject(result).getInt("canvas")>0)
        } finally {
            instrumentation.runOnMainSync {
                val field=BrowserController::class.java.getDeclaredField("headlessWebView").apply{isAccessible=true}
                (field.get(browser) as? WebView)?.destroy()
            }
        }
    }

}
