package com.campusai.core.designsystem

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrandLaunchGateTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test fun `cold launcher entry plays once across activity recreation in the same process`() {
        val gate = BrandLaunchGate()
        assertTrue(gate.claim(launcher(), restored = false, motionEnabled = true))
        assertFalse(gate.claim(launcher(), restored = false, motionEnabled = true))
    }

    @Test fun `external entry bypasses branding and later warm launcher does not replay it`() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_VIEW, "com.campusai.OPEN_COURSE")) {
            val gate = BrandLaunchGate()
            assertFalse(gate.claim(Intent(action), restored = false, motionEnabled = true))
            assertFalse(gate.claim(launcher(), restored = false, motionEnabled = true))
        }
    }

    @Test fun `restored activity and animation disabled startup both enter content directly`() {
        assertFalse(BrandLaunchGate().claim(launcher(), restored = true, motionEnabled = true))
        assertFalse(BrandLaunchGate().claim(launcher(), restored = false, motionEnabled = false))
    }

    @Test fun `a main action without launcher category does not obscure an external route`() {
        assertFalse(BrandLaunchGate().claim(Intent(Intent.ACTION_MAIN), restored = false, motionEnabled = true))
        assertFalse(BrandLaunchGate().claim(null, restored = false, motionEnabled = true))
    }
}
