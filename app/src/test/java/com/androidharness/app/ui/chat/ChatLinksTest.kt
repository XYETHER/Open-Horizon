package com.androidharness.app.ui.chat
import org.junit.Assert.*
import org.junit.Test
class ChatLinksTest {
 @Test fun plainUrlsAndUrlLabelsBecomeLinks() {
  assertEquals("URL:[example.com](https://example.com)",ChatLinks.linkify("URL:example.com"))
  assertEquals("See [https://example.com/a](https://example.com/a).",ChatLinks.linkify("See https://example.com/a."))
  assertEquals("[www.example.com](https://www.example.com)",ChatLinks.linkify("www.example.com"))
 }
 @Test fun markdownAndCodeStayIntact() {
  for(value in listOf("[Source](https://example.com)","`https://example.com`")) assertEquals(value,ChatLinks.linkify(value))
 }
 @Test fun executableAndDeviceSchemesCannotOpen() {
  assertNull(ChatLinks.safeUrl("javascript:alert(1)"));assertNull(ChatLinks.safeUrl("file:///sdcard/private"))
  assertNull(ChatLinks.safeUrl("https://name:pass@example.com"))
 }
}
