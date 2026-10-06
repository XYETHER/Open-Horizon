package com.androidharness.app.ui.chat

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real text tap dispatch, intercepted before any external browser/network opens. */
@RunWith(AndroidJUnit4::class)
class ChatLinksDeviceTest {
 @Test fun plainUrlTapOpensHttpsIntent() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val filter=IntentFilter(Intent.ACTION_VIEW).apply {addDataScheme("https")}
  val monitor=instrumentation.addMonitor(filter,Instrumentation.ActivityResult(0,Intent()),true)
  try {
   ActivityScenario.launch(ChatListTestActivity::class.java).use {scenario ->
    scenario.onActivity {activity -> activity.setContent {MaterialTheme {Surface {Box(Modifier.padding(horizontal=24.dp,vertical=80.dp)) {MarkdownText("URL:example.com")}}}}}
    var bounds:Rect?=null
    for(i in 0 until 50) {
     fun find(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {
      if(n.text?.toString()?.contains("example.com")==true)return n
      for(j in 0 until n.childCount)n.getChild(j)?.let {find(it)?.let { match -> return match }}
      return null
     }
     val node=instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
     if(node!=null) {bounds=Rect().also {node.getBoundsInScreen(it)};break}
     Thread.sleep(100)
    }
    assertNotNull("URL did not render",bounds)
    val b=bounds!!;val now=SystemClock.uptimeMillis()
    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
     val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,b.centerX().toFloat(),b.centerY().toFloat(),0)
     try {assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
    }
    for(i in 0 until 30) {if(monitor.hits>0)break;Thread.sleep(100)}
    assertEquals("Plain URL did not dispatch a clickable HTTPS link",1,monitor.hits)
   }
  } finally {instrumentation.removeMonitor(monitor)}
 }
}
