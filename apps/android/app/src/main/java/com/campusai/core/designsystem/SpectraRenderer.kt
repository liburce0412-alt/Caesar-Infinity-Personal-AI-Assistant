package com.campusai.core.designsystem

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.campusai.core.model.RenderQuality
import com.campusai.core.model.SpectraEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class SpectraPhase { AMBIENT, THINKING, FOCUS }

/** Independent scene identities; CLASSIC is the calm, text-safe product default. */
enum class SpectraVisualStyle { CLASSIC, FLUID }

/** Thread-safe global style switch read by the single SPECTRA render loop every frame. */
object SpectraVisualStyleController {
    private val current = AtomicReference(SpectraVisualStyle.CLASSIC)

    fun set(style: SpectraVisualStyle) {
        current.set(style)
    }

    fun get(): SpectraVisualStyle = current.get()
}

class SpectraSurfaceView(context: Context) : GLSurfaceView(context) {
    internal val opticalRendererOwnerId: Long = OpticalGlassRegistry.nextRendererOwnerId()
    private val spectraRenderer = SpectraGlRenderer(opticalRendererOwnerId)
    private val locationInWindow = IntArray(2)

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(spectraRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        OpticalGlassRegistry.claimRenderer(opticalRendererOwnerId)
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        OpticalGlassRegistry.releaseRenderer(opticalRendererOwnerId)
        super.onDetachedFromWindow()
    }

    fun configure(environment: SpectraEnvironment, quality: RenderQuality, darkMode: Boolean, phase: SpectraPhase, glassEffects: GlassEffects = GlassEffects()) {
        queueEvent { spectraRenderer.configure(environment, quality, darkMode, phase, glassEffects) }
    }

    fun setPointer(x: Float, y: Float) {
        queueEvent { spectraRenderer.setPointer(x / width.coerceAtLeast(1), 1f - y / height.coerceAtLeast(1)) }
    }

    /** Freezes scene time without pausing/detaching the GL surface itself. */
    fun setSceneActive(value: Boolean) {
        queueEvent { spectraRenderer.setActive(value) }
        if (value) requestRender()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        syncWindowOrigin()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) syncWindowOrigin()
    }

    private fun syncWindowOrigin() {
        getLocationInWindow(locationInWindow)
        val windowX = locationInWindow[0].toFloat()
        val windowY = locationInWindow[1].toFloat()
        queueEvent { spectraRenderer.setWindowOrigin(windowX, windowY) }
    }

    override fun onResume() {
        spectraRenderer.setActive(true)
        super.onResume()
    }

    override fun onPause() {
        spectraRenderer.setActive(false)
        super.onPause()
    }

}

