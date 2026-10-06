package com.androidharness.app.workspace

import com.androidharness.app.tools.ToolFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Legacy Full access aliases retain Horizon's folder boundary.
 */
class UnboundedFileFsTest {

    private fun tempRoot(): File = createTempDirectory("harness-unbounded").toFile()

    @Test
    fun `relative path still resolves inside workspace root`() {
        val root = tempRoot()
        try {
            File(root, "sub/a.txt").apply { parentFile.mkdirs(); writeText("inside") }
            val fs = UnboundedFileFs(root)
            assertEquals("inside", fs.resolve("sub/a.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dotdot escape remains blocked in legacy full access`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-outside").toFile()
        try {
            val outside = File(outsideDir, "secret.txt")
            outside.writeText("outside content")

            // Both regular and legacy resolvers enforce the same boundary.
            assertThrows(ToolFailure::class.java) { FileFs(root).resolve("../secret.txt") }
            val fs = UnboundedFileFs(root)
            assertThrows(ToolFailure::class.java) { fs.resolve("../${outsideDir.name}/secret.txt") }
            assertEquals("outside content", outside.readText())
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `absolute path outside workspace is blocked`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-outside2").toFile()
        try {
            val outside = File(outsideDir, "data.bin")
            outside.writeText("abc")
            val fs = UnboundedFileFs(root)
            assertThrows(ToolFailure::class.java) { fs.resolve(outside.absolutePath) }
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `shell root and display stay the workspace`() {
        val root = tempRoot()
        try {
            val fs = UnboundedFileFs(root)
            assertEquals(root.canonicalPath, fs.shellRoot.canonicalPath)
            assertEquals(FileFs(root).displayPath, fs.displayPath)
            assertFalse(fs.isSaf)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `walk applies ignore rules and refuses upward traversal`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-walkout").toFile()
        try {
            File(root, "in.txt").writeText("a")
            File(outsideDir, "node_modules/pkg.js").apply { parentFile.mkdirs(); writeText("junk") }
            File(outsideDir, "keep.txt").writeText("b")

            val fs = UnboundedFileFs(root)
            assertThrows(ToolFailure::class.java) { fs.walk("../${outsideDir.name}") }
            File(root, "node_modules/pkg.js").apply { parentFile.mkdirs(); writeText("junk") }
            val names = fs.walk(".").map { it.name }.toList()
            assertTrue(names.contains("in.txt"))
            assertFalse(names.any { it == "pkg.js" || it == "node_modules" })
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `walk throws for missing path`() {
        val root = tempRoot()
        try {
            assertThrows(ToolFailure::class.java) { UnboundedFileFs(root).walk("./nope") }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `external node cannot be obtained`() {
        val root = tempRoot()
        val outsideDir = createTempDirectory("harness-rel").toFile()
        try {
            val f = File(outsideDir, "x.txt"); f.writeText("n")
            assertThrows(ToolFailure::class.java) { UnboundedFileFs(root).resolve(f.absolutePath) }
        } finally {
            root.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }
}
