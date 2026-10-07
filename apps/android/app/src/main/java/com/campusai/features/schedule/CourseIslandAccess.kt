package com.campusai.features.schedule

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku

/** Optional shell lease; unknown firewall state always falls back to an ordinary notification. */
internal object CourseIslandAccess {
    private val lock = Any()
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun ready(): Boolean = running() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun awaitReady(): Boolean {
        // Alarm delivery can start our process before Shizuku's provider receives its binder.
        val deadline = SystemClock.elapsedRealtime() + 1_000
        while (!running() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        return ready()
    }
    fun requestPermission() { if (running()) Shizuku.requestPermission(7410) }

    fun publish(post: (Boolean) -> Unit) = synchronized(lock) {
        if (!ready()) { post(false); return@synchronized }
        val lease = runCatching { acquire() }.onFailure { Log.w("CourseIsland", "Native island access unavailable", it) }.getOrNull()
        deliver(lease, post) { Log.w("CourseIsland", "Network lease recovery failed", it) }
    }

    internal fun deliver(lease: AutoCloseable?, post: (Boolean) -> Unit, recoveryFailed: (Exception) -> Unit) {
        if (lease == null) { post(false); return }
        try { post(true) }
        finally {
            // An optional integration must not cancel the next course alarm when its binder dies.
            try { lease.close() } catch (failure: Exception) { recoveryFailed(failure) }
        }
    }

    private fun shell(script: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(null, arrayOf("sh", "-c", script), null, null) as Process
    }

    private fun acquire(): AutoCloseable? {
        val process = shell(SCRIPT)
        val reader = process.inputStream.bufferedReader()
        val deadline = SystemClock.elapsedRealtime() + 4_000
        while (!reader.ready() && SystemClock.elapsedRealtime() < deadline) {
            if (runCatching { process.exitValue() }.isSuccess) break
            Thread.sleep(10)
        }
        if (!reader.ready() || reader.readLine() != "READY") {
            runCatching { process.outputStream.close() } // EOF releases an acquired lease.
            runCatching { reader.close() }
            return null
        }
        return AutoCloseable {
            val restored = runCatching {
                process.outputStream.bufferedWriter().use { it.write("posted\n"); it.flush() }
                val restoreDeadline = SystemClock.elapsedRealtime() + 3_000
                while (!reader.ready() && SystemClock.elapsedRealtime() < restoreDeadline) Thread.sleep(10)
                reader.ready() && reader.readLine() == "RESTORED"
            }.getOrDefault(false)
            runCatching { reader.close() }
            if (!restored) {
                // Do not perform an unbounded read if the Shizuku service is stopped mid-publish.
                val recovery = shell(RESTORE)
                val deadline = SystemClock.elapsedRealtime() + 2_000
                var result: Int? = null
                while (SystemClock.elapsedRealtime() < deadline) {
                    result = runCatching { recovery.exitValue() }.getOrNull()
                    if (result != null) break
                    Thread.sleep(10)
                }
                Log.w("CourseIsland", "Network lease recovery command exit=$result (null means timeout)")
            }
        }
    }

    private const val RESTORE = "timeout 1 cmd connectivity set-package-networking-enabled true com.xiaomi.xmsf >/dev/null 2>&1; timeout 1 cmd connectivity set-chain3-enabled false >/dev/null 2>&1"
    internal val SCRIPT = """
        $NETWORK_GUARD_SCRIPT
        [ "${'$'}(timeout 1 cmd connectivity get-chain3-enabled)" = 'chain:disabled' ] || exit 2
        [ "${'$'}(timeout 1 cmd connectivity get-package-networking-enabled com.xiaomi.xmsf)" = 'com.xiaomi.xmsf:allow' ] || exit 2
        clean_owner_map || exit 2
        # Recheck immediately before mutation. This is not an atomic lock against other apps.
        [ "${'$'}(timeout 1 cmd connectivity get-chain3-enabled)" = 'chain:disabled' ] || exit 2
        [ "${'$'}(timeout 1 cmd connectivity get-package-networking-enabled com.xiaomi.xmsf)" = 'com.xiaomi.xmsf:allow' ] || exit 2
        restore() { $RESTORE; }
        trap restore EXIT HUP INT TERM
        (sleep 2; restore) &
        guard=${'$'}!
        timeout 1 cmd connectivity set-package-networking-enabled false com.xiaomi.xmsf >/dev/null 2>&1
        timeout 1 cmd connectivity set-chain3-enabled true >/dev/null 2>&1
        if [ "${'$'}(timeout 1 cmd connectivity get-chain3-enabled)" != 'chain:enabled' ] ||
           [ "${'$'}(timeout 1 cmd connectivity get-package-networking-enabled com.xiaomi.xmsf)" != 'com.xiaomi.xmsf:deny' ]; then
          restore
          kill "${'$'}guard" 2>/dev/null
          exit 3
        fi
        echo READY
        read -r -t 1 result
        sleep 0.2
        restore
        kill "${'$'}guard" 2>/dev/null
        if [ "${'$'}(timeout 1 cmd connectivity get-chain3-enabled)" = 'chain:disabled' ] &&
           [ "${'$'}(timeout 1 cmd connectivity get-package-networking-enabled com.xiaomi.xmsf)" = 'com.xiaomi.xmsf:allow' ]; then
          trap - EXIT HUP INT TERM
          echo RESTORED
        fi
    """.trimIndent()
}

// AOSP BpfNetMaps.dump / BpfDump.dumpMap expose the complete UID owner map to shell.
// A disabled OEM_DENY_3 can still contain dormant deny rules. Enabling it would apply all
// of them (and may close their TCP sockets), so never infer safety from XMSF alone.
internal val NETWORK_GUARD_SCRIPT = """
    clean_owner_map() {
      dump=${'$'}({ timeout 2 dumpsys connectivity trafficcontroller 2>/dev/null; printf '\n__CAESAR_DUMP_EXIT_%s__\n' "${'$'}?"; } | head -c 131073)
      [ "${'$'}{#dump}" -le 131072 ] || return 1
      case "${'$'}dump" in
        *'Map dump end with error'*|*'Entry is deleted while dumping'*|*'Failed to read'*|*'UNKNOWN_MATCH('*|*'OEM_DENY_3_MATCH'*) return 1 ;;
      esac
      case "${'$'}dump" in *'__CAESAR_DUMP_EXIT_0__') ;; *) return 1 ;; esac
      traffic=0; content=0; owner=0; complete=0; inside=0
      set -f
      while IFS= read -r line; do
        line="${'$'}{line#"${'$'}{line%%[![:space:]]*}"}"
        line="${'$'}{line%"${'$'}{line##*[![:space:]]}"}"
        case "${'$'}line" in
          TrafficController) traffic=${'$'}((traffic + 1)); continue ;;
          'BPF map content:') content=${'$'}((content + 1)); continue ;;
          'sUidOwnerMap:') owner=${'$'}((owner + 1)); inside=1; continue ;;
          'sUidPermissionMap:')
            [ "${'$'}inside" = 1 ] || return 1
            complete=${'$'}((complete + 1)); inside=0; continue ;;
        esac
        [ "${'$'}inside" = 1 ] || continue
        [ -n "${'$'}line" ] || continue
        set -- ${'$'}line
        case "${'$'}1" in ''|*[!0-9]*) return 1 ;; esac
        shift
        [ "${'$'}#" -gt 0 ] || return 1
        iif=0; number=0
        for rule do
          [ "${'$'}number" = 0 ] || return 1
          case "${'$'}rule" in
            NO_MATCH) [ "${'$'}#" = 1 ] || return 1 ;;
            IIF_MATCH) iif=1 ;;
            HAPPY_BOX_MATCH|PENALTY_BOX_MATCH|PENALTY_BOX_USER_MATCH|PENALTY_BOX_ADMIN_MATCH|DOZABLE_MATCH|STANDBY_MATCH|POWERSAVE_MATCH|RESTRICTED_MATCH|LOW_POWER_STANDBY_MATCH|LOCKDOWN_VPN_MATCH|OEM_DENY_1_MATCH|OEM_DENY_2_MATCH|BACKGROUND_MATCH|AUROGON_MATCH) ;;
            *[!0-9]*|'') return 1 ;;
            *) [ "${'$'}iif" = 1 ] || return 1; number=1 ;;
          esac
        done
        [ "${'$'}iif" = "${'$'}number" ] || return 1
      done <<__CAESAR_MAP__
    ${'$'}dump
    __CAESAR_MAP__
      [ "${'$'}traffic" = 1 ] && [ "${'$'}content" = 1 ] && [ "${'$'}owner" = 1 ] && [ "${'$'}complete" = 1 ]
    }
""".trimIndent()
