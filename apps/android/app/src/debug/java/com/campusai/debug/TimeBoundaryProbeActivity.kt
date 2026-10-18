package com.campusai.debug

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.health.HealthFreshness
import com.campusai.core.health.HealthMetrics
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthSnapshot
import com.campusai.core.health.mifitness.MiFitnessSummaryHealthGateway
import com.campusai.core.time.CaesarTime
import com.campusai.core.time.rememberCaesarDate
import com.campusai.features.ai.CaesarHealthUiState
import com.campusai.features.ai.MiFitnessUiStatus
import com.campusai.features.ai.currentCloudHealthDisclosure
import com.campusai.features.ai.forCurrentHealthDay
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/** Shell-only debug fixture: real Compose/lifecycle, synthetic date and health, no user data or network IO. */
class TimeBoundaryProbeActivity : ComponentActivity() {
    private var scenario by mutableStateOf("")
    private var generation by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!startScenario(intent)) { finish(); return }
        setContent {
            key(generation) {
                val run = remember { ProbeRun(scenario, File(requireNotNull(getExternalFilesDir(null)), "time-boundary-probe")) }
                val date by rememberCaesarDate(run.clock)
                DisposableEffect(run) {
                    val observer = LifecycleEventObserver { _, event -> run.recordLifecycle(event) }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                LaunchedEffect(date) { run.recordDate(date) }
                val now = run.clock.millis()
                val current = run.health.forCurrentHealthDay(now)
                MaterialTheme {
                    Column(Modifier.padding(24.dp)) {
                        Text("受控日期模拟（非自然午夜）", style = MaterialTheme.typography.titleLarge)
                        Text("场景：${run.scenario} · 独立 debug 进程")
                        Text("北京时间日期：$date")
                        Text("合成今日步数：${current.snapshot?.metrics?.steps ?: "无当日数据"}")
                        Text("合成同步提示：${current.actionMessage ?: "已清除旧日提示"}")
                        Text("未同意时：不披露合成健康摘要")
                        Text("无真实健康读取、无网络请求；报告：${run.reportFile.name}")
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // No scenario means resume the same Activity/clock after Home, not a fresh fixture.
        if (intent.hasExtra("scenario")) startScenario(intent)
    }

    private fun startScenario(intent: Intent): Boolean {
        val requested = intent.getStringExtra("scenario")
        if (!intent.getBooleanExtra("explicit_opt_in", false) || requested !in setOf("foreground", "resume")) return false
        scenario = requested!!
        generation++
        return true
    }

    private class ProbeRun(val scenario: String, root: File) {
        val clock = RunningClock()
        val reportFile = File(root, "$scenario.json")
        private val firstDate = CaesarTime.today(clock.instant())
        private val nextDate = firstDate.plusDays(1)
        private val dayStart = firstDate.atStartOfDay(CaesarTime.zone).toInstant().toEpochMilli()
        private val boundary = nextDate.atStartOfDay(CaesarTime.zone).toInstant().toEpochMilli()
        private val initialMillis = clock.millis()
        val health = CaesarHealthUiState(
            miFitnessConfigured = true, miFitnessStatus = MiFitnessUiStatus.SUCCESS,
            miFitnessLastSyncAt = initialMillis, actionMessage = "合成今日已同步",
            snapshot = HealthSnapshot(setOf(MiFitnessSummaryHealthGateway.SOURCE_ID),
                HealthPeriod(dayStart, boundary - 1L, "today"), initialMillis, initialMillis,
                HealthFreshness.FRESH, HealthMetrics(steps = 123), emptySet(), 1.0),
        )
        private val events = JSONArray()
        private val observations = JSONArray()
        private var beforePassed = false
        private var afterPassed = false
        private var stoppedBeforeBoundary = false
        private var startedAfterBoundary = false
        private var stoppedBeforeDateUpdate = false

        fun recordLifecycle(event: Lifecycle.Event) {
            val now = clock.millis()
            events.put(JSONObject().put("event", event.name).put("controlledEpochMillis", now)
                .put("systemEpochMillis", System.currentTimeMillis()))
            if (event == Lifecycle.Event.ON_STOP && now < boundary) stoppedBeforeBoundary = true
            if (event == Lifecycle.Event.ON_STOP && !afterPassed) stoppedBeforeDateUpdate = true
            if (event == Lifecycle.Event.ON_START && now >= boundary && stoppedBeforeBoundary) startedAfterBoundary = true
            save()
        }

        fun recordDate(date: LocalDate) {
            val now = clock.millis()
            val current = health.forCurrentHealthDay(now)
            val consentOffExcluded = currentCloudHealthDisclosure(health.snapshot, false, now) == CloudHealthDisclosure.Excluded
            val consentOnIncluded = currentCloudHealthDisclosure(health.snapshot, true, now) is CloudHealthDisclosure.Included
            val dayMatches = date == CaesarTime.today(Instant.ofEpochMilli(now))
            val oldStatusCleared = current.miFitnessLastSyncAt == null && current.actionMessage == null &&
                current.miFitnessStatus == MiFitnessUiStatus.IDLE
            if (date == firstDate && dayMatches) beforePassed = current.snapshot != null && consentOnIncluded && consentOffExcluded
            if (date == nextDate && dayMatches) afterPassed = current.snapshot == null && !consentOnIncluded && consentOffExcluded && oldStatusCleared
            observations.put(JSONObject().put("date", date.toString()).put("controlledEpochMillis", now)
                .put("systemEpochMillis", System.currentTimeMillis()).put("dateMatchesControlledClock", dayMatches)
                .put("todaySnapshotVisible", current.snapshot != null).put("consentOnIncluded", consentOnIncluded)
                .put("consentOffExcluded", consentOffExcluded).put("oldStatusCleared", oldStatusCleared))
            save()
        }

        private fun save() {
            reportFile.parentFile!!.mkdirs()
            val lifecyclePassed = if (scenario == "resume") stoppedBeforeBoundary && startedAfterBoundary
                else !stoppedBeforeDateUpdate
            val report = JSONObject().put("schema", "caesar.time.boundary.probe.v1").put("scenario", scenario)
                .put("controlledDateSimulation", true).put("naturalMidnightVerified", false)
                .put("syntheticHealthOnly", true).put("networkRequests", 0)
                .put("globalClockChanged", false).put("boundaryEpochMillis", boundary)
                .put("beforePassed", beforePassed).put("afterPassed", afterPassed)
                .put("lifecyclePassed", lifecyclePassed).put("passed", beforePassed && afterPassed && lifecyclePassed)
                .put("lifecycleEvents", events).put("observations", observations)
            reportFile.writeText(report.toString(2))
        }
    }

    /** Wall-time progression is monotonic and confined to this one fixture instance. */
    private class RunningClock(private val zoneId: ZoneId = ZoneOffset.UTC,
        private val startElapsed: Long = SystemClock.elapsedRealtime()) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = RunningClock(zone, startElapsed)
        override fun instant(): Instant = Instant.parse("2026-10-18T15:59:56Z")
            .plusMillis(SystemClock.elapsedRealtime() - startElapsed)
    }
}
