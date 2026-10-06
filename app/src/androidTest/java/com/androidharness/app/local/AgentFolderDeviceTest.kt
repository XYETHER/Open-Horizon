package com.androidharness.app.local

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.workspace.FileFs
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFolderDeviceTest {
    @Test fun traversalAndSymlinkEscapeAreBlockedOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File(context.cacheDir, "boundary-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val root = File(base,"agent").apply { mkdirs() }
            val outside = File(base,"outside.txt").apply { writeText("PRIVATE_SENTINEL") }
            val fs = FileFs(root)
            val previouslyResolved = fs.resolve("link.txt")
            Os.symlink(outside.absolutePath, File(root,"link.txt").absolutePath)
            listOf("../outside.txt", outside.absolutePath, "link.txt").forEach { path ->
                assertThrows(ToolFailure::class.java) { fs.resolve(path).readText() }
                assertThrows(ToolFailure::class.java) { fs.resolve(path).writeText("changed") }
            }
            assertThrows(ToolFailure::class.java) { previouslyResolved.readText() }
            assertThrows(ToolFailure::class.java) { previouslyResolved.writeText("changed") }
            fs.resolve("notes/allowed.txt").writeText("ALLOWED")
            assertEquals("ALLOWED", fs.resolve("notes/allowed.txt").readText())
            assertEquals("PRIVATE_SENTINEL", outside.readText())
        } finally {
            // Explicitly unlink the test-owned symlink; never recurse through its target.
            File(base,"agent/link.txt").delete()
            base.deleteRecursively()
        }
    }
    @Test fun persistedWorkspaceSelectionCannotWidenAgentAccess() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
        val manager = app.container.workspace
        val fs = manager.fsFor(ProjectEntity("validation", "Other folder", "SHELL", app.cacheDir.absolutePath, 0))
        assertEquals(manager.appPrivateRoot.canonicalPath, fs.shellRoot!!.canonicalPath)
        assertThrows(ToolFailure::class.java) { fs.resolve(app.cacheDir.absolutePath) }
        val manifest = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(manifest.requestedPermissions.orEmpty().contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertFalse(manifest.requestedPermissions.orEmpty().contains("moe.shizuku.manager.permission.API_V23"))
    }
    @Test fun publicResearchCannotFetchPhoneLocalHttp() {
        val client=com.androidharness.app.tools.PublicWebPolicy.client(okhttp3.OkHttpClient())
        val error=assertThrows(java.net.UnknownHostException::class.java) {
            client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:9/").build()).execute().use { }
        }
        assertTrue(error.message.orEmpty().contains("local/device"))
    }
}
