package com.campusai.core.designsystem

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Bounded, per-surface particles. Only the shared GL thread advances this state. */
internal class GlassAtmosphere(private val random: Random = Random.Default) {
    val heads = FloatArray(20)
    val directions = FloatArray(10)
    var hue = 0f
        private set
    var phase = 0f
        private set
    private var engaged = false
    private var nextMeteor = 0f
    private val starts = FloatArray(5) { -100f }
    private val lives = FloatArray(5)
    private val origins = FloatArray(10)
    private val speeds = FloatArray(5)
    private val tails = FloatArray(5)

    fun update(seconds: Float, interaction: Float, width: Float, height: Float, meteorsEnabled: Boolean = true) {
        if (!meteorsEnabled) lives.fill(0f)
        if (!engaged && interaction > .1f) {
            hue = if (random.nextBoolean()) 1f else 0f
            phase = random.nextFloat() * 100f
            nextMeteor = seconds + .04f + random.nextFloat() * .22f
            engaged = true
        } else if (interaction <= .001f) {
            engaged = false
        }
        if (meteorsEnabled && engaged && interaction > .4f && seconds >= nextMeteor) {
            val slot = starts.indices.firstOrNull { seconds - starts[it] >= lives[it] }
            if (slot != null) {
                val angle = .15f + random.nextFloat() * .48f
                val scale = (height / 180f).coerceIn(.6f, 2.5f)
                starts[slot] = seconds
                lives[slot] = .55f + random.nextFloat() * 1.15f
                speeds[slot] = (190f + random.nextFloat() * 300f) * scale
                tails[slot] = (55f + random.nextFloat() * 80f) * scale
                origins[slot * 2] = -width * .5f - 20f + random.nextFloat() * width * .75f
                origins[slot * 2 + 1] = height * .5f + 12f - random.nextFloat() * height * .7f
                directions[slot * 2] = cos(angle)
                directions[slot * 2 + 1] = -sin(angle)
            }
            nextMeteor = seconds + .16f + random.nextFloat() * .72f
        }
        starts.indices.forEach { i ->
            val age = seconds - starts[i]
            heads[i * 4] = origins[i * 2] + age * speeds[i] * directions[i * 2]
            heads[i * 4 + 1] = origins[i * 2 + 1] + age * speeds[i] * directions[i * 2 + 1]
            heads[i * 4 + 2] = tails[i]
            heads[i * 4 + 3] = minOf(1f, age * 8f) * ((lives[i] - age) * 3f).coerceIn(0f, 1f)
        }
    }
}
