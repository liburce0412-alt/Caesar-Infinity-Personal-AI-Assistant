package com.campusai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.campusai.core.localai.LocalMnnAiEngine
import com.campusai.core.localai.LocalModelManager
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMnnIdleReleaseTest {
    @Test fun `releasing an unused engine never initializes the native library`() = runTest {
        // This JVM has no MNN JNI. The same idle release is called during memory trim.
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManager.initialize(context, Configuration.Builder().build())
        val manager = LocalModelManager(context)
        val engine = LocalMnnAiEngine(context, manager)
        try {
            engine.releaseAndWait()
            engine.releaseAndWait()
        } finally {
            engine.shutdown()
            manager.close()
        }
    }
}
