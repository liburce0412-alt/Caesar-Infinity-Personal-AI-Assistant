package com.campusai.features.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import java.util.UUID

/** Page-owned playback. Engine callbacks are checked against the current utterance and lifetime. */
internal class AiSpeechPlayback {
    var ready by mutableStateOf(false)
        private set
    var speaking by mutableStateOf(false)
        private set
    var activeUtterance by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    private var engine: TextToSpeech? = null
    private var alive = false

    fun start(context: Context) {
        alive = true
        val main = Handler(Looper.getMainLooper())
        engine = TextToSpeech(context) { status -> main.post {
            if (alive) {
                val language = if (status == TextToSpeech.SUCCESS) engine?.setLanguage(Locale.SIMPLIFIED_CHINESE) else null
                ready = language != null && language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
            }
        } }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { if (alive && id == activeUtterance) speaking = true } }
            private fun finish(id: String?, result: String) { main.post {
                if (alive && id == activeUtterance) { activeUtterance = null; speaking = false; message = result }
            } }
            override fun onDone(id: String?) = finish(id, "朗读结束")
            @Deprecated("Legacy engine callback")
            override fun onError(id: String?) = finish(id, "朗读失败 · 请重试")
            override fun onError(id: String?, errorCode: Int) = finish(id, "朗读失败 · 请重试")
            override fun onStop(id: String?, interrupted: Boolean) = finish(id, "已停止朗读")
        })
    }

    fun speak(text: String) {
        if (!ready) return
        stop()
        message = "正在准备朗读"
        val id = UUID.randomUUID().toString()
        activeUtterance = id
        if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            activeUtterance = null
            message = "朗读失败 · 请重试"
        }
    }

    fun stop() {
        activeUtterance = null
        speaking = false
        engine?.stop()
    }

    fun close() {
        alive = false
        stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}

@Composable
internal fun rememberAiSpeechPlayback(): AiSpeechPlayback {
    val context = LocalContext.current
    val playback = remember(context) { AiSpeechPlayback() }
    DisposableEffect(playback) {
        playback.start(context)
        onDispose { playback.close() }
    }
    return playback
}
