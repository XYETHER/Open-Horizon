package com.androidharness.app.local
import com.androidharness.app.core.*
import org.junit.Assert.*
import org.junit.Test

class ResearchEvidenceTest {
    private fun page(id:String,url:String,text:String,error:Boolean=false)=listOf(
        ChatMessage(Role.ASSISTANT,"",toolCalls=listOf(ToolCallData(id,"web_fetch","{\"url\":\"$url\"}"))),
        ChatMessage(Role.TOOL,text,toolCallId=id,toolName="web_fetch",isError=error))
    @Test fun `only successful actual page reads become sources`() {
        val good=page("a","https://example.com/a","Read page ".repeat(30))
        val bad=page("b","https://example.com/b","Access denied ".repeat(30),true)
        val binary=page("c","https://example.com/c","[binary content: application/pdf, 999 bytes]"+"x".repeat(100))
        val prose=listOf(ChatMessage(Role.ASSISTANT,"Source https://invented.invalid"))
        assertEquals(listOf("https://example.com/a"),ResearchEvidence.sources(good+bad+binary+prose).map { it.url })
    }
    @Test fun `actual redirect URL and title survive independently of assistant citation`() {
        val m=page("a","https://example.com/old","Source URL: https://example.com/new\nPage title: Artist catalog\nRetrieved: date\n\n"+"body ".repeat(30))
        assertEquals(ReadSource("https://example.com/new","Artist catalog"),ResearchEvidence.sources(m).single())
        assertEquals(1,ResearchEvidence.sources(m+page("b","https://example.com/other",m.last().text)).size)
    }
    @Test fun `metadata alone cannot count as a source page read`() {
        val metadata="Source URL: https://example.com/very/long/url\nPage title: A long title "+"x".repeat(200)+"\nRetrieved: today\n\n"
        assertTrue(ResearchEvidence.sources(page("a","https://example.com",metadata)).isEmpty())
    }
    @Test fun `malformed metadata cannot create a clickable local destination`() {
        assertTrue(ResearchEvidence.sources(page("a","https://example.com","Source URL: file:///private\n"+"body ".repeat(30))).isEmpty())
    }
}
