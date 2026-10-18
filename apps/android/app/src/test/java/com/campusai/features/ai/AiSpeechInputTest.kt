package com.campusai.features.ai

import android.Manifest
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.campusai.core.designsystem.CaesarOrbSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiSpeechInputTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun microphoneAvailableInFakeEnvironment() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
    }

    @Test
    fun `a cancelled recognizer cannot overwrite the next attempt draft`() {
        lateinit var input: AiSpeechInput
        var draft = ""
        compose.setContent {
            input = rememberAiSpeechInput("owner-a", false, remember { CaesarOrbSignal() },
                onTranscript = { draft = it }, onBeforeStart = {}, onPaused = {}, onNotice = {})
        }
        compose.runOnIdle { input.toggle() }
        compose.waitForIdle()
        val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        compose.runOnIdle {
            shadowOf(first).triggerOnPartialResults(result("第一轮草稿"))
            input.cancel(null)
        }
        compose.waitForIdle()
        compose.runOnIdle { input.toggle() }
        compose.waitForIdle()
        val second = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertNotSame(first, second)
        compose.runOnIdle {
            assertTrue(shadowOf(first).isDestroyed)
            shadowOf(first).triggerOnResults(result("过期结果"))
            assertEquals("第一轮草稿", draft)
            shadowOf(second).triggerOnResults(result("当前结果"))
            assertEquals("当前结果", draft)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(input.listening); assertFalse(input.transcribing) }
    }

    @Test
    fun `owner change and generation block reject late native results`() {
        lateinit var input: AiSpeechInput
        var owner by mutableStateOf("owner-a")
        var blocked by mutableStateOf(false)
        var draft = ""
        compose.setContent {
            input = rememberAiSpeechInput(owner, blocked, remember { CaesarOrbSignal() },
                onTranscript = { draft = it }, onBeforeStart = {}, onPaused = {}, onNotice = {})
        }
        compose.runOnIdle { input.toggle() }
        compose.waitForIdle()
        val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        compose.runOnIdle { owner = "owner-b" }
        compose.waitForIdle()
        compose.runOnIdle {
            shadowOf(first).triggerOnResults(result("上个账号"))
            assertEquals("", draft)
            input.toggle()
        }
        compose.waitForIdle()
        val second = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        compose.runOnIdle { blocked = true }
        compose.waitForIdle()
        compose.runOnIdle {
            shadowOf(second).triggerOnResults(result("生成期间的旧语音"))
            assertEquals("", draft)
            assertFalse(input.listening)
            assertFalse(input.transcribing)
        }
    }

    private fun result(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }
}
