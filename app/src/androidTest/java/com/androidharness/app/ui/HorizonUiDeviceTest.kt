package com.androidharness.app.ui

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.MainActivity
import com.androidharness.app.HarnessApp
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Files -> native preview route, no model generation or user chat edits. */
@RunWith(AndroidJUnit4::class)
class HorizonUiDeviceTest {
    @get:Rule val activity=ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun find(label:String):AccessibilityNodeInfo? {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if(root.packageName?.toString()!=instrumentation.targetContext.packageName)return null
        fun walk(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.text?.toString()==label || node.contentDescription?.toString()==label)return node
            for(i in 0 until node.childCount)node.getChild(i)?.let { walk(it)?.let { match -> return match } }
            return null
        }
        return walk(root)
    }
    private fun await(label:String):AccessibilityNodeInfo {
        val until=System.currentTimeMillis()+10_000
        while(System.currentTimeMillis()<until) {find(label)?.let {return it};Thread.sleep(100)}
        error("Own Horizon UI did not show $label")
    }
    private fun click(label:String) {
        var node:AccessibilityNodeInfo?=await(label)
        while(node!=null) {
            if(node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return
            node=node.parent
        }
        error("No clickable parent for $label")
    }
    private fun screenshot(name:String) {
        assertEquals(instrumentation.targetContext.packageName,instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir,name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private suspend fun previewText():String=withContext(Dispatchers.Main) {
        fun findView(view:View):WebView? {
            if(view is WebView)return view
            if(view is ViewGroup)for(i in 0 until view.childCount)findView(view.getChildAt(i))?.let {return it}
            return null
        }
        val view=WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull(::findView) ?: return@withContext ""
        val value=CompletableDeferred<String>()
        view.evaluateJavascript("document.body.innerText") {value.complete(it ?: "")}
        withTimeout(3000) {value.await()}
    }
    @Test fun runSettingsShowsResourcesAndPersistsBudgetAndProfile()=runBlocking {
        val c=(instrumentation.targetContext.applicationContext as HarnessApp).container
        val original=c.localModels.limits("k2-horizon-09b");val profile=c.settings.settings.first().thinkingLevel
        try {
            click("Run settings");await("Thinking profile");await("Deep")
            fun editable(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                if(node.isEditable)return node
                for(i in 0 until node.childCount)node.getChild(i)?.let {editable(it)?.let { found -> return found }}
                return null
            }
            val node=editable(instrumentation.uiAutomation.rootInActiveWindow) ?: error("No output budget field")
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"1536")}))
            click("Deep");screenshot("horizon-082-run-settings.png");click("Apply")
            for(i in 0 until 50) {if(c.localModels.limits("k2-horizon-09b").output==1536)break;delay(100)}
            assertEquals(1536,c.localModels.limits("k2-horizon-09b").output)
            assertEquals(com.androidharness.app.agent.ThinkingLevel.HIGH,c.settings.settings.first().thinkingLevel)
            assertEquals(original.kvCache,c.localModels.limits("k2-horizon-09b").kvCache)
        } finally {c.localModels.saveLimits("k2-horizon-09b",original);c.settings.setThinkingLevel(profile)}
    }

    @Test fun filesOpenHtmlPreviewWithoutChangingSourceOrUserChats()=runBlocking {
        val app=instrumentation.targetContext.applicationContext as HarnessApp
        val root=File(app.container.workspace.appPrivateRoot,"_ui-check-080-${System.nanoTime()}").apply {mkdirs()}
        val html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>Horizon preview</title></head><body><h1>UI_PREVIEW_080</h1><p>Local HTML preview from My files.</p></body></html>"
        val file=File(root,"single.html").apply {writeText(html)}
        try {
            // Context is visible and opens its usage details without creating a chat.
            var contextNode:AccessibilityNodeInfo?=null
            for(i in 0 until 30) {
                fun scan(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
                    if(n.text?.toString()?.startsWith("Context ")==true)return n
                    for(j in 0 until n.childCount)n.getChild(j)?.let {scan(it)?.let { found -> return found }}
                    return null
                }
                contextNode=instrumentation.uiAutomation.rootInActiveWindow?.let(::scan)
                if(contextNode!=null)break
                delay(100)
            }
            assertNotNull("Context usage absent from chat header",contextNode)
            var clickable=contextNode!!
            while(!clickable.isClickable && clickable.parent!=null)clickable=clickable.parent
            assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("Context and Usage");click("Close")
            click("Task options");click("My files");await("Your agent folder")
            screenshot("horizon-080-files.png")
            click(root.name);click("Preview single.html");await("Close")
            var value=""
            for(i in 0 until 30) {value=previewText();if(value.contains("UI_PREVIEW_080"))break;delay(100)}
            assertTrue("Native preview did not render the actual workspace HTML: $value",value.contains("UI_PREVIEW_080"))
            screenshot("horizon-080-preview.png")
            assertEquals(html,file.readText())
            click("Close")
        } finally {
            assertEquals(app.container.workspace.appPrivateRoot.canonicalFile,root.canonicalFile.parentFile)
            root.deleteRecursively()
        }
    }
}
