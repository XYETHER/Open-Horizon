package com.androidharness.app.local
import com.androidharness.app.core.*
import org.junit.Assert.*
import org.junit.Test

class LocalHtmlVerificationTest {
    private val available = setOf("browser_navigate","browser_get_dom","browser_get_logs")
    private fun pair(id: String, name: String, args: String = "{}", failed: Boolean = false): List<ChatMessage> = listOf(
        ChatMessage(Role.ASSISTANT,toolCalls=listOf(ToolCallData(id,name,args))),
        ChatMessage(Role.TOOL,"result",toolCallId=id,toolName=name,isError=failed))
    private val written = pair("write","write_file","""{"path":"site/index.html","content":"<h1>ok</h1>"}""")
    private val navigated = pair("nav","browser_navigate","""{"url":"site/index.html"}""")
    @Test fun explicitCreationCannotFinishWithOnlyARefusal() {
        val tools=setOf("write_file","read_file","browser_navigate","browser_get_dom","browser_get_logs")
        val messages=listOf(ChatMessage(Role.ASSISTANT,"I cannot create HTML."))
        assertTrue(LocalHtmlVerification.missingCheckPrompt(messages,tools,requiresCreation=true)!!.contains("write_file is available"))
        assertNull(LocalHtmlVerification.missingCheckPrompt(messages,tools,requiresCreation=false))
        assertNull(LocalHtmlVerification.missingCheckPrompt(messages,tools-setOf("write_file"),requiresCreation=true))
    }
    @Test fun navigationDoesNotSubstituteForConsoleAndDomChecks() {
        val prompt=LocalHtmlVerification.missingCheckPrompt(written+navigated,available)!!
        assertTrue(prompt.contains("browser_get_dom"));assertTrue(prompt.contains("browser_get_logs"))
    }
    @Test fun onlySuccessfulChecksAfterTheLastWriteCompleteVerification() {
        val checked=written+navigated+pair("dom","browser_get_dom")+pair("logs","browser_get_logs")
        assertNull(LocalHtmlVerification.missingCheckPrompt(checked,available))
        assertNotNull(LocalHtmlVerification.missingCheckPrompt(checked+written,available))
        assertNotNull(LocalHtmlVerification.missingCheckPrompt(written+navigated+pair("dom","browser_get_dom")+pair("logs","browser_get_logs",failed=true),available))
    }
    @Test fun unrelatedPageDoesNotVerifyTheSavedHtml() {
        assertNotNull(LocalHtmlVerification.missingCheckPrompt(written+pair("nav","browser_navigate","""{"url":"other.html"}""")+pair("dom","browser_get_dom")+pair("logs","browser_get_logs"),available))
    }
    @Test fun failedWritesOrARegistryWithoutBrowserDoNotTriggerFollowups() {
        assertNull(LocalHtmlVerification.missingCheckPrompt(pair("write","write_file","""{"path":"index.html"}""",true),available))
        assertNull(LocalHtmlVerification.missingCheckPrompt(written,emptySet()))
    }
}
