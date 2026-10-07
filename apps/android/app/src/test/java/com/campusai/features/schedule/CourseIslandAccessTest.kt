package com.campusai.features.schedule

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class CourseIslandAccessTest {
    private val clean = """
        TrafficController
        BPF map content:
          current ownerMatch configuration: 0 NO_MATCH
          sCookieTagMap:
          sUidOwnerMap:
            10001 DOZABLE_MATCH IIF_MATCH 4
            10002 NO_MATCH
          sUidPermissionMap:
            10001 PERMISSION_INTERNET
    """.trimIndent()

    @Test fun `guard accepts a complete known map but rejects dormant or unreadable rules`() {
        assertEquals(0, runGuard(clean).first)
        // HyperOS 4 framework: AUROGON_MATCH=32768, distinct from OEM_DENY_3_MATCH=2048.
        assertEquals(0, runGuard(clean.replace("10002 NO_MATCH", "10002 AUROGON_MATCH")).first)
        val unsafe = listOf(
            clean.replace("10002 NO_MATCH", "10002 OEM_DENY_3_MATCH"),
            clean.replace("10002 NO_MATCH", "10002 UNKNOWN_MATCH(2048)"),
            clean.replace("10002 NO_MATCH", "Map dump end with error: Permission denied"),
            clean.replace("10002 NO_MATCH", "Entry is deleted while dumping, iterating from first entry"),
            clean.substringBefore("sUidPermissionMap:"),
            clean.replace("sUidOwnerMap:", "uid_owner_map:"),
            clean.replace("10002 NO_MATCH", "unexpected output"),
            "x".repeat(131_073) + clean,
        )
        unsafe.forEach { assertNotEquals("Unsafe map must not enable the firewall", 0, runGuard(it).first) }
        assertNotEquals("A timed-out dump is not complete even when its prefix parses", 0, runGuard(clean, 124).first)
    }

    @Test fun `a refused lease never mutates any network rule`() {
        val result = runGuard(clean.replace("10002 NO_MATCH", "10002 OEM_DENY_3_MATCH"), fullLease = true)
        assertNotEquals(0, result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test fun `successful lease restores once before acknowledging completion`() {
        val result = runGuard(clean, fullLease = true)
        assertEquals(0, result.first)
        assertEquals(listOf(
            "set-package-networking-enabled false com.xiaomi.xmsf",
            "set-chain3-enabled true",
            "set-package-networking-enabled true com.xiaomi.xmsf",
            "set-chain3-enabled false",
        ), result.second)
    }

    @Test fun `recovery failure cannot escape a successful notification delivery`() {
        var posted: Boolean? = null
        var recoveredFailure: Exception? = null
        CourseIslandAccess.deliver(AutoCloseable { throw IOException("binder disconnected") },
            { posted = it }, { recoveredFailure = it })
        assertEquals(true, posted)
        assertTrue(recoveredFailure is IOException)
    }

    @Test fun `missing lease uses normal notification and a failed post still closes the lease`() {
        var native: Boolean? = null
        CourseIslandAccess.deliver(null, { native = it }, { fail("No lease to recover") })
        assertEquals(false, native)
        var closed = false
        try {
            CourseIslandAccess.deliver(AutoCloseable { closed = true }, { throw IllegalStateException("post failed") }, { fail() })
            fail("Posting failure must remain visible to the caller")
        } catch (_: IllegalStateException) { assertTrue(closed) }
    }

    /** Execute the production shell guard with isolated command fixtures, never Android services. */
    private fun runGuard(dump: String, dumpExit: Int = 0, fullLease: Boolean = false): Pair<Int, List<String>> {
        val bash = if (System.getProperty("os.name").startsWith("Windows")) File("C:/Program Files/Git/bin/bash.exe") else File("/bin/bash")
        assumeTrue("Bash is required for the shell guard contract tests", bash.isFile)
        val directory = Files.createTempDirectory("course-island-guard-").toFile()
        try {
            File(directory, "dump").writeText(dump)
            File(directory, "chain").writeText("chain:disabled\n")
            File(directory, "package").writeText("com.xiaomi.xmsf:allow\n")
            val root = directory.absolutePath.replace('\\', '/').replace("'", "'\"'\"'")
            val fixture = """
                fixture='$root'
                dumpsys() { cat "${'$'}fixture/dump"; return $dumpExit; }
                timeout() { shift; "${'$'}@"; }
                cmd() {
                  shift
                  case "${'$'}1" in
                    get-chain3-enabled) cat "${'$'}fixture/chain" ;;
                    get-package-networking-enabled) cat "${'$'}fixture/package" ;;
                    set-chain3-enabled)
                      printf '%s\n' "${'$'}*" >> "${'$'}fixture/mutations"
                      if [ "${'$'}2" = true ]; then echo chain:enabled; else echo chain:disabled; fi > "${'$'}fixture/chain" ;;
                    set-package-networking-enabled)
                      printf '%s\n' "${'$'}*" >> "${'$'}fixture/mutations"
                      if [ "${'$'}2" = true ]; then echo com.xiaomi.xmsf:allow; else echo com.xiaomi.xmsf:deny; fi > "${'$'}fixture/package" ;;
                    *) return 99 ;;
                  esac
                }
            """.trimIndent()
            val script = fixture + "\n" + if (fullLease) CourseIslandAccess.SCRIPT else NETWORK_GUARD_SCRIPT + "\nclean_owner_map"
            // Windows Java and Git Bash do not preserve nested shell quotes through -c.
            // Pass a real UTF-8 script file so the production parameter expansions reach Bash intact.
            val scriptFile = File(directory, "guard-fixture.sh").apply { writeText(script) }
            val process = ProcessBuilder(bash.absolutePath, scriptFile.absolutePath.replace('\\', '/'))
                .redirectErrorStream(true).start()
            process.outputStream.close()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            assertTrue("Guard script must finish", completed)
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() != 0 && output.isNotBlank()) System.err.println("Guard fixture rejected: $output")
            if (fullLease && process.exitValue() == 0) assertEquals("READY\nRESTORED", output.trim())
            return process.exitValue() to File(directory, "mutations").takeIf { it.exists() }?.readLines().orEmpty()
        } finally { directory.deleteRecursively() }
    }
}