private class SpectraGlRenderer(
    private val opticalRendererOwnerId: Long,
) : GLSurfaceView.Renderer {
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
        }
    private var sceneProgram = 0
    private var blitProgram = 0
    private var opticalProgram = 0
    private var sceneHandles: SceneHandles? = null
    private var blitHandles: BlitHandles? = null
    private var opticalHandles: OpticalHandles? = null
    private val glassAtmospheres = mutableMapOf<Long, GlassAtmosphere>()
    private var sceneFramebuffer = 0
    private var sceneTexture = 0
    private var sceneWidth = 1
    private var sceneHeight = 1
    private var sceneFramebufferReady = false
    private var activeSeconds = 0f
    private var lastFrameAt = SystemClock.elapsedRealtime()
    @Volatile private var active = true
    private var environment = SpectraEnvironment.ORIGINAL
    private var previousEnvironment = SpectraEnvironment.ORIGINAL
    private var environmentTransitionAt = 0f
    private var quality = RenderQuality.AUTO
    private var darkMode = false
    private var glassEffects = GlassEffects()
    private var phase = SpectraPhase.AMBIENT
    private var pointerX = .62f
    private var pointerY = .45f
    private var pointerTargetX = .62f
    private var pointerTargetY = .45f
    private var width = 1
    private var height = 1
    private var windowOriginX = 0f
    private var windowOriginY = 0f

    fun configure(environment: SpectraEnvironment, quality: RenderQuality, darkMode: Boolean, phase: SpectraPhase, glassEffects: GlassEffects) {
        if (this.environment != environment) {
            previousEnvironment = this.environment
            this.environment = environment
            environmentTransitionAt = activeSeconds
        }
        this.quality = quality
        this.darkMode = darkMode
        this.glassEffects = glassEffects.active(dark = darkMode, motion = true)
        this.phase = phase
    }

    fun setActive(value: Boolean) {
        active = value
        lastFrameAt = SystemClock.elapsedRealtime()
    }

    fun setPointer(x: Float, y: Float) {
        pointerTargetX = x.coerceIn(0f, 1f)
        pointerTargetY = y.coerceIn(0f, 1f)
    }

    fun setWindowOrigin(x: Float, y: Float) {
        windowOriginX = x
        windowOriginY = y
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        sceneProgram = linkProgram(VERTEX_SHADER, SCENE_FRAGMENT_SHADER)
        blitProgram = linkProgram(VERTEX_SHADER, BLIT_FRAGMENT_SHADER)
        opticalProgram = linkProgram(VERTEX_SHADER, OPTICAL_FRAGMENT_SHADER)
        sceneHandles = sceneProgram.takeIf { it != 0 }?.let(::SceneHandles)
        blitHandles = blitProgram.takeIf { it != 0 }?.let(::BlitHandles)
        opticalHandles = opticalProgram.takeIf { it != 0 }?.let(::OpticalHandles)
        sceneFramebuffer = 0
        sceneTexture = 0
        sceneFramebufferReady = false
        glassAtmospheres.clear()
        val fallback = fallbackColor()
        GLES20.glClearColor(fallback[0], fallback[1], fallback[2], 1f)
        lastFrameAt = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
        createSceneFramebuffer()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (sceneProgram == 0) {
            val fallback = fallbackColor()
            GLES20.glClearColor(fallback[0], fallback[1], fallback[2], 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val deltaSeconds = (now - lastFrameAt).coerceIn(0L, 100L) / 1000f
        if (active) activeSeconds += deltaSeconds
        lastFrameAt = now
        val speed = if (quality == RenderQuality.LOW) .82f else 1f
        val seconds = activeSeconds
        val pointerAlpha = (1f - kotlin.math.exp(-4.2f * deltaSeconds)).coerceIn(0f, 1f)
        pointerX += (pointerTargetX - pointerX) * pointerAlpha
        pointerY += (pointerTargetY - pointerY) * pointerAlpha

        if (!sceneFramebufferReady || blitHandles == null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawScene(sceneSeconds = seconds * speed, railSeconds = seconds)
            return
        }

        // Pass 1: render the whole SPECTRA field once into a half-resolution scene texture.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES20.glViewport(0, 0, sceneWidth, sceneHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawScene(sceneSeconds = seconds * speed, railSeconds = seconds)

        // Pass 2: composite the untouched field to the phone-sized default framebuffer.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawSceneTexture()

        // LOW is the explicit solid-glass fallback. AUTO/HIGH get at most three live optics.
        if (quality != RenderQuality.LOW && opticalHandles != null) {
            drawOpticalGlassRegions(seconds * speed)
        }
    }

    private fun drawScene(sceneSeconds: Float, railSeconds: Float) {
        val handles = sceneHandles ?: return
        GLES20.glUseProgram(sceneProgram)
        bindFullscreenVertices(handles.position)
        GLES20.glUniform2f(handles.resolution, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(handles.time, sceneSeconds)
        GLES20.glUniform1f(handles.railTime, railSeconds)
        GLES20.glUniform2f(handles.pointer, pointerX, pointerY)
        GLES20.glUniform1f(handles.environment, environment.ordinal.toFloat())
        GLES20.glUniform1f(handles.previousEnvironment, previousEnvironment.ordinal.toFloat())
        GLES20.glUniform1f(
            handles.environmentMix,
            ((activeSeconds - environmentTransitionAt) / .6f).coerceIn(0f, 1f),
        )
        GLES20.glUniform1f(
            handles.quality,
            when (quality) { RenderQuality.LOW -> 0f; RenderQuality.AUTO -> 1f; RenderQuality.HIGH -> 2f },
        )
        GLES20.glUniform1f(handles.dark, if (darkMode) 1f else 0f)
        GLES20.glUniform1f(handles.phase, phase.ordinal.toFloat())
        GLES20.glUniform1f(handles.visualStyle, SpectraVisualStyleController.get().ordinal.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(handles.position)
    }

    private fun drawSceneTexture() {
        val handles = blitHandles ?: return
        GLES20.glUseProgram(blitProgram)
        bindFullscreenVertices(handles.position)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTexture)
        GLES20.glUniform1i(handles.scene, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisableVertexAttribArray(handles.position)
    }

    private fun drawOpticalGlassRegions(seconds: Float) {
        val handles = opticalHandles ?: return
        val regions = OpticalGlassRegistry.snapshot(
            rendererOwnerId = opticalRendererOwnerId,
            viewLeft = windowOriginX,
            viewTop = windowOriginY,
            viewWidth = width.toFloat(),
            viewHeight = height.toFloat(),
        )
        glassAtmospheres.keys.retainAll(regions.map { it.id }.toSet())
        if (regions.isEmpty()) return

        GLES20.glUseProgram(opticalProgram)
        bindFullscreenVertices(handles.position)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTexture)
        GLES20.glUniform1i(handles.scene, 0)
        GLES20.glUniform2f(handles.surfaceSize, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(handles.dark, if (darkMode) 1f else 0f)
        GLES20.glUniform1f(handles.time, seconds)
        GLES20.glUniform4f(handles.effects, if (glassEffects.deformation) 1f else 0f, if (glassEffects.rimLight) 1f else 0f, if (glassEffects.aurora) 1f else 0f, if (glassEffects.meteors) 1f else 0f)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)

        regions.forEach { region ->
            val rawLeft = region.boundsInWindow.left - windowOriginX
            val rawTop = region.boundsInWindow.top - windowOriginY
            val rawRight = region.boundsInWindow.right - windowOriginX
            val rawBottom = region.boundsInWindow.bottom - windowOriginY
            val regionWidth = rawRight - rawLeft
            val regionHeight = rawBottom - rawTop
            if (regionWidth < 2f || regionHeight < 2f) return@forEach

            // Clip GPU work to the viewport, but keep the original geometry in the shader so a
            // scrolling card does not grow a false rounded edge where the phone clips it.
            val clippedLeft = rawLeft.coerceIn(0f, width.toFloat())
            val clippedTop = rawTop.coerceIn(0f, height.toFloat())
            val clippedRight = rawRight.coerceIn(0f, width.toFloat())
            val clippedBottom = rawBottom.coerceIn(0f, height.toFloat())
            val glScissorBottom = height.toFloat() - clippedBottom
            val glRegionBottom = height.toFloat() - rawBottom
            GLES20.glScissor(
                clippedLeft.toInt(),
                glScissorBottom.toInt(),
                kotlin.math.ceil((clippedRight - clippedLeft).toDouble()).toInt().coerceAtMost(width - clippedLeft.toInt()),
                kotlin.math.ceil((clippedBottom - clippedTop).toDouble()).toInt().coerceAtMost(height - glScissorBottom.toInt()),
            )
            GLES20.glUniform4f(
                handles.region,
                rawLeft / width,
                glRegionBottom / height,
                regionWidth / width,
                regionHeight / height,
            )
            GLES20.glUniform2f(handles.regionSize, regionWidth, regionHeight)
            GLES20.glUniform1f(
                handles.cornerRadius,
                region.cornerRadiusPx.coerceIn(0f, minOf(regionWidth, regionHeight) * .5f),
            )
            GLES20.glUniform1f(handles.refraction, region.refractionPx)
            GLES20.glUniform1f(handles.dispersion, region.dispersionPx)
            GLES20.glUniform1f(handles.flow, region.flowPx)
            GLES20.glUniform1f(handles.bodyOpacity, region.bodyOpacity)
            GLES20.glUniform1f(handles.interaction, region.interaction)
            GLES20.glUniform2f(handles.touch, region.touch.x, 1f - region.touch.y)
            val atmosphere = glassAtmospheres.getOrPut(region.id) { GlassAtmosphere() }
            atmosphere.update(seconds, if (darkMode) region.interaction else 0f, regionWidth, regionHeight, glassEffects.meteors)
            GLES20.glUniform1f(handles.auroraColor, atmosphere.hue)
            GLES20.glUniform1f(handles.effectSeed, atmosphere.phase)
            GLES20.glUniform4fv(handles.meteorHead, 5, atmosphere.heads, 0)
            GLES20.glUniform2fv(handles.meteorDir, 5, atmosphere.directions, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisableVertexAttribArray(handles.position)
    }

    private fun bindFullscreenVertices(position: Int) {
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
    }

    private fun createSceneFramebuffer() {
        if (sceneTexture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(sceneTexture), 0)
            sceneTexture = 0
        }
        if (sceneFramebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(sceneFramebuffer), 0)
            sceneFramebuffer = 0
        }

        sceneWidth = (width / 2).coerceAtLeast(1)
        sceneHeight = (height / 2).coerceAtLeast(1)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        sceneTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sceneTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            sceneWidth,
            sceneHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        sceneFramebuffer = framebuffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            sceneTexture,
            0,
        )
        sceneFramebufferReady = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        if (!sceneFramebufferReady) {
            Log.w("CaesarOpticalGlass", "Half-resolution scene framebuffer is unavailable; using solid glass fallback")
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /** Uniform/attribute lookups are driver work; resolve them once per GL context. */
    private class SceneHandles(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val resolution = GLES20.glGetUniformLocation(program, "uResolution")
        val time = GLES20.glGetUniformLocation(program, "uTime")
        val railTime = GLES20.glGetUniformLocation(program, "uRailTime")
        val pointer = GLES20.glGetUniformLocation(program, "uPointer")
        val environment = GLES20.glGetUniformLocation(program, "uEnvironment")
        val previousEnvironment = GLES20.glGetUniformLocation(program, "uPreviousEnvironment")
        val environmentMix = GLES20.glGetUniformLocation(program, "uEnvironmentMix")
        val quality = GLES20.glGetUniformLocation(program, "uQuality")
        val dark = GLES20.glGetUniformLocation(program, "uDark")
        val phase = GLES20.glGetUniformLocation(program, "uPhase")
        val visualStyle = GLES20.glGetUniformLocation(program, "uVisualStyle")
    }

    private class BlitHandles(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val scene = GLES20.glGetUniformLocation(program, "uScene")
    }

    private class OpticalHandles(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val scene = GLES20.glGetUniformLocation(program, "uScene")
        val surfaceSize = GLES20.glGetUniformLocation(program, "uSurfaceSize")
        val region = GLES20.glGetUniformLocation(program, "uRegion")
        val regionSize = GLES20.glGetUniformLocation(program, "uRegionSize")
        val cornerRadius = GLES20.glGetUniformLocation(program, "uCornerRadius")
        val refraction = GLES20.glGetUniformLocation(program, "uRefractionPx")
        val dispersion = GLES20.glGetUniformLocation(program, "uDispersionPx")
        val flow = GLES20.glGetUniformLocation(program, "uFlowPx")
        val bodyOpacity = GLES20.glGetUniformLocation(program, "uBodyOpacity")
        val interaction = GLES20.glGetUniformLocation(program, "uInteraction")
        val touch = GLES20.glGetUniformLocation(program, "uTouch")
        val auroraColor = GLES20.glGetUniformLocation(program, "uAuroraColor")
        val effectSeed = GLES20.glGetUniformLocation(program, "uEffectSeed")
        val effects = GLES20.glGetUniformLocation(program, "uEffects")
        val meteorHead = GLES20.glGetUniformLocation(program, "uMeteorHead[0]")
        val meteorDir = GLES20.glGetUniformLocation(program, "uMeteorDir[0]")
        val dark = GLES20.glGetUniformLocation(program, "uDark")
        val time = GLES20.glGetUniformLocation(program, "uTime")
    }

    private fun linkProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("CaesarSpectra", "Shader compilation failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }
        val vertexShader = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (vertexShader == 0 || fragmentShader == 0) return 0
        val linkedProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(linkedProgram, vertexShader)
        GLES20.glAttachShader(linkedProgram, fragmentShader)
        GLES20.glLinkProgram(linkedProgram)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(linkedProgram, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (linked[0] == 0) {
            Log.e("CaesarSpectra", "Shader link failed: ${GLES20.glGetProgramInfoLog(linkedProgram)}")
            GLES20.glDeleteProgram(linkedProgram)
            return 0
        }
        return linkedProgram
    }

    private fun fallbackColor(): FloatArray {
        if (darkMode) {
            return when (environment) {
                SpectraEnvironment.AURORA -> floatArrayOf(.043f, .098f, .082f)
                SpectraEnvironment.ORIGINAL,
                SpectraEnvironment.OCEAN,
                SpectraEnvironment.ULTRAVIOLET,
                SpectraEnvironment.EMBER -> floatArrayOf(.051f, .078f, .133f)
            }
        }
        return when (environment) {
            SpectraEnvironment.ORIGINAL -> floatArrayOf(.91f, .93f, .98f)
            SpectraEnvironment.OCEAN -> floatArrayOf(.86f, .95f, .98f)
            SpectraEnvironment.ULTRAVIOLET -> floatArrayOf(.92f, .89f, .99f)
            SpectraEnvironment.EMBER -> floatArrayOf(.99f, .92f, .88f)
            SpectraEnvironment.AURORA -> floatArrayOf(.88f, .96f, .91f)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            varying vec2 vUv;
            void main() { vUv = aPosition * .5 + .5; gl_Position = vec4(aPosition, 0., 1.); }
        """
        private const val SCENE_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform vec2 uResolution;
            uniform vec2 uPointer;
            uniform float uTime;
            uniform float uRailTime;
            uniform float uEnvironment;
            uniform float uPreviousEnvironment;
            uniform float uEnvironmentMix;
            uniform float uQuality;
            uniform float uDark;
            uniform float uPhase;
            uniform float uVisualStyle;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float noise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
                    mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0)), f.x), f.y);
            }
            float fbm(vec2 p) {
                // AUTO spends two octaves so its higher frame cadence stays inside the mobile
                // budget. HIGH restores the third octave; LOW never enters this path.
                float value = 0.55 * noise(p);
                p = p * 2.03 + 11.7;
                value += 0.2585 * noise(p);
                if (uQuality > 1.5) {
                    p = p * 2.03 + 11.7;
                    value += 0.121495 * noise(p);
                }
                return value;
            }
            vec2 liquidDropletField(
                vec2 p,
                vec2 origin,
                vec2 scattered,
                vec2 gathered,
                vec2 radius,
                float scatter,
                float gather,
                float t,
                float seed
            ) {
                vec2 centre = mix(origin, scattered, scatter);
                centre = mix(centre, gathered, gather);
                float breathe = 1.0 + 0.052 * sin(t * 0.78 + seed * 6.2831);
                vec2 shapedRadius = radius * vec2(breathe, 2.0 - breathe);
                vec2 q = (p - centre) / shapedRadius;
                // A calm pear/teardrop deformation gives each drop a soft shoulder and a heavier
                // belly. It is deterministic and low-frequency, so it reads as surface tension
                // rather than a particle sprite or a noisy metaball.
                float pear = sin(seed * 9.17);
                q.x *= 1.0 + clamp(q.y, -0.8, 0.8) * (0.10 + 0.035 * pear);
                q.y += q.x * q.x * 0.10 * pear;
                float body = exp(-dot(q, q) * 1.48);
                vec2 specularCentre = centre + vec2(-shapedRadius.x * 0.26, shapedRadius.y * 0.28);
                vec2 highlightQ = (p - specularCentre) / (shapedRadius * 0.42);
                float highlight = exp(-dot(highlightQ, highlightQ) * 2.15);
                return vec2(body, highlight);
            }
            vec2 liquidUnion(vec2 a, vec2 b) {
                // Probability-union is a smooth, bounded union for density fields. Overlapping
                // drops create a soft liquid neck without additive white blow-out or a hard max.
                return vec2(
                    1.0 - (1.0 - clamp(a.x, 0.0, 1.0)) * (1.0 - clamp(b.x, 0.0, 1.0)),
                    1.0 - (1.0 - clamp(a.y, 0.0, 1.0)) * (1.0 - clamp(b.y, 0.0, 1.0))
                );
            }
            vec3 accentPalette(float environment, float index) {
                vec3 cyan = vec3(0.08, 0.70, 0.78);
                vec3 violet = vec3(0.38, 0.30, 0.86);
                vec3 warm = vec3(0.96, 0.56, 0.27);
                vec3 rose = vec3(0.85, 0.43, 0.62);
                if (environment < 0.5) { if (index < 0.5) return cyan; if (index < 1.5) return violet; return warm; }
                if (environment < 1.5) { if (index < 0.5) return vec3(0.03, 0.45, 0.60); if (index < 1.5) return cyan; return vec3(0.18, 0.34, 0.78); }
                if (environment < 2.5) { if (index < 0.5) return vec3(0.22, 0.14, 0.60); if (index < 1.5) return violet; return rose; }
                if (environment < 3.5) { if (index < 0.5) return vec3(0.73, 0.22, 0.13); if (index < 1.5) return warm; return rose; }
                if (index < 0.5) return vec3(0.055, 0.50, 0.32);
                if (index < 1.5) return vec3(0.20, 0.78, 0.50);
                return vec3(0.60, 0.82, 0.32);
            }
            vec3 bodyPalette(float environment) {
                if (environment < 0.5) return vec3(0.145, 0.165, 0.220);
                if (environment < 1.5) return vec3(0.080, 0.205, 0.260);
                if (environment < 2.5) return vec3(0.155, 0.105, 0.255);
                if (environment < 3.5) return vec3(0.265, 0.135, 0.095);
                return vec3(0.070, 0.230, 0.160);
            }
            vec3 envAccent(float index) {
                float amount = smoothstep(0.0, 1.0, uEnvironmentMix);
                return mix(accentPalette(uPreviousEnvironment, index), accentPalette(uEnvironment, index), amount);
            }
            vec3 envBody() {
                float amount = smoothstep(0.0, 1.0, uEnvironmentMix);
                return mix(bodyPalette(uPreviousEnvironment), bodyPalette(uEnvironment), amount);
            }
            float softLobe(vec2 p, vec2 centre, vec2 radius) {
                vec2 q = (p - centre) / radius;
                return exp(-dot(q, q) * 1.05);
            }
            // Shared continuous liquid rail for both CLASSIC and FLUID. x is normalised to the
            // viewport at each call site. railT scales wall-clock time by 0.20, so 5.2 units are
            // exactly 26 seconds at every quality: 0-20% stretch, 20-38% bridged split, 38-62% droplets,
            // 62-82% attraction, then a pressure-like merge. x/y are body/specular masks.
            vec2 silverRailMask(vec2 railP, float railAxis, float t) {
                float cycle = fract(t / 5.2);
                float stretch = smoothstep(0.01, 0.08, cycle) *
                    (1.0 - smoothstep(0.22, 0.38, cycle));
                float fork = smoothstep(0.18, 0.29, cycle) *
                    (1.0 - smoothstep(0.82, 0.96, cycle));
                float droplets = smoothstep(0.34, 0.41, cycle) *
                    (1.0 - smoothstep(0.82, 0.97, cycle));
                float scatter = smoothstep(0.38, 0.50, cycle);
                float gather = smoothstep(0.62, 0.82, cycle);

                float x = railP.x;
                float axis = railAxis - 0.012;
                axis += sin(x * 5.8 - t * 0.62) * 0.010 * stretch;
                float neckWave = 0.5 + 0.5 * sin(x * 12.4 + t * 0.48);
                float singleWidth = 0.034 * (1.0 - 0.34 * stretch * neckWave);
                float singleField = exp(-pow(axis / singleWidth, 2.0));

                float branchDistance = (0.018 + 0.040 * fork) * fork;
                float branchWarp = sin(x * 7.1 - t * 0.54) * 0.007 * fork;
                float branchWidth = mix(0.031, 0.021, fork);
                float upperBranch = exp(-pow(
                    (axis - branchDistance - branchWarp) / branchWidth,
                    2.0
                ));
                float lowerBranch = exp(-pow(
                    (axis + branchDistance + branchWarp) / branchWidth,
                    2.0
                ));
                float branchField = (upperBranch + lowerBranch) * 0.58;

                // Uneven vertical necks form true liquid bridges while the rail forks. Their
                // contribution fades continuously as droplets pull free; there is no binary cut.
                float bridgeSpan = branchDistance + 0.024;
                float bridgeEnvelope = exp(-pow(axis / bridgeSpan, 2.0));
                float bridgeNodes = (
                    exp(-pow((x + 0.285) / 0.070, 2.0)) * 0.72 +
                    exp(-pow((x - 0.035) / 0.092, 2.0)) * 0.88 +
                    exp(-pow((x - 0.335) / 0.062, 2.0)) * 0.64
                ) * bridgeEnvelope * fork * (1.0 - droplets * 0.86);
                float liquidThread = exp(-pow(axis / 0.014, 2.0)) *
                    fork * (1.0 - droplets * 0.90) * 0.15;
                float railField = mix(singleField, branchField, fork) +
                    bridgeNodes * 0.46 + liquidThread;

                vec2 dropField = vec2(0.0);
                if (droplets > 0.001) {
                    vec2 dropP = vec2(x, axis);
                    float branchOrigin = 0.052 * fork;
                    // LOW retains four deliberately different liquid drops. AUTO/HIGH add three
                    // smaller drops between them; neither branch uses a grid, hash, or bead wave.
                    dropField = liquidUnion(dropField, liquidDropletField(
                        dropP,
                        vec2(-0.38, -branchOrigin),
                        vec2(-0.44, -0.155),
                        vec2(-0.090, -0.030),
                        vec2(0.058, 0.041),
                        scatter,
                        gather,
                        t,
                        0.13
                    ));
                    dropField = liquidUnion(dropField, liquidDropletField(
                        dropP,
                        vec2(-0.12, branchOrigin),
                        vec2(-0.18, 0.205),
                        vec2(-0.030, 0.034),
                        vec2(0.039, 0.056),
                        scatter,
                        gather,
                        t,
                        0.37
                    ));
                    dropField = liquidUnion(dropField, liquidDropletField(
                        dropP,
                        vec2(0.14, -branchOrigin),
                        vec2(0.19, -0.215),
                        vec2(0.038, -0.024),
                        vec2(0.048, 0.036),
                        scatter,
                        gather,
                        t,
                        0.61
                    ));
                    dropField = liquidUnion(dropField, liquidDropletField(
                        dropP,
                        vec2(0.39, branchOrigin),
                        vec2(0.44, 0.148),
                        vec2(0.096, 0.027),
                        vec2(0.062, 0.047),
                        scatter,
                        gather,
                        t,
                        0.89
                    ));
                    if (uQuality > 0.5) {
                        dropField = liquidUnion(dropField, liquidDropletField(
                            dropP,
                            vec2(-0.265, branchOrigin),
                            vec2(-0.315, 0.112),
                            vec2(-0.064, 0.018),
                            vec2(0.031, 0.037),
                            scatter,
                            gather,
                            t,
                            0.24
                        ));
                        dropField = liquidUnion(dropField, liquidDropletField(
                            dropP,
                            vec2(-0.005, -branchOrigin),
                            vec2(-0.042, -0.132),
                            vec2(-0.010, -0.014),
                            vec2(0.035, 0.030),
                            scatter,
                            gather,
                            t,
                            0.49
                        ));
                        dropField = liquidUnion(dropField, liquidDropletField(
                            dropP,
                            vec2(0.270, branchOrigin),
                            vec2(0.315, 0.188),
                            vec2(0.068, 0.016),
                            vec2(0.036, 0.052),
                            scatter,
                            gather,
                            t,
                            0.76
                        ));
                    }
                }

                float railKeep = 1.0 - droplets * 0.92;
                float railDensity = clamp(railField * railKeep, 0.0, 1.0);
                float dropDensity = clamp(dropField.x * droplets, 0.0, 1.0);
                float density = 1.0 - (1.0 - railDensity) * (1.0 - dropDensity);
                // During attraction the drop fields overlap into one softly necked cluster before
                // the re-forming rail takes over, preserving the visual sense of mass.
                density += exp(-pow(x / 0.165, 2.0) - pow(axis / 0.064, 2.0)) *
                    gather * droplets * 0.28;
                float body = smoothstep(0.16, 0.62, density);

                float singleHighlight = exp(-pow((axis - singleWidth * 0.32) /
                    max(singleWidth * 0.24, 0.006), 2.0));
                float branchHighlight = (
                    exp(-pow((axis - branchDistance - branchWarp - 0.006) / 0.008, 2.0)) +
                    exp(-pow((axis + branchDistance + branchWarp - 0.006) / 0.008, 2.0))
                ) * 0.64;
                float highlightDensity = mix(singleHighlight, branchHighlight, fork) * railKeep +
                    dropField.y * droplets * 0.86;
                float tensionEdge = smoothstep(0.10, 0.26, density) *
                    (1.0 - smoothstep(0.46, 0.72, density));
                float highlight = clamp(
                    smoothstep(0.22, 0.72, highlightDensity) * body + tensionEdge * 0.22,
                    0.0,
                    1.0
                );
                return vec2(body, highlight);
            }
            vec3 classicScene(vec2 uv, vec2 p, float aspect, float t, float railT) {
                vec3 icePaper = mix(vec3(0.948, 0.956, 0.982), vec3(0.051, 0.078, 0.133), uDark);
                vec3 paperLift = mix(vec3(0.989, 0.992, 1.000), vec3(0.070, 0.096, 0.155), uDark);
                vec3 color = mix(icePaper, paperLift, smoothstep(0.0, 1.0, uv.y) * 0.24);

                vec2 pointer = vec2((uPointer.x - 0.5) * aspect, uPointer.y - 0.5);
                float pointerWeight = exp(-dot(p - pointer, p - pointer) * 18.0);
                p += normalize(p - pointer + vec2(0.0001)) * pointerWeight * 0.008;

                // CLASSIC remains calm, but its three broad domains must still read as a living
                // environment behind the UI instead of a nearly white static sheet.
                // LOW reuses one cheap noise sample instead of adding an fbm path.
                float flowA = noise(p * 1.55 + vec2(t * 0.12, -t * 0.09));
                float flowB = uQuality < 0.5
                    ? flowA
                    : noise(p * 2.10 + vec2(-t * 0.08, t * 0.11));
                vec2 fieldP = p + vec2(flowA - 0.5, flowB - 0.5) * 0.038;
                vec2 c0 = vec2(-0.13 * aspect + 0.020 * sin(t * 0.82), -0.315 + 0.025 * cos(t * 0.64));
                vec2 c1 = vec2(0.16 * aspect + 0.018 * cos(t * 0.71), 0.015 + 0.028 * sin(t * 0.57));
                vec2 c2 = vec2(-0.08 * aspect + 0.022 * sin(t * 0.53), 0.345 + 0.022 * cos(t * 0.76));
                float w0 = softLobe(fieldP, c0, vec2(0.250 + aspect * 0.080, 0.270));
                float w1 = softLobe(fieldP, c1, vec2(0.270 + aspect * 0.070, 0.290));
                float w2 = softLobe(fieldP, c2, vec2(0.240 + aspect * 0.090, 0.260));
                float sum = w0 + w1 + w2;
                vec3 pigment = (envAccent(0.0) * w0 + envAccent(1.0) * w1 + envAccent(2.0) * w2) /
                    max(sum, 0.001);
                vec3 palePigment = mix(
                    mix(vec3(0.875, 0.925, 0.982), vec3(0.300, 0.385, 0.555), uDark),
                    pigment,
                    mix(0.72, 0.58, uDark)
                );
                vec3 dimensionalPigment = mix(palePigment, envBody(), mix(0.04, 0.12, uDark));
                float veil = smoothstep(0.04, 0.90, sum) * mix(0.48, 0.315, uDark);
                veil *= 0.82 + flowA * 0.18;
                color = mix(color, dimensionalPigment, veil);

                // Reuse the existing field samples for low-frequency illumination. This restores
                // depth without adding particles, extra noise octaves, or a second colour layer.
                float relief = (flowA - 0.5) * 0.055 + (flowB - 0.5) * 0.035;
                color += relief * mix(vec3(0.70, 0.82, 1.00), vec3(0.24, 0.38, 0.62), uDark);

                // A single open pearl fold adds depth across the whole viewport. It is deliberately
                // broad and low-contrast: no closed silhouette, no billiard-like object, no HUD rim.
                float foldAxis = fieldP.y + 0.12 * sin(fieldP.x * 4.0 - t * 0.36) +
                    (flowB - 0.5) * 0.08;
                float foldShade = exp(-pow((foldAxis + 0.075) / 0.155, 2.0));
                float foldLight = exp(-pow((foldAxis - 0.095) / 0.210, 2.0));
                color = mix(color, mix(envBody(), color, 0.36), foldShade * mix(0.080, 0.14, uDark));
                color = mix(color, paperLift, foldLight * mix(0.090, 0.08, uDark));
                vec2 classicRail = silverRailMask(
                    vec2(fieldP.x / max(aspect, 0.001), fieldP.y),
                    foldAxis,
                    railT
                );
                vec3 classicRailReflection = mix(
                    mix(vec3(0.985, 0.995, 1.0), vec3(0.62, 0.73, 0.88), uDark),
                    envAccent(1.0),
                    mix(0.12, 0.18, uDark)
                );
                color = mix(
                    color,
                    classicRailReflection,
                    classicRail.x * mix(0.70, 0.50, uDark)
                );
                color = mix(color, vec3(1.0), classicRail.y * mix(0.30, 0.20, uDark));
                float classicLuma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                color += vec3(max(0.0, 0.700 - classicLuma)) * (1.0 - uDark);
                return clamp(color, 0.0, 1.0);
            }
            vec2 fluidCoordinates(vec2 p, float aspect, float t) {
                // Work in viewport-normalised space so the environment stays full-canvas on
                // both tall phones and wide previews. There is no closed SDF or object boundary.
                vec2 q = vec2(p.x / max(aspect, 0.001), p.y);
                vec2 pointer = uPointer - 0.5;
                float pointerWeight = exp(-dot(q - pointer, q - pointer) * 13.0);
                q += normalize(q - pointer + vec2(0.0001)) * pointerWeight * 0.004;
                return q;
            }
            vec3 fluidEnvironmentScene(
                vec2 uv,
                vec2 p,
                float aspect,
                float t,
                float railT,
                float thinking,
                float focusing
            ) {
                vec2 baseQ = fluidCoordinates(p, aspect, t);
                float broad = (uQuality < 0.5
                    ? noise(baseQ * 1.18 + vec2(t * 0.075, -t * 0.055))
                    : fbm(baseQ * 1.32 + vec2(t * 0.085, -t * 0.060))) - 0.46;
                float cross = noise(baseQ.yx * vec2(1.42, 1.18) + vec2(-t * 0.052, t * 0.070)) - 0.5;
                vec2 q = baseQ + vec2(broad, cross) * (uQuality < 0.5 ? 0.035 : 0.052);
                mat2 turn = mat2(0.82, -0.57, 0.57, 0.82);
                vec2 r = turn * q;

                // Reuse the warp samples as colour fields: AUTO is three noise evaluations per
                // pixel (one two-octave fbm plus one noise), HIGH is four, and LOW is two.
                float flowA = clamp(broad + 0.46, 0.0, 1.0);
                float flowB = clamp(cross + 0.50, 0.0, 1.0);
                float flowC = clamp(mix(flowA, flowB, 0.46), 0.0, 1.0);

                // One open field continuously changes topology instead of translating a fixed
                // fold. The three phases connect opposite viewport edges and loop in 28 seconds
                // at normal speed (about 34 seconds on LOW), without a closed SDF or extra layer.
                float bendA = sin(r.x * 4.60 - t * 0.85);
                float bendB = sin(r.y * 4.05 + t * 0.68);
                float bendC = sin((r.x + r.y) * 3.45 - t * 0.54);
                float topologyA = r.y + bendA * 0.16 + (flowA - 0.46) * 0.16;
                float topologyB = r.x * 0.72 + r.y * 0.18 + bendB * 0.15 +
                    (flowB - 0.50) * 0.14;
                float topologyC = r.y * 0.56 - r.x * 0.48 + bendC * 0.14 +
                    (flowC - 0.48) * 0.18;
                float macroSegment = fract(t / 5.60) * 3.0;
                float topologyMorph = smoothstep(0.08, 0.92, fract(macroSegment));
                float topologyAxis;
                if (macroSegment < 1.0) {
                    topologyAxis = mix(topologyA, topologyB, topologyMorph);
                } else if (macroSegment < 2.0) {
                    topologyAxis = mix(topologyB, topologyC, topologyMorph);
                } else {
                    topologyAxis = mix(topologyC, topologyA, topologyMorph);
                }

                // Two open waves carry colour through every edge of the viewport. Unlike the
                // former ellipse field, these contours never close into a ball or focal object.
                float openSweepA = 0.5 + 0.5 * sin(
                    r.x * 3.15 + r.y * 1.70 + t * 0.82 + broad * 1.25
                );
                float openSweepB = 0.5 + 0.5 * sin(
                    -r.x * 1.90 + r.y * 2.65 - t * 0.63 + cross * 1.10
                );

                vec3 base = mix(vec3(0.966, 0.970, 0.982), vec3(0.030, 0.043, 0.071), uDark);
                vec3 lifted = mix(vec3(0.995, 0.995, 0.999), vec3(0.067, 0.082, 0.125), uDark);
                float verticalLight = 0.28 + 0.36 * smoothstep(0.0, 1.0, uv.y);
                vec3 color = mix(base, lifted, verticalLight);

                vec3 pearlNeutral = mix(vec3(0.914, 0.944, 0.985), vec3(0.095, 0.137, 0.220), uDark);
                vec3 tintA = mix(pearlNeutral, envAccent(0.0), mix(0.66, 0.52, uDark));
                vec3 tintB = mix(pearlNeutral, envAccent(1.0), mix(0.62, 0.50, uDark));
                vec3 tintC = mix(pearlNeutral, envAccent(2.0), mix(0.58, 0.48, uDark));
                vec3 pigment = mix(tintA, tintB, smoothstep(0.16, 0.84, openSweepA));
                pigment = mix(pigment, tintC, smoothstep(0.20, 0.86, openSweepB) * 0.68);
                float zone = smoothstep(-0.24, 0.24, topologyAxis);
                vec3 zoneTint = mix(tintA, tintC, zone);
                pigment = mix(pigment, zoneTint, 0.32);

                // Keep the central reading area calm while retaining a continuous fluid field.
                vec2 readingQ = (q - vec2(0.0, -0.015)) / vec2(0.46, 0.38);
                float readingQuiet = exp(-dot(readingQ, readingQ) * 1.20);
                float phaseEnergy = thinking * 0.16 + focusing * 0.08;
                float pigmentStrength = mix(0.50, 0.40, uDark) *
                    (0.94 + (flowA - 0.46) * 0.20 + phaseEnergy) *
                    (1.0 - readingQuiet * 0.12);
                color = mix(color, pigment, clamp(pigmentStrength, 0.40, 0.58));

                // A broad pearl illumination moves through the field without producing a rim.
                float pearlLight = (flowA - 0.46) * 0.13 + (flowB - 0.50) * 0.08;
                pearlLight *= 1.0 - readingQuiet * 0.14;
                color += pearlLight * mix(vec3(0.80, 0.90, 1.00), vec3(0.38, 0.52, 0.78), uDark);

                // The same morphing open axis carries depth and colour, keeping FLUID a single
                // connected environment rather than overlapping ribbons or a floating object.
                float foldAxis = topologyAxis + 0.020;
                float foldShadow = exp(-pow((foldAxis + 0.055) / 0.105, 2.0));
                float foldGlow = exp(-pow((foldAxis - 0.080) / 0.170, 2.0));
                // CLASSIC and FLUID deliberately share the exact same rail lifecycle. FLUID only
                // changes its carrier axis, so switching visual systems never hides the feature.
                vec2 foldPearl = silverRailMask(r, foldAxis, railT);
                float foldCalm = 1.0 - readingQuiet * 0.34;
                vec3 shade = mix(vec3(0.220, 0.280, 0.440), vec3(0.020, 0.055, 0.120), uDark);
                vec3 glow = mix(vec3(0.995, 0.997, 1.000), vec3(0.250, 0.355, 0.565), uDark);
                vec3 railReflection = mix(vec3(1.0), envAccent(1.0), mix(0.10, 0.16, uDark));
                color = mix(color, shade, foldShadow * foldCalm * mix(0.78, 0.58, uDark));
                color = mix(color, glow, foldGlow * foldCalm * mix(0.34, 0.26, uDark));
                color = mix(color, railReflection, foldPearl.x * foldCalm * mix(0.68, 0.48, uDark));
                color = mix(color, vec3(1.0), foldPearl.y * foldCalm * mix(0.30, 0.20, uDark));

                // Caustics stay attached to the open fold, so colour reads as refraction through
                // the environment instead of a decorative rainbow outline.
                float cyanCaustic = exp(-pow((foldAxis + 0.155) / 0.052, 2.0)) * foldCalm;
                float violetCaustic = exp(-pow((foldAxis - 0.145) / 0.060, 2.0)) * foldCalm;
                float warmCaustic = exp(-pow((foldAxis - 0.235) / 0.085, 2.0)) * foldCalm;
                color = mix(color, envAccent(0.0), cyanCaustic * 0.18);
                color = mix(color, envAccent(1.0), violetCaustic * 0.16);
                color = mix(color, envAccent(2.0), warmCaustic * 0.09);

                // Channel-separated light is tied to the folds, not painted as a rainbow edge.
                float dispersion = (foldGlow - foldShadow) * foldCalm *
                    (0.014 + thinking * 0.003);
                color += vec3(dispersion * 0.42, -abs(dispersion) * 0.08, -dispersion * 0.36);
                // Lift luminance rather than clamping individual channels. A per-channel floor
                // flattened the colour field into a uniform lavender sheet on real devices.
                float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                float lumaFloor = mix(0.545, 0.105, uDark);
                color += vec3(max(0.0, lumaFloor - luma));
                return clamp(color, 0.0, 1.0);
            }
            void main() {
                vec2 uv = vUv;
                float aspect = uResolution.x / uResolution.y;
                vec2 p = vec2((uv.x - 0.5) * aspect, uv.y - 0.5);
                float t = uTime * 0.20;
                float railT = uRailTime * 0.20;
                if (uVisualStyle < 0.5) {
                    gl_FragColor = vec4(classicScene(uv, p, aspect, t, railT), 1.0);
                    return;
                }
                float thinking = 1.0 - smoothstep(0.25, 0.75, abs(uPhase - 1.0));
                float focusing = 1.0 - smoothstep(0.25, 0.75, abs(uPhase - 2.0));
                gl_FragColor = vec4(fluidEnvironmentScene(uv, p, aspect, t, railT, thinking, focusing), 1.0);
            }
        """

        private const val BLIT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uScene;
            void main() { gl_FragColor = texture2D(uScene, vUv); }
        """

        private const val OPTICAL_FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vUv;
            uniform sampler2D uScene;
            uniform vec2 uSurfaceSize;
            uniform vec4 uRegion;
            uniform vec2 uRegionSize;
            uniform float uCornerRadius;
            uniform float uRefractionPx;
            uniform float uDispersionPx;
            uniform float uFlowPx;
            uniform float uBodyOpacity;
            uniform float uInteraction;
            uniform vec2 uTouch;
            uniform float uAuroraColor;
            uniform float uEffectSeed;
            uniform vec4 uEffects;
            uniform vec4 uMeteorHead[5];
            uniform vec2 uMeteorDir[5];
            uniform float uDark;
            uniform float uTime;

            float roundedRectSdf(vec2 p, vec2 halfSize, float radius) {
                vec2 q = abs(p) - (halfSize - vec2(radius));
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }

            vec2 roundedRectNormal(vec2 p, vec2 halfSize, float radius) {
                // A finite SDF gradient is stable for both flat sides and rounded corners on ES 2.
                float dx = roundedRectSdf(p + vec2(1.0, 0.0), halfSize, radius) -
                    roundedRectSdf(p - vec2(1.0, 0.0), halfSize, radius);
                float dy = roundedRectSdf(p + vec2(0.0, 1.0), halfSize, radius) -
                    roundedRectSdf(p - vec2(0.0, 1.0), halfSize, radius);
                return normalize(vec2(dx, dy) + vec2(0.0001));
            }

            vec3 saturateColor(vec3 color, float amount) {
                float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
                return mix(vec3(luma), color, amount);
            }

            void main() {
                vec2 local = (vUv - uRegion.xy) / uRegion.zw;
                vec2 halfSize = uRegionSize * 0.5;
                vec2 p = (local - 0.5) * uRegionSize;
                float radius = min(uCornerRadius, min(halfSize.x, halfSize.y));
                float sdf = roundedRectSdf(p, halfSize, radius);
                if (sdf > 0.0) discard;

                float distanceInside = max(-sdf, 0.0);
                float edgeBand = max(12.0, min(halfSize.x, halfSize.y) * 0.34);
                float edge = 1.0 - smoothstep(0.0, edgeBand, distanceInside);
                edge = edge * edge * (3.0 - 2.0 * edge);
                float curvature = pow(edge, 1.28);
                vec2 normal = roundedRectNormal(p, halfSize, radius);

                // The renderer moves only its scene texture. Compose paints labels and icons later,
                // so stronger optics never smear foreground content.
                vec2 centre = uRegion.xy + uRegion.zw * 0.5;
                float interior = 1.0 - edge;
                // A convex clear lens magnifies the owned scene; its thick rim bends in the
                // opposite direction. Text and controls remain sharp in the Compose layer.
                float dome = sqrt(max(0.0, 1.0 - edge * edge));
                vec2 focusUv = uRegion.xy + uRegion.zw * uTouch;
                vec2 focusDelta = (local - uTouch) * vec2(uRegionSize.x / max(uRegionSize.y, 1.0), 1.0);
                float touchFalloff = exp(-dot(focusDelta, focusDelta) * 4.0);
                vec2 lensWarp = (centre - vUv) * (0.045 + 0.085 * dome + uInteraction * uEffects.x * 0.055);
                lensWarp += (focusUv - vUv) * uInteraction * uEffects.x * touchFalloff * 0.20;

                // Two low-frequency waves replace the former sub-pixel micro warp. They bend the
                // background throughout the lens while keeping the texture-read count unchanged.
                float waveX = sin(dot(p, vec2(0.0105, 0.0145)) + uTime * 0.46);
                float waveY = sin(dot(p, vec2(-0.0130, 0.0090)) - uTime * 0.37);
                vec2 flowVector = vec2(waveX, waveY);
                vec2 flowDirection = normalize(vec2(waveY + 0.12, -waveX + 0.08) + vec2(0.0001));
                float flowBand = 0.5 + 0.5 * waveX * waveY;
                vec2 flowWarp = flowVector * (uFlowPx / uSurfaceSize) *
                    (0.52 + 0.48 * interior) * (0.78 + 0.44 * flowBand);
                vec2 edgeWarp = normal * (uRefractionPx / uSurfaceSize) * curvature;
                vec2 foldWarp = flowDirection * (uRefractionPx / uSurfaceSize) *
                    (0.14 + 0.10 * flowBand) * interior;

                // Chromatic separation follows both the rounded optical edge and the internal fold.
                // It remains scene-derived instead of becoming a decorative rainbow stroke.
                vec2 chromaDirection = normalize(
                    normal * (0.35 + 0.65 * curvature) +
                    flowDirection * (0.55 + 0.65 * interior) + vec2(0.0001)
                );
                float chromaAmount = 0.12 + 0.65 * curvature;
                vec2 dispersionWarp = chromaDirection * (uDispersionPx / uSurfaceSize) * chromaAmount * (1.0 - uDark);
                vec2 sampleUv = clamp(
                    vUv + lensWarp + edgeWarp + foldWarp + flowWarp,
                    vec2(0.001),
                    vec2(0.999)
                );

                float r = texture2D(uScene, clamp(sampleUv + dispersionWarp, vec2(0.001), vec2(0.999))).r;
                float g = texture2D(uScene, sampleUv).g;
                float b = texture2D(uScene, clamp(sampleUv - dispersionWarp, vec2(0.001), vec2(0.999))).b;
                vec3 refracted = saturateColor(vec3(r, g, b), 1.20) * 1.015;
                float caustic = flowBand * flowBand * interior;
                vec3 causticTint = mix(vec3(0.82, 0.94, 1.0), vec3(0.20, 0.34, 0.62), uDark);
                refracted = mix(refracted, causticTint, caustic * mix(0.065, 0.045, uDark));

                vec3 tint = mix(vec3(0.93, 0.975, 1.0), vec3(0.035, 0.060, 0.115), uDark);
                float body = mix(uBodyOpacity, uBodyOpacity * 0.72, uDark);
                vec3 glass = mix(refracted, tint, body);

                // Desktop LiquidDesktop's single-hue filament curtain and irregular meteors,
                // adapted to the existing mobile scene texture and touch lifecycle.
                float energy = clamp(abs(uInteraction), 0.0, 1.0);
                if (uDark > 0.5 && energy > 0.003) {
                    vec2 a = vec2(local.x, 1.0 - local.y);
                    float t = uTime + uEffectSeed;
                    float wave = 0.57 + sin(a.x * 5.5 + t * 0.38) * 0.16 + sin(a.x * 12.0 - t * 0.28) * 0.055;
                    float above = wave - a.y;
                    float curtain = exp(-max(above, 0.0) * 6.0) * smoothstep(-0.035, 0.015, above);
                    float filaments = 0.38 + 0.62 * pow(0.5 + 0.5 * sin(a.x * 73.0 + sin(a.x * 16.0 - t * 0.6) * 3.0 + t * 0.4), 2.0);
                    float hem = exp(-pow(above * 38.0, 2.0));
                    float veil = (curtain * filaments * 0.34 + hem * 0.28) * (0.65 + 0.35 * sin(a.x * 3.0 + t * 0.25));
                    glass += uEffects.z * energy * (uAuroraColor < 0.5 ? vec3(0.10, 0.85, 0.49) : vec3(0.95, 0.13, 0.28)) * veil;
                    for (int i = 0; i < 5; i++) {
                        if (uEffects.w > 0.5 && uMeteorHead[i].w > 0.001) {
                            vec2 dir = uMeteorDir[i];
                            vec2 rel = p - uMeteorHead[i].xy;
                            float trail = dot(-rel, dir);
                            float crossing = dot(rel, vec2(-dir.y, dir.x));
                            float streak = exp(-crossing * crossing / 3.0) * smoothstep(0.0, 4.0, trail) * (1.0 - smoothstep(10.0, uMeteorHead[i].z, trail));
                            float spark = exp(-dot(rel, rel) / 6.0);
                            glass += (streak * 0.72 + spark) * uMeteorHead[i].w * energy * vec3(0.66, 0.87, 1.0);
                        }
                    }
                }

                // Deliberately decorative cyan/violet light, independent of optical dispersion.
                vec2 lean = (uTouch - 0.5) * 2.0;
                vec2 edgeCursor = lean / max(max(abs(lean.x), abs(lean.y)), 0.05) * halfSize;
                vec2 edgeDelta = (p - edgeCursor) / max(min(halfSize.x, halfSize.y), 1.0);
                float following = exp(-dot(edgeDelta, edgeDelta) * 1.8);
                float rimFlow = 0.5 + 0.5 * sin((p.x + p.y) * 0.021 - uTime * 1.4);
                vec3 rimColor = mix(vec3(0.20, 0.88, 1.0), vec3(0.80, 0.43, 1.0), rimFlow);
                float rimBand = exp(-pow((distanceInside - 2.0) / 1.7, 2.0));
                glass += uEffects.y * rimColor * following * energy * (rimBand * 0.65 + exp(-distanceInside / 7.0) * 0.12);

                // Quiet inner illumination and a darker contact edge give the lens real thickness.
                float innerHighlight = (1.0 - smoothstep(0.0, 2.0, distanceInside)) * (1.0 - uDark * 0.35);
                float contact = smoothstep(0.0, 8.0, distanceInside) * (1.0 - smoothstep(8.0, 18.0, distanceInside));
                vec2 lightDirection = normalize(mix(vec2(-0.6, 0.8), (uTouch - 0.5) * 2.0 + vec2(0.001), clamp(abs(uInteraction), 0.0, 1.0)));
                float lightFacing = pow(max(dot(normal, lightDirection), 0.0), 3.0);
                glass += vec3(1.0) * innerHighlight * (0.045 + 0.24 * lightFacing + 0.12 * abs(uInteraction) * touchFalloff);
                glass *= 1.0 - contact * mix(0.025, 0.05, uDark);
                gl_FragColor = vec4(glass, 1.0);
            }
        """
    }
}
