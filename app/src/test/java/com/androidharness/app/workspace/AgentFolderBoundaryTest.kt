package com.androidharness.app.workspace

import com.androidharness.app.tools.ToolFailure
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentFolderBoundaryTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun traversalAbsoluteSiblingAndLegacyFullAccessStayBlocked() {
        val allowed = temp.newFolder("agent")
        val outside = temp.newFile("private.txt").apply { writeText("outside sentinel") }
        for (fs in listOf(FileFs(allowed), UnboundedFileFs(allowed))) {
            for (path in listOf("../private.txt", outside.absolutePath, "notes/../../private.txt")) {
                assertThrows(ToolFailure::class.java) { fs.resolve(path).readText() }
                assertThrows(ToolFailure::class.java) { fs.resolve(path).writeText("changed") }
            }
            fs.resolve("notes/hello.txt").writeText("allowed")
            assertEquals("allowed", fs.resolve("notes/hello.txt").readText())
        }
        assertEquals("outside sentinel", outside.readText())
    }
    @Test fun returnedNodesCannotEscapeThroughChildNames() {
        val allowed = temp.newFolder("agent")
        val fs = FileFs(allowed)
        val dir = fs.resolve(".")
        for (name in listOf("../escape", "nested/escape", "nested\\escape", "..")) {
            assertThrows(ToolFailure::class.java) { dir.createFile(name) }
            assertThrows(ToolFailure::class.java) { dir.createDir(name) }
        }
        val file = fs.resolve("allowed.txt").apply { writeText("kept") }
        assertThrows(ToolFailure::class.java) { file.renameTo("../escape") }
        assertEquals("kept", file.readText())
    }
    @Test fun symlinkAndResolvedNodeReplacementCannotReadOrWriteOutside() {
        val allowed = temp.newFolder("agent")
        val outside = temp.newFile("private.txt").apply { writeText("outside sentinel") }
        val fs = FileFs(allowed)
        val node = fs.resolve("future.txt")
        try { Files.createSymbolicLink(File(allowed,"future.txt").toPath(), outside.toPath()) }
        catch (e: Exception) { assumeNoException("Host does not allow symlink creation; real Android test covers it", e) }
        assertThrows(ToolFailure::class.java) { fs.resolve("future.txt") }
        assertThrows(ToolFailure::class.java) { node.readText() }
        assertThrows(ToolFailure::class.java) { node.writeText("changed") }
        assertEquals("outside sentinel", outside.readText())
    }
}
