package com.androidharness.app.browser
import android.webkit.WebView
import java.lang.ref.WeakReference
import kotlinx.coroutines.*
import org.json.JSONArray

/** Trusted instrumentation inspection; no eval/new Function and no new agent tool. */
internal suspend fun BrowserController.inspectLocalForTest(script:String):String = withContext(Dispatchers.Main) {
    val active=BrowserController::class.java.getDeclaredField("activeWebViewRef").apply{isAccessible=true}.get(this@inspectLocalForTest) as? WeakReference<*>
    val headless=BrowserController::class.java.getDeclaredField("headlessWebView").apply{isAccessible=true}.get(this@inspectLocalForTest) as? WebView
    val view=checkNotNull(active?.get() as? WebView ?: headless)
    val reply=CompletableDeferred<String>()
    view.evaluateJavascript(script){reply.complete(it ?: "null")}
    JSONArray("[${withTimeout(3000){reply.await()}}]").getString(0)
}
