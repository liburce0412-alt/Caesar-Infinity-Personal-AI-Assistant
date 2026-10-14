package com.campusai.core.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

enum class CaesarOrbMode { IDLE, LISTENING, TRANSCRIBING, THINKING, RESPONDING, SPEAKING, COMPLETE, CANCELLED, ERROR }

/** Audio callbacks write here, not to Compose state. The one scene loop smooths the envelope. */
class CaesarOrbSignal {
    @Volatile var mode: CaesarOrbMode = CaesarOrbMode.IDLE
    @Volatile private var level = 0f
    @Volatile private var sampledAt = 0L

    fun onRms(rmsDb: Float, now: Long = android.os.SystemClock.elapsedRealtime()) {
        level = if (rmsDb.isFinite()) ((rmsDb + 2f) / 12f).coerceIn(0f, 1f) else 0f
        sampledAt = now
    }

    fun reset() { level = 0f; sampledAt = 0L }

    fun amplitude(now: Long): Float = if (mode == CaesarOrbMode.LISTENING && now - sampledAt in 0..240) level else 0f
}

/** Set only by the owning scene view on the UI thread; a failed shader/FBO shows native artwork. */
internal object CaesarOrbAvailability {
    var available by mutableStateOf(false)
}

/** Decorative only: status and actions are separate native text/buttons. No GL view or frame loop. */
@Composable
fun CaesarGlassOrb(
    modifier: Modifier = Modifier,
    size: Dp = 176.dp,
    mode: CaesarOrbMode = CaesarOrbMode.IDLE,
    motionEnabled: Boolean = true,
    signal: CaesarOrbSignal = remember { CaesarOrbSignal() },
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .35f
    val live = motionEnabled && CaesarOrbAvailability.available
    SideEffect { signal.mode = mode }
    val artwork = remember(dark) { CaesarOrbArtwork.bitmap(dark).asImageBitmap() }
    Canvas(modifier.size(size).opticalGlassRegion(enabled = live, orb = signal)) {
        if (!live) drawImage(artwork, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
    }
}

/** Cached still material for reduced motion, unavailable GPU and system-owned notification images. */
object CaesarOrbArtwork {
    private val light by lazy { create(false) }
    private val dark by lazy { create(true) }
    fun bitmap(darkMode: Boolean): Bitmap = if (darkMode) dark else light

    private fun create(darkMode: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val body = RectF(18f, 32f, 238f, 222f)
        val shape = Path().apply { addRoundRect(body, 103f, 95f, Path.Direction.CW) }
        canvas.save()
        canvas.translate(128f, 218f)
        canvas.scale(1f, .28f)
        paint.shader = RadialGradient(0f, 0f, 126f, intArrayOf(0x44000000, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, 126f, paint)
        canvas.restore()
        canvas.save()
        canvas.clipPath(shape)
        paint.shader = LinearGradient(0f, 18f, 0f, 234f,
            intArrayOf(0xff08090b.toInt(), AndroidColor.BLACK, 0xff17191c.toInt(), 0xff656971.toInt(),
                if (darkMode) 0xe98f98a8.toInt() else 0xe9bbc5ce.toInt(), 0xe0dbe3ed.toInt()),
            floatArrayOf(0f, .28f, .43f, .59f, .83f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)
        // A concentrated base reflection avoids turning the whole lower hemisphere white.
        canvas.save()
        canvas.translate(128f, 222f)
        canvas.scale(1f, .44f)
        paint.shader = RadialGradient(0f, 0f, 112f,
            intArrayOf(0xe8ffffff.toInt(), 0x72ffffff, 0x00ffffff),
            floatArrayOf(0f, .40f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, 112f, paint)
        canvas.restore()
        paint.shader = RadialGradient(19f, 140f, 65f, intArrayOf(0x82eff5ff.toInt(), 0x00ffffff), null, Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)
        paint.shader = RadialGradient(237f, 140f, 62f, intArrayOf(0x68f5f8ff, 0x00ffffff), null, Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)
        val wave = Path().apply {
            moveTo(23f, 131f); cubicTo(70f, 132f, 79f, 109f, 128f, 124f)
            cubicTo(167f, 139f, 193f, 133f, 233f, 131f)
            cubicTo(181f, 137f, 164f, 153f, 124f, 134f)
            cubicTo(86f, 117f, 61f, 133f, 23f, 131f); close()
        }
        paint.shader = RadialGradient(128f, 130f, 89f,
            intArrayOf(0x78f2e6ff, 0x3097bfff, 0x00ffffff), null, Shader.TileMode.CLAMP)
        canvas.drawOval(28f, 104f, 228f, 155f, paint)
        paint.shader = LinearGradient(44f, 117f, 212f, 141f,
            intArrayOf(0x00ffffff, 0xbbb5d8ff.toInt(), 0xffe9cf9e.toInt(), AndroidColor.WHITE,
                0xff96edff.toInt(), 0xcab9a5ff.toInt(), 0x00ffffff), null, Shader.TileMode.CLAMP)
        canvas.drawPath(wave, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f
        canvas.drawPath(wave, paint)
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(129f, 128f, 31f,
            intArrayOf(0xccffffff.toInt(), 0x00ffffff), null, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.scale(1f, .34f, 129f, 128f)
        canvas.drawCircle(129f, 128f, 31f, paint)
        canvas.restore()
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.15f
        paint.shader = LinearGradient(0f, 18f, 0f, 234f,
            intArrayOf(0x18ffffff, 0x08ffffff, 0xcfffffff.toInt(), 0xffc9ced6.toInt()),
            floatArrayOf(0f, .35f, .82f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)
        return bitmap
    }

}
