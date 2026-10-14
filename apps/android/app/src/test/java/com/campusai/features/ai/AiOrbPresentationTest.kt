package com.campusai.features.ai

import com.campusai.core.designsystem.CaesarOrbMode
import com.campusai.core.designsystem.CaesarOrbSignal
import com.campusai.core.model.AiConversationMessage
import org.junit.Assert.*
import org.junit.Test

class AiOrbPresentationTest {
    private val reply = listOf(AiConversationMessage("assistant", "部分回复"))
    private fun presentation(state: AiUiState) = aiOrbPresentation(state, false, false, false, null, null)

    @Test fun `partial replies never hide cancellation or errors behind completion`() {
        assertEquals(CaesarOrbMode.CANCELLED, presentation(AiUiState(messages = reply, errorCode = "cancelled", error = "停止")).mode)
        assertEquals(CaesarOrbMode.ERROR, presentation(AiUiState(messages = reply, error = "断开")).mode)
        assertEquals(CaesarOrbMode.COMPLETE, presentation(AiUiState(messages = reply)).mode)
    }

    @Test fun `streaming retains actual tool status and leaves thinking after first token`() {
        val state = AiUiState(streaming = true, stage = "正在读取时间记录")
        assertEquals("正在读取时间记录", presentation(state).label)
        assertEquals(CaesarOrbMode.THINKING, presentation(state).mode)
        val started = presentation(state.copy(messages = reply, stage = "思考中"))
        assertEquals(CaesarOrbMode.RESPONDING, started.mode)
        assertEquals("正在生成回复", started.label)
        assertEquals("正在等待回复", presentation(state.copy(stage = "responding")).label)
        assertEquals("正在思考", presentation(state.copy(stage = "planning")).label)
        assertEquals("正在加载本地模型", presentation(state.copy(stage = "local_loading")).label)
    }

    @Test fun `listening transcription and playback reflect their own lifecycle`() {
        val state = AiUiState(messages = reply)
        assertEquals(CaesarOrbMode.LISTENING, aiOrbPresentation(state, true, false, false, null, null).mode)
        assertEquals(CaesarOrbMode.TRANSCRIBING, aiOrbPresentation(state, false, true, false, null, null).mode)
        assertEquals(CaesarOrbMode.SPEAKING, aiOrbPresentation(state, false, false, true, null, null).mode)
        assertEquals("已停止朗读", aiOrbPresentation(state, false, false, false, null, "已停止朗读").label)
        assertEquals(CaesarOrbMode.CANCELLED, aiOrbPresentation(state, false, false, false, null, "朗读已停止").mode)
        assertEquals(CaesarOrbMode.CANCELLED, aiOrbPresentation(state, false, false, false, "语音已结束", null).mode)
        assertEquals(CaesarOrbMode.ERROR, aiOrbPresentation(state, false, false, false, "没有听清", null).mode)
    }

    @Test fun `rms is bounded expires on silence and is never represented as playback audio`() {
        val signal = CaesarOrbSignal().apply { mode = CaesarOrbMode.LISTENING }
        signal.onRms(100f, 1_000)
        assertEquals(1f, signal.amplitude(1_010), 0f)
        assertEquals(0f, signal.amplitude(1_241), 0f)
        signal.onRms(Float.NaN, 2_000)
        assertEquals(0f, signal.amplitude(2_001), 0f)
        signal.onRms(6f, 3_000)
        signal.mode = CaesarOrbMode.SPEAKING
        assertEquals(0f, signal.amplitude(3_001), 0f)
        signal.mode = CaesarOrbMode.LISTENING
        signal.reset()
        assertEquals(0f, signal.amplitude(3_002), 0f)
    }
}
