package com.androidharness.app.local
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
import java.io.File

class K2TokenizerTest {
 private fun asset():File = File("src/main/assets/k2/tokenizer.json").takeIf {it.exists()} ?: File("app/src/main/assets/k2/tokenizer.json")
 @Test fun exactOriginalIdsIncludeNfcZwjUnicodeAndEveryAddedToken() {
  val tokenizer=K2Tokenizer(asset().readText())
  val rows=Json.parseToJsonElement(javaClass.getResource("/k2-tokenizer-cases.json")!!.readText()).jsonArray
  for(row in rows) {
   val r=row.jsonObject
   val ids=r.getValue("ids").jsonArray.map {it.jsonPrimitive.int}
   assertEquals(r.getValue("decoded").jsonPrimitive.content,ids.flatMap {tokenizer.decode(it).toList()}.toByteArray().toString(Charsets.UTF_8))
   assertArrayEquals(r.getValue("text").jsonPrimitive.content,r.getValue("ids").jsonArray.map {it.jsonPrimitive.int}.toIntArray(),tokenizer.encode(r.getValue("text").jsonPrimitive.content))
  }
 }
 @Test fun originalChatTemplateFramesAndThreeThinkingProfiles() {
  val rows=Json.parseToJsonElement(javaClass.getResource("/k2-prompt-cases.json")!!.readText()).jsonArray
  for(row in rows) {
   val r=row.jsonObject;val roles=r.getValue("roles").jsonArray.map {it.jsonPrimitive.content}.toTypedArray()
   val texts=r.getValue("contents").jsonArray.map {it.jsonPrimitive.content.toByteArray()}.toTypedArray()
   assertEquals(r.getValue("prompt").jsonPrimitive.content,K2MnnPrompt.render(roles,texts,r.getValue("effort").jsonPrimitive.content))
  }
 }
 @Test fun bothMnnEntriesRetainDistinctStorageAndPinnedDownloadHashes() {
  assertEquals(2,LocalModelCatalog.mnnModels.size)
  val model=LocalModelCatalog.k2MnnModel
  assertEquals("MNN_BUNDLE",model.format)
  assertEquals(model.bytes,model.artifacts.sumOf {it.bytes})
  assertTrue(model.artifacts.all {it.sha256.matches(Regex("[a-f0-9]{64}"))})
  assertEquals(model,LocalModelCatalog.find(model.id))
 }
}
