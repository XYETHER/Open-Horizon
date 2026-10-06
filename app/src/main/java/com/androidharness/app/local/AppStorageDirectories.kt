package com.androidharness.app.local

import java.io.File

/** Stable named folders, with an in-place migration that never deletes old user data. */
object AppStorageDirectories {
    fun models(noBackupRoot: File): File = migrate(noBackupRoot, "HorizonMNN/models", "local-models")
    fun workspace(filesRoot: File): File {
        val legacy = File(filesRoot, "workspace")
        // Saved tasks and project tools can contain this absolute path; keep existing workspaces stable.
        if (legacy.isDirectory && legacy.listFiles().orEmpty().isNotEmpty()) return legacy
        return migrate(filesRoot, "HorizonMNN/workspace", "workspace")
    }
    private fun migrate(base: File, relative: String, legacyRelative: String): File {
        val target = File(base, relative)
        val legacy = File(base, legacyRelative)
        if (legacy.isDirectory && legacy.listFiles().orEmpty().isNotEmpty()) {
            if (!target.exists()) {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) { "Cannot create app storage folder." }
                if (legacy.renameTo(target)) return target
            }
            // If the move is unavailable or both roots contain data, keep using the existing folder.
            return legacy
        }
        check(target.isDirectory || target.mkdirs()) { "Cannot create app storage folder." }
        return target
    }
}

