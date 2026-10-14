package com.campusai.features.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import com.campusai.core.designsystem.CaesarOrbMode

/** Small native island artwork. Expressions change with state, never with a fake audio clock. */
internal object AiIslandFace {
    private val cache = mutableMapOf<CaesarOrbMode, Bitmap>()

    @Synchronized fun bitmap(mode: CaesarOrbMode): Bitmap = cache.getOrPut(mode) {
        createBitmap(96, 96).also { bitmap ->
            val canvas = Canvas(bitmap)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
            }
            if (mode == CaesarOrbMode.THINKING || mode == CaesarOrbMode.TRANSCRIBING) {
                canvas.drawLine(20f, 37f, 34f, 33f, pen)
                canvas.drawLine(62f, 33f, 76f, 37f, pen)
            } else if (mode == CaesarOrbMode.COMPLETE) {
                canvas.drawArc(20f, 27f, 36f, 45f, 190f, 160f, false, pen)
                canvas.drawArc(60f, 27f, 76f, 45f, 190f, 160f, false, pen)
            } else {
                canvas.drawLine(28f, 30f, 28f, 41f, pen)
                canvas.drawLine(68f, 30f, 68f, 41f, pen)
            }
            when (mode) {
                CaesarOrbMode.LISTENING, CaesarOrbMode.SPEAKING -> canvas.drawOval(39f, 55f, 57f, 75f, pen)
                CaesarOrbMode.THINKING, CaesarOrbMode.TRANSCRIBING -> canvas.drawLine(37f, 65f, 59f, 65f, pen)
                CaesarOrbMode.ERROR -> canvas.drawArc(33f, 60f, 63f, 80f, 205f, 130f, false, pen)
                CaesarOrbMode.CANCELLED -> canvas.drawLine(37f, 66f, 59f, 66f, pen)
                else -> canvas.drawArc(29f, 48f, 67f, 75f, 15f, 150f, false, pen)
            }
        }
    }
}
