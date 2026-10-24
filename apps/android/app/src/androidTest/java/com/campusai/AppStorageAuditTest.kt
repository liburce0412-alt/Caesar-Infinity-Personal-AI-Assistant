package com.campusai

import android.app.usage.StorageStatsManager
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in metadata-only audit: no file contents, deletions, or health requests. */
@RunWith(AndroidJUnit4::class)
class AppStorageAuditTest {
    @Test fun reportOwnStorage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("storageAudit") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(line: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
        val stats = context.getSystemService(StorageStatsManager::class.java)
            .queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
        report("ANDROID_BYTES app=${stats.appBytes} data=${stats.dataBytes} cache=${stats.cacheBytes}")
        val roots = listOf(context.dataDir, context.createDeviceProtectedStorageContext().dataDir) +
            context.getExternalFilesDirs(null).mapNotNull { it?.parentFile } + context.externalMediaDirs.filterNotNull()
        for (root in roots.distinctBy { it.canonicalPath }.filter(File::exists)) {
            val groups = mutableMapOf<String, Long>()
            val allocatedGroups = mutableMapOf<String, Long>()
            val largeFiles = mutableListOf<Pair<String, Long>>()
            var total = 0L
            var count = 0
            root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                .onFail { file, error -> throw IllegalStateException("Cannot enumerate ${file.name}", error) }
                .filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }.forEach { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    val bytes = file.length()
                    val allocated = Os.stat(file.absolutePath).st_blocks * 512L
                    val group = relative.split('/').dropLast(1).take(3).joinToString("/").ifEmpty { "." }
                    groups[group] = (groups[group] ?: 0L) + bytes
                    allocatedGroups[group] = (allocatedGroups[group] ?: 0L) + allocated
                    total += bytes
                    count++
                    if (bytes >= 10L * 1024 * 1024) largeFiles += relative to bytes
                }
            assertTrue(total >= 0)
            report("ROOT ${root.absolutePath} bytes=$total files=$count")
            groups.toList().sortedByDescending { it.second }.forEach { (path, bytes) ->
                report("DIR logical=$bytes allocated=${allocatedGroups.getValue(path)} $path")
            }
            largeFiles.sortedByDescending { it.second }.take(12).forEach { (path, bytes) -> report("LARGE $bytes $path") }
        }
    }
}
