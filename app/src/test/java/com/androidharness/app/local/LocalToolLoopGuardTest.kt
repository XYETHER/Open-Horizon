package com.androidharness.app.local
import com.androidharness.app.core.ToolCallData
import org.junit.Assert.*
import org.junit.Test

class LocalToolLoopGuardTest {
    private fun search(q:String)=ToolCallData("s","web_search","{\"query\":\"$q\"}")
    @Test fun `interleaved repeat is blocked before a third identical web request`() {
        val guard=LocalToolLoopGuard()
        assertNull(guard.repeatedRequest(listOf(search("Artist music"))))
        assertNull(guard.repeatedRequest(listOf(search("discography"))))
        assertNull(guard.repeatedRequest(listOf(search(" artist   MUSIC "))))
        assertEquals("web_search",guard.repeatedRequest(listOf(search("Artist music"))))
        assertNull(guard.repeatedRequest(listOf(search("new original source"))))
    }
    @Test fun `blocked whole batch does not consume requests that never ran`() {
        val guard=LocalToolLoopGuard();guard.repeatedRequest(listOf(search("a"),search("a")))
        assertNotNull(guard.repeatedRequest(listOf(search("b"),search("a"))))
        assertNull(guard.repeatedRequest(listOf(search("b"),search("b"))))
    }
    @Test fun `file verification is unrestricted and JSON key ordering cannot bypass guard`() {
        val guard=LocalToolLoopGuard()
        repeat(10) {assertNull(guard.repeatedRequest(listOf(ToolCallData("f","read_file","{\"path\":\"notes.txt\"}"))))}
        val a=ToolCallData("s","web_search","{\"count\":3,\"query\":\"a\"}")
        val b=a.copy(argumentsJson="{\"query\":\"a\",\"count\":3}")
        assertNull(guard.repeatedRequest(listOf(a,b)));assertNotNull(guard.repeatedRequest(listOf(b)))
    }
}
