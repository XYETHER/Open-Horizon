package com.androidharness.app.local
import com.androidharness.app.agent.AssistantMode
import com.androidharness.app.core.*
import org.junit.Assert.*
import org.junit.Test
class HtmlTaskIntentTest {
 @Test fun explicitHtmlCreationPromotesChat() {
  for(text in listOf("Create a full fancy Italian restraunt menu in a single .html","make a restaurant menu","write single.html","Build a webpage")) assertEquals(text,AssistantMode.CODE,HtmlTaskIntent.mode(text,AssistantMode.CHAT))
 }
 @Test fun explanationsAndOrdinaryChatRemainChat() {
  for(text in listOf("How do I create HTML?","Explain how to build a website","When was DevDay?","What is on an Italian menu?"))assertEquals(text,AssistantMode.CHAT,HtmlTaskIntent.mode(text,AssistantMode.CHAT))
 }
 @Test fun explicitResearchModeIsPreserved() {assertEquals(AssistantMode.RESEARCH,HtmlTaskIntent.mode("Create HTML",AssistantMode.RESEARCH))}
 @Test fun resumeRetainsOriginalCreationIntent() {
  val m=listOf(ChatMessage(Role.USER,"Create single.html"),ChatMessage(Role.USER,"Resume the interrupted task from saved progress. Inspect results."))
  assertEquals("Create single.html",HtmlTaskIntent.task(m))
 }
}
