package com.androidharness.app.browser

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.workspace.FileFs
import java.net.URI
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HtmlArtifactsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `only completed matching successful HTML write and edit calls produce artifacts`() {
        val messages = listOf(
            ChatMessage(Role.ASSISTANT, "Done. View missing.html"),
            call("a", "write_file", "site/index.html"),
            result("a", "write_file"),
            call("b", "edit_file", "site/index.html"),
            result("b", "edit_file"),
            call("c", "write_file", "failed.html"),
            result("c", "write_file", isError = true),
            call("d", "write_file", "pending.html"),
            call("e", "read_file", "existing.html"),
            result("e", "read_file"),
            call("f", "write_file", "mismatched.html"),
            result("f", "edit_file"),
            call("g", "write_file", "note.md"),
            result("g", "write_file"),
            call("h", "write_file", "wrong-turn.html"),
            result("h", "write_file").copy(turnId = "another-turn"),
        )
        assertEquals(listOf(HtmlArtifact("site/index.html", "b", "turn")), HtmlArtifacts.successfulWrites(messages))
    }

    @Test fun `HTML action requires an existing workspace file and disappears after deletion`() {
        val workspace = FileFs(tmp.root)
        workspace.resolve("site/index.html").writeText("<html>Saved</html>")
        workspace.resolve("directory.html").mkdirs()
        val messages = listOf(
            call("saved", "write_file", "site/index.html"), result("saved", "write_file"),
            call("missing", "write_file", "missing.html"), result("missing", "write_file"),
            call("dir", "write_file", "directory.html"), result("dir", "write_file"),
        )
        assertEquals(listOf("site/index.html"), HtmlArtifacts.existing(messages, workspace).map { it.path })
        assertTrue(workspace.resolve("site/index.html").delete())
        assertTrue(HtmlArtifacts.existing(messages, workspace).isEmpty())
    }

    @Test fun `malformed arguments and out of workspace paths never produce HTML actions`() {
        for (path in listOf("../secret.html", "site/../../secret.html", "/sdcard/secret.html", "https://example.com/index.html", "site\\index.html")) {
            assertTrue(HtmlArtifacts.successfulWrites(listOf(call("a", "write_file", path), result("a", "write_file"))).isEmpty())
        }
        val invalid = ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("a", "write_file", "{bad")), turnId = "turn")
        assertTrue(HtmlArtifacts.successfulWrites(listOf(invalid, result("a", "write_file"))).isEmpty())
        assertEquals("site/index.HTML", HtmlPreviewPolicy.htmlPath("./site/index.HTML"))
        assertEquals("site/index.html", HtmlPreviewPolicy.htmlPath("/workspace/site/index.html", "/workspace"))
        assertNull(HtmlPreviewPolicy.htmlPath("/workspace-other/index.html", "/workspace"))
    }

    @Test fun `relative CSS and JS URLs resolve under the same workspace origin including spaces`() {
        val page = URI(HtmlPreviewPolicy.localFileUrl("my site/index.html"))
        assertEquals("my site/index.html", HtmlPreviewPolicy.workspaceRequestPath(page.toString()))
        assertEquals("my site/css/style.css", HtmlPreviewPolicy.workspaceRequestPath(page.resolve("css/style.css").toString()))
        assertEquals("my site/app.js", HtmlPreviewPolicy.workspaceRequestPath(page.resolve("app.js?v=1#ready").toString()))
        assertEquals("shared.css", HtmlPreviewPolicy.workspaceRequestPath(page.resolve("/shared.css").toString()))
        assertEquals("my site/a+b.js", HtmlPreviewPolicy.workspaceRequestPath(page.resolve("a+b.js").toString()))
    }

    @Test fun `remote origins file URIs and encoded traversal cannot become local assets`() {
        for (url in listOf(
            "https://example.com/ws/index.html", "http://harness.workspace/ws/index.html",
            "https://harness.workspace.evil/ws/index.html", "https://user@harness.workspace/ws/index.html",
            "https://harness.workspace:8080/ws/index.html", "file:///sdcard/index.html", "content://tree/index.html",
            "https://harness.workspace/ws/../secret", "https://harness.workspace/ws/%2e%2e/secret",
            "https://harness.workspace/ws/site%2f..%2fsecret", "https://harness.workspace/ws/site%5csecret",
            "https://harness.workspace/ws/%00secret", "https://harness.workspace/ws/%zz",
        )) assertNull(url, HtmlPreviewPolicy.workspaceRequestPath(url))
    }

    private fun call(id: String, name: String, path: String) = ChatMessage(
        Role.ASSISTANT,
        toolCalls = listOf(ToolCallData(id, name, buildJsonObject { put("path", path) }.toString())),
        turnId = "turn",
    )

    private fun result(id: String, name: String, isError: Boolean = false) = ChatMessage(
        Role.TOOL, text = "Tool finished", toolCallId = id, toolName = name, isError = isError, turnId = "turn",
    )
}
