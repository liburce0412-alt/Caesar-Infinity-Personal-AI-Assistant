package com.campusai.core.designsystem

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val CAESAR_SIGNATURE_WIDTH = 676f

/** The launch signature at rest, with native brand text for accessibility and no animation loop. */
@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    width: Dp = 132.dp,
    color: Color = LocalContentColor.current,
) {
    Spacer(
        modifier.width(width).aspectRatio(CAESAR_SIGNATURE_WIDTH / 144f)
            .semantics { text = AnnotatedString("Caesar∞") }
            .drawWithCache {
                val signature = caesarSignaturePath()
                val signatureScale = size.width / CAESAR_SIGNATURE_WIDTH
                val stroke = Stroke(8f, cap = StrokeCap.Square, join = StrokeJoin.Miter)
                onDrawBehind {
                    withTransform({
                        translate(top = 30f * signatureScale)
                        scale(signatureScale, signatureScale, Offset.Zero)
                    }) {
                        drawPath(signature, color, style = stroke)
                    }
                }
            },
    )
}

/** Original angular CAESAR infinity signature; hand-authored paths, not a third-party font. */
internal fun caesarSignaturePath(): Path = Path().apply {
    // One pen-down stroke: unequal letter heights and staggered baselines give a signed rhythm.
    // C sits low; A rises above it, with the connecting stroke climbing into its apex.
    moveTo(73f, 31f)
    lineTo(26f, 39f)
    lineTo(8f, 98f)
    lineTo(70f, 89f)
    lineTo(93f, 65f)
    lineTo(140f, -13f)
    lineTo(155f, 65f)
    lineTo(146f, 30f)
    lineTo(113f, 36f)
    lineTo(155f, 65f)
    // E drops back down; its shorter height keeps the skyline deliberately uneven.
    lineTo(180f, 96f)
    lineTo(201f, 34f)
    lineTo(253f, 28f)
    lineTo(201f, 34f)
    lineTo(191f, 63f)
    lineTo(237f, 54f)
    lineTo(191f, 63f)
    lineTo(180f, 96f)
    lineTo(241f, 87f)
    // S lifts again, its diagonal spine and retraced turns retaining the continuous signature.
    lineTo(264f, 64f)
    lineTo(321f, 51f)
    lineTo(325f, 38f)
    lineTo(270f, 23f)
    lineTo(278f, 3f)
    lineTo(335f, -10f)
    lineTo(278f, 3f)
    lineTo(270f, 23f)
    lineTo(325f, 38f)
    lineTo(321f, 51f)
    lineTo(264f, 64f)
    lineTo(350f, 93f)
    lineTo(397f, 22f)
    lineTo(414f, 94f)
    lineTo(404f, 60f)
    lineTo(372f, 66f)
    lineTo(414f, 94f)
    // R has a high shoulder and a falling leg, flowing into the softer infinity flourish.
    lineTo(440f, 54f)
    lineTo(459f, -7f)
    lineTo(500f, -11f)
    lineTo(518f, 8f)
    lineTo(484f, 25f)
    lineTo(449f, 31f)
    lineTo(484f, 25f)
    lineTo(521f, 63f)
    lineTo(548f, 61f)
    cubicTo(548f, 28f, 577f, 23f, 601f, 56f)
    cubicTo(624f, 89f, 655f, 93f, 661f, 61f)
    cubicTo(666f, 27f, 634f, 21f, 610f, 56f)
    cubicTo(587f, 91f, 554f, 94f, 548f, 61f)
}
