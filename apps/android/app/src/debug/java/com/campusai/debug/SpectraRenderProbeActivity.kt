package com.campusai.debug

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.campusai.core.designsystem.*
import com.campusai.core.model.MotionMode
import com.campusai.core.model.RenderQuality
import com.campusai.core.model.SpectraEnvironment
import com.campusai.core.model.ThemeMode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shell-only, separate-process fixture. No account, preferences, network, system overrides or extra GL loop. */
class SpectraRenderProbeActivity : ComponentActivity() {
    private var quality by mutableStateOf(RenderQuality.AUTO)
    private var phaseLabel by mutableStateOf("准备真实渲染器")
    private var budgetLabel by mutableStateOf("")
    private val phases = JSONArray()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pressureUntil = 0L
    private var stallCount = 0
    private var started = false
    private var finished = false
    private var startedAt = 0L
    private val pressure = object : Runnable {
        override fun run() {
            if (finished || SystemClock.elapsedRealtime() >= pressureUntil) return
            // Bounded waiting, not a busy loop/GPU load: real delays in this debug process only.
            SystemClock.sleep(24)
            stallCount++
            mainHandler.postDelayed(this, 16)
        }
    }
    private val deadline = Runnable { complete("deadline", "fixed_deadline") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SpectraRenderProbeContract.accepts(intent.action,
                intent.getBooleanExtra(SpectraRenderProbeContract.OPT_IN, false))) {
            finish()
            return
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        started = true
        startedAt = SystemClock.elapsedRealtime()
        File(filesDir, SpectraRenderProbeContract.REPORT).writeText(
            JSONObject().put("schema", 1).put("outcome", "running").put("startedUptimeMs", startedAt).toString())
        mainHandler.postDelayed(deadline, SpectraRenderProbeContract.DEADLINE_MS)
        setContent {
            CampusTheme(ThemeMode.DARK) {
                ProvideSpectraExperience(SpectraVisualStyle.FLUID) {
                    ProvideSpectraTokens(spectraTokensForStyle(style = SpectraVisualStyle.FLUID)) {
                        SideEffect {
                            SpectraVisualStyleController.set(SpectraVisualStyle.FLUID)
                            OpticalGlassRegistry.switchRouteScope("debug:spectra-probe")
                        }
                        Box(Modifier.fillMaxSize()) {
                            SpectraBackdrop(SpectraEnvironment.ORIGINAL, quality, MotionMode.ON)
                            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("仅调试 · 渲染接线验证")
                                Text("热/省电输入为合成条件，不是实际温度证据。\n帧压力来自本进程有限等待，不测耗电或 GPU 完成。")
                                CaesarGlassOrb(size = 176.dp, mode = CaesarOrbMode.RESPONDING)
                                GlassPanel(Modifier.fillMaxWidth(), emphasized = true) {
                                    Column(Modifier.padding(20.dp)) {
                                        Text(phaseLabel)
                                        Text(budgetLabel)
                                        Text("70 秒内结束；离开页面立即终止")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            try {
                withTimeout(SpectraRenderProbeContract.DEADLINE_MS) {
                    val surface = withTimeout(3_000) {
                        var found: SpectraSurfaceView? = null
                        while (found == null) { found = findSurface(window.decorView); if (found == null) delay(25) }
                        checkNotNull(found)
                    }
                    val probe = SurfaceProbe(surface)
                    for (phase in SpectraRenderProbeContract.phases) {
                        if (finished) return@withTimeout
                        quality = phase.quality
                        phaseLabel = phase.name
                        delay(80) // Let the production AndroidView configure the requested quality.
                        val begin = SystemClock.elapsedRealtime()
                        val row = JSONObject().put("phase", phase.name).put("startedUptimeMs", begin)
                            .put("quality", phase.quality.name).put("syntheticThermalStatus", phase.syntheticThermal)
                            .put("syntheticPowerSave", phase.syntheticSaver).put("actualProcessFramePressure", phase.stallMainThread)
                            .put("expectedFinalBudget", phase.expectedFinalBudget)
                        phases.put(row) // Preserve the unfinished phase when the activity is interrupted.
                        val transitions = JSONArray()
                        row.put("observedTransitions", transitions)
                        val before = probe.glSnapshot()
                        var lastBudget = ""
                        val stallsBefore = stallCount
                        if (phase.stallMainThread) {
                            pressureUntil = begin + phase.durationMs
                            mainHandler.post(pressure)
                        }
                        while (!finished && SystemClock.elapsedRealtime() - begin < phase.durationMs) {
                            val observed = probe.applyInputs(phase)
                            val budget = observed.getString("budget")
                            if (budget != lastBudget) {
                                transitions.put(observed)
                                lastBudget = budget
                                budgetLabel = "${phase.quality} / $budget"
                                Log.i(TAG, "phase=${phase.name} syntheticThermal=${phase.syntheticThermal} syntheticSaver=${phase.syntheticSaver} actualStall=${phase.stallMainThread} budget=$budget")
                            }
                            row.put("lastInputs", observed)
                            delay(250)
                        }
                        mainHandler.removeCallbacks(pressure)
                        pressureUntil = 0
                        val after = probe.glSnapshot()
                        row.put("endedUptimeMs", SystemClock.elapsedRealtime()).put("glBefore", before).put("glAfter", after)
                            .put("injectedWaitCount", stallCount - stallsBefore)
                            .put("glCompletionAdvanced", after.getLong("completedFrameAtNanos") > before.getLong("completedFrameAtNanos"))
                            .put("expectedFinalBudgetObserved", lastBudget == phase.expectedFinalBudget)
                    }
                    complete("completed", null)
                }
            } catch (_: CancellationException) {
                complete("interrupted", "cancelled_or_timeout")
            } catch (failure: Exception) {
                complete("failed", if (failure is ActualThermalProtection) "actual_thermal_protection" else failure.javaClass.simpleName)
            }
        }
    }

    override fun onPause() {
        if (started && !finished) complete("interrupted", "activity_paused")
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pressure)
        mainHandler.removeCallbacks(deadline)
        super.onDestroy()
    }

    private fun complete(outcome: String, error: String?) {
        if (!started || finished) return
        finished = true
        pressureUntil = 0
        mainHandler.removeCallbacks(pressure)
        mainHandler.removeCallbacks(deadline)
        val report = JSONObject().put("schema", 1).put("outcome", outcome)
            .put("errorCode", error ?: JSONObject.NULL).put("startedUptimeMs", startedAt)
            .put("endedUptimeMs", SystemClock.elapsedRealtime()).put("deadlineMs", SpectraRenderProbeContract.DEADLINE_MS)
            .put("thermalAndPowerInputsAreSynthetic", true).put("gpuOrPresentationMeasured", false)
            .put("callbackSamplesAreNotFrameCounts", true).put("phases", phases)
        File(filesDir, SpectraRenderProbeContract.REPORT).writeText(report.toString(2))
        Log.i(TAG, "outcome=$outcome phases=${phases.length()} error=$error")
        finish()
    }

    private fun findSurface(view: View): SpectraSurfaceView? {
        if (view is SpectraSurfaceView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findSurface(view.getChildAt(i))?.let { return it }
        return null
    }

    /** Private-field access lives only in debug. Production has no injection API or override state. */
    private class SurfaceProbe(private val surface: SpectraSurfaceView) {
        private val type = SpectraSurfaceView::class.java
        private val thermal = type.getDeclaredField("thermalStatus").apply { isAccessible = true }
        private val saver = type.getDeclaredField("powerSave").apply { isAccessible = true }
        private val policy = type.getDeclaredField("renderPolicy").apply { isAccessible = true }.get(surface) as SpectraRenderPolicy
        private val update = type.getDeclaredMethod("updateRenderBudget").apply { isAccessible = true }
        private val renderer = type.getDeclaredField("spectraRenderer").apply { isAccessible = true }.get(surface)!!
        private val fields = listOf("sceneScale", "sceneWidth", "sceneHeight", "width", "height", "sceneFramebufferReady",
            "completedFrameAtNanos", "frameCpuDurationNanos").associateWith { renderer.javaClass.getDeclaredField(it).apply { isAccessible = true } }
        private val power = surface.context.getSystemService(PowerManager::class.java)

        fun applyInputs(phase: SpectraRenderProbeContract.Phase): JSONObject {
            val actualThermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: 0 else 0
            val actualSaver = power?.isPowerSaveMode == true
            // Abort rather than mask or deliberately extend genuine severe thermal conditions.
            if (actualThermal >= 3) throw ActualThermalProtection()
            thermal.setInt(surface, maxOf(actualThermal, phase.syntheticThermal))
            saver.setBoolean(surface, actualSaver || phase.syntheticSaver)
            update.invoke(surface)
            return JSONObject().put("uptimeMs", SystemClock.elapsedRealtime()).put("budget", policy.budget.name)
                .put("budgetCapHz", policy.budget.maximumHz).put("budgetSceneScale", policy.budget.sceneScale)
                .put("observedSystemThermalStatus", actualThermal).put("observedSystemPowerSave", actualSaver)
                .put("effectiveThermalStatus", thermal.getInt(surface)).put("effectivePowerSave", saver.getBoolean(surface))
        }

        suspend fun glSnapshot(): JSONObject = withTimeout(1_500) {
            suspendCancellableCoroutine { continuation ->
                surface.queueEvent {
                    try {
                        val result = JSONObject()
                        fields.forEach { (name, field) -> result.put(name, field.get(renderer)) }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            }
        }
    }

    private class ActualThermalProtection : RuntimeException()

    companion object { private const val TAG = "CaesarRenderProbe" }
}
