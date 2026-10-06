package com.androidharness.app.local
import com.androidharness.app.core.*
import com.androidharness.app.agent.AssistantMode
import org.junit.Assert.*
import org.junit.Test
class LocalWebVerificationTest {
    private val names=setOf("web_search","web_fetch")
    private fun result(name:String,key:String,value:String,id:String)=listOf(
        ChatMessage(Role.ASSISTANT,"",toolCalls=listOf(ToolCallData(id,name,"{\"$key\":\"$value\"}"))),
        ChatMessage(Role.TOOL,"https://example.com/ " + "real source text ".repeat(20),toolCallId=id,toolName=name))
    @Test fun `ordinary chat can finish without a forced page read or citation`() {
        val searched=result("web_search","query","devday","s")+ChatMessage(Role.ASSISTANT,"September 29, 2026. Would you like more details?")
        assertNull(LocalWebVerification.missingCheckPrompt(searched,names,AssistantMode.CHAT))
        assertNull(LocalWebVerification.missingCheckPrompt(searched+result("web_fetch","url","https://example.com/a","f"),names,AssistantMode.CHAT))
    }
    @Test fun `unrelated file task needs no web checks`() { assertNull(LocalWebVerification.missingCheckPrompt(emptyList(),names,AssistantMode.RESEARCH)) }
    @Test fun `search snippet cannot stand in for page read`() {
        assertTrue(LocalWebVerification.missingCheckPrompt(result("web_search","query","topic","s"),names,AssistantMode.RESEARCH)!!.contains("call web_fetch"))
    }
    @Test fun `citation must reference actually fetched page`() {
        val m=(1..3).flatMap {result("web_search","query","topic$it","s$it")}+result("web_fetch","url","https://example.com/a","f")+result("web_fetch","url","https://other.com/b","f2")+result("web_fetch","url","https://third.com/c","f3")
        assertNotNull(LocalWebVerification.missingCheckPrompt(m+ChatMessage(Role.ASSISTANT,"A fact https://invented.invalid"),names,AssistantMode.RESEARCH))
        assertNull(LocalWebVerification.missingCheckPrompt(m+ChatMessage(Role.ASSISTANT,"A fact https://example.com/a"),names,AssistantMode.RESEARCH))
    }
    @Test fun `deep research requires distinct queries pages and domains`() {
        val m=result("web_search","query","topic","s")+result("web_fetch","url","https://example.com/a","f")
        assertTrue(LocalWebVerification.missingCheckPrompt(m,names,AssistantMode.RESEARCH)!!.contains("3 different queries"))
        val full=m+result("web_search","query","releases","s2")+result("web_search","query","credits","s3")+result("web_fetch","url","https://example.com/b","f2")+result("web_fetch","url","https://other.com/c","f3")+ChatMessage(Role.ASSISTANT,"Report https://other.com/c")
        assertNull(LocalWebVerification.missingCheckPrompt(full,names,AssistantMode.RESEARCH))
    }
    @Test fun `research search loops get only two spaced source reading reminders`() {
        val m=(1..6).flatMap { result("web_search","query","repeated topic","s$it") }
        assertNull(LocalWebVerification.readingProgressPrompt(m.take(4),names,AssistantMode.RESEARCH,0))
        assertTrue(LocalWebVerification.readingProgressPrompt(m.take(6),names,AssistantMode.RESEARCH,0)!!.contains("next call should be web_fetch"))
        assertNull(LocalWebVerification.readingProgressPrompt(m.take(10),names,AssistantMode.RESEARCH,1))
        assertNotNull(LocalWebVerification.readingProgressPrompt(m,names,AssistantMode.RESEARCH,1))
        assertNull(LocalWebVerification.readingProgressPrompt(m,names,AssistantMode.RESEARCH,2))
        assertNull(LocalWebVerification.readingProgressPrompt(m,names,AssistantMode.CHAT,0))
    }
    @Test fun `actual page reading ends the search loop reminder`() {
        val m=(1..3).flatMap { result("web_search","query","topic$it","s$it") }
        val read=(1..3).flatMap { result("web_fetch","url","https://example.com/$it","f$it") }
        assertNull(LocalWebVerification.readingProgressPrompt(m+read,names,AssistantMode.RESEARCH,0))
        val failed=read.map { if(it.role==Role.TOOL) it.copy(isError=true) else it }
        assertNotNull(LocalWebVerification.readingProgressPrompt(m+failed,names,AssistantMode.RESEARCH,0))
    }

    @Test fun `saved research report must cite actual sources and be reread after editing`() {
        val evidence=(1..3).flatMap { result("web_search","query","query$it","s$it") } +
            result("web_fetch","url","https://example.com/a","f1") +
            result("web_fetch","url","https://other.com/b","f2") +
            result("web_fetch","url","https://third.com/c","f3")
        val tools=names+setOf("write_file","read_file")
        val written=result("write_file","path","report.txt","w")
        val uncited=result("read_file","path","report.txt","r").map { if(it.role==Role.TOOL)it.copy(text="Long uncited report ".repeat(40)) else it }
        assertTrue(LocalWebVerification.missingCheckPrompt(evidence+written+uncited,tools,AssistantMode.RESEARCH)!!.contains("missing source links"))
        val cited=uncited.map { if(it.role==Role.TOOL)it.copy(text="Report: https://example.com/a https://other.com/b") else it }
        val final=ChatMessage(Role.ASSISTANT,"Done. Source https://example.com/a")
        assertNull(LocalWebVerification.missingCheckPrompt(evidence+written+cited+final,tools,AssistantMode.RESEARCH))
        assertTrue(LocalWebVerification.missingCheckPrompt(evidence+written+cited+result("edit_file","path","report.txt","e")+final,tools,AssistantMode.RESEARCH)!!.contains("read_file"))
    }
}
