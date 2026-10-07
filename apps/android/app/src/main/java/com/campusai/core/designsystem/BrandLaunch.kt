package com.campusai.core.designsystem

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.campusai.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One launch per process, including processes first entered through a notification or share. */
internal class BrandLaunchGate {
    private var opened = false

    fun claim(intent: Intent?, restored: Boolean, motionEnabled: Boolean): Boolean {
        val first = !opened
        opened = true
        return first && !restored && motionEnabled && intent?.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }
}

internal fun brandLaunchMotionEnabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

/** Content renders immediately underneath; this is a bounded entrance, never a loading gate. */
@Composable
internal fun BrandLaunchHost(
    visible: Boolean,
    ready: Boolean,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember(visible) { Animatable(0f) }
    Box(Modifier.fillMaxSize()) {
        Box(
            if (visible) Modifier.clearAndSetSemantics { }.graphicsLayer {
                alpha = launchEase(progress.value * 2_050f, 1_750f, 2_050f)
            } else Modifier,
        ) { content() }
        if (visible) BrandLaunchOverlay(ready, progress, onFinished)
    }
}

@Composable
private fun BrandLaunchOverlay(ready: Boolean, progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, onFinished: () -> Unit) {
    val finish by rememberUpdatedState(onFinished)
    // The original metal logo is decoded once. The arriving ribbons and signature are paths.
    val metal = ImageBitmap.imageResource(R.drawable.caesar_brand_metal)
    val background = MaterialTheme.colorScheme.background
    BackHandler { finish() }
    LaunchedEffect(ready) {
        if (ready) {
            // Slow animator scales must not turn branding into a loading gate.
            withTimeoutOrNull(2_200L) {
                progress.animateTo(1f, tween(2_050, easing = LinearEasing))
            }
            finish()
        } else {
            // Fail open if a vendor never dispatches the platform splash exit callback.
            delay(1_100L)
            finish()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .semantics { paneTitle = "Caesar∞"; contentDescription = "Caesar∞" }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
            .brandLaunchArtwork(metal, background) { progress.value },
    )
}

private fun Modifier.brandLaunchArtwork(
    metal: ImageBitmap,
    background: Color,
    progress: () -> Float,
) = drawWithCache {
    val center = Offset(size.width / 2f, size.height / 2f)
    val dark = background.luminance() < .4f
    val side = minOf(192.dp.toPx(), size.minDimension * .56f)
    val origin = center - Offset(side / 2f, side / 2f)
    val destination = IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
    val dimensions = IntSize(side.roundToInt(), side.roundToInt())
    val blackStart = separatedRibbonPoints(side, white = false)
    val whiteStart = separatedRibbonPoints(side, white = true)
    val blackJoined = interlacedRibbonPoints(side, -90f)
    val whiteJoined = interlacedRibbonPoints(side, -30f)
    val black = Path()
    val white = Path()
    val blackTrace = Path()
    val whiteTrace = Path()
    val crossing = Path()
    val blackMeasure = PathMeasure()
    val whiteMeasure = PathMeasure()
    val signatureMeasure = PathMeasure().apply { setPath(caesarSignaturePath(), false) }
    val signature = Path()
    val signatureWidth = minOf(280.dp.toPx(), size.width * .78f)
    val signatureScale = signatureWidth / CAESAR_SIGNATURE_WIDTH
    val signatureOrigin = Offset(center.x - signatureWidth / 2f, center.y + 117.dp.toPx())
    val signatureInk = if (dark) Color(0xFFF1F4FF) else Color(0xFF1C2030)
    val halo = Brush.radialGradient(
        listOf(Color(0xFFAAAFFF).copy(alpha = .12f), Color(0xFF75DBEE).copy(alpha = .05f), Color.Transparent),
        center, side,
    )
    val blackMaterial = Brush.linearGradient(
        0f to Color(0xFF151922), .36f to Color(0xFF8B94AB),
        .51f to Color(0xFF242938), .74f to Color(0xFF090C13), 1f to Color(0xFF66718C),
        start = Offset(-side / 2f, -side / 2f), end = Offset(side / 2f, side / 2f),
    )
    val whiteMaterial = Brush.linearGradient(
        0f to Color(0xFFE6DFFA), .28f to Color.White, .52f to Color(0xFFD2EDFA),
        .7f to Color.White, 1f to Color(0xFFE0D9F4),
        start = Offset(side / 2f, -side / 2f), end = Offset(-side / 2f, side / 2f),
    )
    val ribbonWidth = 12.dp.toPx()
    val ribbonStroke = Stroke(ribbonWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val edgeStroke = Stroke(ribbonWidth + 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val reflectionStroke = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val signatureStroke = Stroke(8f, cap = StrokeCap.Square, join = StrokeJoin.Miter)
    val shine = ColorFilter.tint(Color.White, BlendMode.SrcIn)
    val beam = Path()
    val exitTravel = 8.dp.toPx()
    val logoArrivalTravel = 6.dp.toPx()
    val signatureTravel = 4.dp.toPx()
    onDrawBehind {
        val time = progress() * 2_050f
        val exit = launchEase(time, 1_750f, 2_050f)
        val logoAlpha = launchEase(time, 850f, 1_180f) * (1f - launchEase(time, 1_780f, 2_020f))
        val ribbonAlpha = launchEase(time, 0f, 150f) * (1f - launchEase(time, 960f, 1_200f))
        // The app's existing SpectraBackdrop stays mounted beneath this host throughout.
        drawRect(halo, alpha = launchEase(time, 80f, 520f) * (1f - exit))

        if (ribbonAlpha > 0f) {
            val weave = launchEase(time, 280f, 1_050f)
            val reveal = launchEase(time, 0f, 420f)
            black.morphRibbon(blackStart, blackJoined, weave)
            white.morphRibbon(whiteStart, whiteJoined, weave)
            blackMeasure.setPath(black, false)
            whiteMeasure.setPath(white, false)
            blackTrace.rewind()
            whiteTrace.rewind()
            blackMeasure.getSegment(0f, blackMeasure.length * reveal, blackTrace, true)
            whiteMeasure.getSegment(0f, whiteMeasure.length * reveal, whiteTrace, true)
            withTransform({ translate(center.x, center.y) }) {
                drawPath(blackTrace, Color(0xFF93A2CF), alpha = .2f * ribbonAlpha, style = edgeStroke)
                drawPath(blackTrace, blackMaterial, alpha = ribbonAlpha, style = ribbonStroke)
                drawPath(blackTrace, Color.White, alpha = .24f * ribbonAlpha, style = reflectionStroke)
                drawPath(whiteTrace, Color(0xFF8393B3), alpha = .2f * ribbonAlpha, style = edgeStroke)
                drawPath(whiteTrace, whiteMaterial, alpha = ribbonAlpha, style = ribbonStroke)
                // Repaint only the middle of the dark ribbon above the white one: a real
                // over-under crossing, rather than rotating a completed logo or image pieces.
                crossing.rewind()
                blackMeasure.getSegment(blackMeasure.length * .34f, blackMeasure.length * .54f * reveal, crossing, true)
                val crossAlpha = launchEase(time, 530f, 730f) * ribbonAlpha
                drawPath(crossing, blackMaterial, alpha = crossAlpha, style = ribbonStroke)
                drawPath(crossing, Color.White, alpha = crossAlpha * .24f, style = reflectionStroke)
            }
        }

        val formed = launchEase(time, 850f, 1_180f)
        val settle = sin(PI * ((time - 1_050f) / 240f).coerceIn(0f, 1f)).toFloat() * .012f
        withTransform({
            translate(top = logoArrivalTravel * (1f - formed) - exitTravel * exit)
            scale(.93f + .07f * formed + settle + .025f * exit,
                .93f + .07f * formed + settle + .025f * exit, center)
        }) {
            drawImage(metal, dstOffset = destination, dstSize = dimensions, alpha = logoAlpha)
            if (time in 1_260f..1_620f) {
                val sweep = (time - 1_260f) / 360f
                val beamX = origin.x - side * .5f + side * 2f * sweep
                beam.rewind()
                beam.moveTo(beamX, origin.y)
                beam.lineTo(beamX + side * .11f, origin.y)
                beam.lineTo(beamX + side * .56f, origin.y + side)
                beam.lineTo(beamX + side * .45f, origin.y + side)
                beam.close()
                clipPath(beam) {
                    drawImage(metal, dstOffset = destination, dstSize = dimensions,
                        colorFilter = shine, alpha = .17f * logoAlpha)
                }
            }
        }

        val writing = launchEase(time, 1_050f, 1_710f)
        if (writing > 0f) {
            signature.rewind()
            signatureMeasure.getSegment(0f, signatureMeasure.length * writing, signature, true)
            withTransform({
                translate(signatureOrigin.x, signatureOrigin.y + signatureTravel * (1f - writing) - exitTravel * exit)
                scale(signatureScale, signatureScale, Offset.Zero)
            }) {
                drawPath(signature, signatureInk, alpha = 1f - launchEase(time, 1_780f, 2_030f), style = signatureStroke)
            }
        }
    }
}

/** Matching control-point counts let two separate S ribbons ease into alternating three-loop bands. */
private fun separatedRibbonPoints(side: Float, white: Boolean): List<Offset> = List(19) { index ->
    val t = index / 18f
    val sign = if (white) 1f else -1f
    Offset(
        sign * side * (.34f + .1f * cos(2.0 * PI * t).toFloat()),
        side * (if (white) .57f - 1.14f * t else -.57f + 1.14f * t),
    )
}

private fun interlacedRibbonPoints(side: Float, startAngle: Float): List<Offset> = buildList {
    add(Offset.Zero)
    fun radial(radius: Float, angle: Float): Offset {
        val radians = angle * PI / 180.0
        return Offset(cos(radians).toFloat(), sin(radians).toFloat()) * (side * .52f * radius)
    }
    repeat(3) { index ->
        val angle = startAngle + index * 120f
        add(radial(.73f, angle - 51f))
        add(radial(1.1f, angle - 30f))
        add(radial(.89f, angle + 3f))
        add(radial(.73f, angle + 49f))
        add(radial(.26f, angle + 30f))
        add(Offset.Zero)
    }
}

private fun Path.morphRibbon(from: List<Offset>, to: List<Offset>, fraction: Float) {
    rewind()
    fun x(index: Int) = from[index].x + (to[index].x - from[index].x) * fraction
    fun y(index: Int) = from[index].y + (to[index].y - from[index].y) * fraction
    moveTo(x(0), y(0))
    for (index in 1 until from.size step 3) {
        cubicTo(x(index), y(index), x(index + 1), y(index + 1), x(index + 2), y(index + 2))
    }
}

private fun launchEase(time: Float, start: Float, end: Float): Float =
    FastOutSlowInEasing.transform(((time - start) / (end - start)).coerceIn(0f, 1f))
