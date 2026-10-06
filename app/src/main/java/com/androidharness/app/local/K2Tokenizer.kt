package com.androidharness.app.local

import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.regex.Pattern

/** Original pinned K2 NFC/Unicode byte-BPE. MNN receives these exact IDs. */
internal class K2Tokenizer(source:String) {
 private val config=Json.parseToJsonElement(source).jsonObject
 private val vocab=config.getValue("model").jsonObject.getValue("vocab").jsonObject.mapValues {it.value.jsonPrimitive.int}
 private val ranks=config.getValue("model").jsonObject.getValue("merges").jsonArray.mapIndexed {i,v ->
  val pair=v.jsonArray; (pair[0].jsonPrimitive.content to pair[1].jsonPrimitive.content) to i
 }.toMap()
 private val added=config.getValue("added_tokens").jsonArray.associate {v ->
  val item=v.jsonObject
  require(listOf("single_word","lstrip","rstrip","normalized").none {item.getValue(it).jsonPrimitive.boolean}) {"Unsupported added-token flags"}
  item.getValue("content").jsonPrimitive.content to item.getValue("id").jsonPrimitive.int
 }
 private val special=Pattern.compile(added.keys.sortedByDescending {it.length}.joinToString("|") {Pattern.quote(it)})
 // Android lacks UNICODE_CHARACTER_CLASS. Spell out Rust regex's Unicode White_Space.
 private val split=Pattern.compile(config.getValue("pre_tokenizer").jsonObject.getValue("pretokenizers").jsonArray[0].jsonObject.getValue("pattern").jsonObject.getValue("Regex").jsonPrimitive.content.replace("""\s""", """[\x09-\x0D\x20\x85\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]"""))
 private val alphabet=Array(256){""}.also {a ->
  var extra=256
  for(b in 0..255)a[b]=(if(b in 33..126 || b in 161..172 || b in 174..255)b else extra++).toChar().toString()
 }
 private val byId=vocab.entries.associate {it.value to it.key} + added.entries.associate {it.value to it.key}
 private val specialIds=added.values.toSet()
 private val reverseBytes=alphabet.mapIndexed {i,s -> s.single() to i.toByte()}.toMap()
 fun decode(id:Int):ByteArray {
  val token=requireNotNull(byId[id]) {"Unknown K2 token $id"}
  return if(id in specialIds)token.toByteArray(Charsets.UTF_8) else token.map {requireNotNull(reverseBytes[it])}.toByteArray()
 }
 fun encode(text:String):IntArray {
  val result=mutableListOf<Int>();val matcher=special.matcher(text);var position=0
  while(matcher.find()) {
   plain(text.substring(position,matcher.start()),result)
   result.add(added.getValue(matcher.group()));position=matcher.end()
  }
  plain(text.substring(position),result);return result.toIntArray()
 }
 private fun plain(raw:String,out:MutableList<Int>) {
  val text=Normalizer.normalize(raw,Normalizer.Form.NFC);val matcher=split.matcher(text);var end=0
  while(matcher.find()) {
   if(matcher.start()>end)bpe(text.substring(end,matcher.start()),out)
   bpe(matcher.group(),out);end=matcher.end()
  }
  if(end<text.length)bpe(text.substring(end),out)
 }
 private fun bpe(text:String,out:MutableList<Int>) {
  var pieces=text.toByteArray(Charsets.UTF_8).map {alphabet[it.toInt() and 255]}
  while(pieces.size>1) {
   var best:Int?=null;var pair:Pair<String,String>?=null
   for(i in 0 until pieces.size-1) {
    val candidate=pieces[i] to pieces[i+1];val rank=ranks[candidate] ?: continue
    if(best==null || rank<best){best=rank;pair=candidate}
   }
   if(pair==null)break
   val next=mutableListOf<String>();var i=0
   while(i<pieces.size) {
    if(i+1<pieces.size && pieces[i]==pair.first && pieces[i+1]==pair.second){next.add(pieces[i]+pieces[i+1]);i+=2}
    else {next.add(pieces[i]);i++}
   }
   pieces=next
  }
  pieces.forEach {out.add(vocab.getValue(it))}
 }
}

internal object K2MnnPrompt {
 fun opening(effort:String)=when(effort){"high"->"<ifm|think>\n";"low"->"<ifm|think_faster>\n";else->"<ifm|think_fast>\n"}
 fun render(roles:Array<String>,contents:Array<ByteArray>,effort:String):String {
  require(roles.size==contents.size && roles.isNotEmpty())
  return buildString {
   for(i in roles.indices) {
    val role=roles[i];require(role in setOf("system","user","assistant","tool"))
    var content=contents[i].toString(Charsets.UTF_8)
    content=content.replace("<|ifm|im_start|>","[message boundary]").replace("<|ifm|im_end|>","[message boundary]")
    append("<|ifm|im_start|>").append(role)
    append('\n')
    append(content).append("<|ifm|im_end|>")
   }
   append("<|ifm|im_start|>assistant\n").append(opening(effort))
  }
 }
}
