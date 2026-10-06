package com.androidharness.app.tools

import com.androidharness.app.browser.BrowserController
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalBrowserBoundaryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun networkDeviceContentAndTraversalTargetsNeverReachWebView() = runBlocking {
        val type=Class.forName("sun.misc.Unsafe")
        val field=type.getDeclaredField("theUnsafe").apply{isAccessible=true}
        // Browser is deliberately uninitialized: calling into it would fail this check.
        val browser=type.getMethod("allocateInstance",Class::class.java).invoke(field.get(null),BrowserController::class.java) as BrowserController
        val tool=BrowserNavigateTool(browser)
        val ctx=ToolContext(FileFs(temp.root),sandboxOff=true)
        for(target in listOf("https://example.com/page.html","http://127.0.0.1/private.html","file:///sdcard/private.html","content://provider/secret.html","/data/user/0/private.html","../escape.html","folder\\..\\escape.html")) {
            val result=tool.execute(buildJsonObject{put("url",target)},ctx)
            assertFalse("Target must be rejected: $target",result.ok)
        }
    }
}
