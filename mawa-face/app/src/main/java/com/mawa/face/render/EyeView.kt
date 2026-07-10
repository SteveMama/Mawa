package com.mawa.face.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.mawa.face.scene.PanelSlot
import com.mawa.face.scene.ScenePanel
import com.mawa.face.weather.WeatherCondition
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fullscreen eye renderer. Pure black background (OLED pixels off) and two
 * emissive, glowing capsule eyes that carry the whole personality: they light
 * up in a mood-specific color, cast a soft halo, hold a bright catchlight, and
 * squash/tilt/dim with feeling. Everything else that used to crowd the face
 * (equalizer bars, orbiting motes, floating glyphs, corner text cards) is gone
 * — the face owns the screen. Room data lives on the dashboard, not the wall.
 *
 * Drawn at display refresh rate via postInvalidateOnAnimation. All motion comes
 * from [engine]; the look (color, glow, catchlight) is computed here.
 */
class EyeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    val engine = AnimationEngine()

    var debug = false
    var debugText = ""

    /** Raw face position (0..1 in camera frame), for the calibration overlay. */
    var rawFace: Pair<Float, Float>? = null

    /** Current sky + golden-hour warmth (0..1), driven from MainActivity. */
    var weather: WeatherCondition = WeatherCondition.CLEAR
    var warmth = 0f

    /** Kept for the dashboard + debug overlay; not painted on the face anymore. */
    var scenePanels: List<ScenePanel> = emptyList()
    var cloudAnimation: CloudAnimation? = null
        set(value) {
            field = value
            engine.cloudAnimation = value
        }

    private var lastFrameNs = 0L
    private var zClock = 0f
    private var flashClock = 0f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val lidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7FBF7F")
        textSize = 34f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#224422")
        strokeWidth = 2f
    }
    private val faceDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E24B4A")
        style = Paint.Style.FILL
    }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 120, 170, 210); strokeWidth = 3f
    }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 235, 240, 255); style = Paint.Style.FILL
    }
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 180, 185, 195); style = Paint.Style.FILL
    }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA6C0")
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA6C0")
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val panelRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#35404A")
        strokeWidth = 2f
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#7FBF7F")
    }
    private val eyePath = Path()

    private class Flake(var x: Float, var y: Float, var v: Float, var drift: Float, var r: Float)
    private val flakes = ArrayList<Flake>()
    private var flakeKind: WeatherCondition? = null

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else min(0.05f, (now - lastFrameNs) / 1e9f)
        lastFrameNs = now

        engine.update(dt)

        // Warm the black very slightly near golden hour (still OLED-dark)
        canvas.drawColor(Color.rgb((warmth * 20f).toInt(), (warmth * 9f).toInt(), 0))

        val cx = width / 2f + engine.driftX
        val cy = height / 2f + engine.driftY
        val eyeGap = width * 0.30f

        val (core, halo) = eyeColors()

        // Halos first (behind both eyes), then the glowing bodies on top.
        drawEyeHalo(canvas, cx - eyeGap / 2f, cy, halo, engine.left)
        drawEyeHalo(canvas, cx + eyeGap / 2f, cy, halo, engine.right)
        drawEye(canvas, cx - eyeGap / 2f, cy, engine.left, core, halo)
        drawEye(canvas, cx + eyeGap / 2f, cy, engine.right, core, halo)
        drawFocusFrame(canvas, cx, cy, eyeGap)

        updateAndDrawWeather(canvas, dt)

        if (engine.sleeping) {
            zClock += dt
            drawZzz(canvas, cx + eyeGap / 2f + width * 0.06f, cy - height * 0.16f)
        }

        if (debug) {
            drawScenePanels(canvas)
            drawCalibrationOverlay(canvas)
        }

        postInvalidateOnAnimation()
    }

    // --- color: mood is the primary read, energy/music tints toward palette ---

    private fun eyeColors(): Pair<Int, Int> {
        var core: Int
        var halo: Int
        when (engine.currentMood()) {
            Mood.HAPPY -> { core = 0xFFFFF0C8.toInt(); halo = 0xFFF2B24E.toInt() }
            Mood.GRUMPY -> { core = 0xFFBFC9CE.toInt(); halo = 0xFF4E626F.toInt() }
            Mood.SLEEPY -> { core = 0xFFEBD3AC.toInt(); halo = 0xFF6A5640.toInt() }
            Mood.SUSPICIOUS -> { core = 0xFFE6D4FF.toInt(); halo = 0xFF7A5AD6.toInt() }
            Mood.EXCITED -> { core = 0xFFFFFFFF.toInt(); halo = 0xFFFF7BC8.toInt() }
            Mood.NEUTRAL -> { core = 0xFFDFF6FF.toInt(); halo = 0xFF4FB4E6.toInt() }
        }

        // When the room feels charged/musical, drift toward the cloud palette.
        val energy = engine.visualEnergy()
        if (energy > 0.25f) {
            val pal = paletteColors(engine.palette())
            val t = ((energy - 0.25f) / 0.75f).coerceIn(0f, 0.7f)
            core = blend(core, lighten(pal.secondary, 0.30f), t)
            halo = blend(halo, pal.primary, t)
        }

        // A little golden-hour warmth on the glow.
        if (warmth > 0.02f) halo = blend(halo, 0xFFE9A25B.toInt(), warmth * 0.35f)

        if (engine.isSleeping()) {
            core = darken(core, 0.45f)
            halo = darken(halo, 0.55f)
        }
        return core to halo
    }

    private fun drawEyeHalo(canvas: Canvas, ex: Float, ey: Float, halo: Int, p: EyeParams) {
        val open = p.openness.coerceIn(0f, 1.2f)
        val openScale = 0.32f + 0.68f * open
        if (openScale <= 0.02f) return
        val energy = engine.visualEnergy()
        val beat = engine.beatLevel()
        val radius = (width * 0.155f) * (1.20f + 0.34f * energy + 0.46f * beat) * openScale
        if (radius < 1f) return
        val alpha = (78 + 92 * energy + 96 * beat).toInt().coerceIn(48, 226)
        haloPaint.shader = RadialGradient(
            ex, ey, radius,
            intArrayOf(withAlpha(halo, (alpha * openScale).toInt().coerceIn(0, 255)), withAlpha(halo, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(ex, ey, radius, haloPaint)
        haloPaint.shader = null
    }

    private fun drawEye(canvas: Canvas, ex: Float, ey: Float, p: EyeParams, core: Int, halo: Int) {
        val eyeW = width * 0.155f
        val eyeH = eyeW * 1.5f * p.squash

        val bounds = RectF(ex - eyeW / 2, ey - eyeH / 2, ex + eyeW / 2, ey + eyeH / 2)
        eyePath.reset()
        eyePath.addRoundRect(bounds, eyeW / 2, eyeW / 2, Path.Direction.CW)

        // Glowing body: bright, near-white core fading to the mood color, top-lit.
        bodyPaint.shader = RadialGradient(
            ex, ey - eyeH * 0.12f, eyeH * 0.72f,
            intArrayOf(lighten(core, 0.55f), core, darken(core, 0.26f)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(eyePath, bodyPaint)
        bodyPaint.shader = null

        canvas.save()
        canvas.clipPath(eyePath)

        // Soft dark iris/pupil (a deep tint of the glow, never a flat black hole).
        val pr = eyeW * 0.26f * p.pupilScale
        val px = ex + p.pupilX * eyeW * 0.30f
        val py = ey + p.pupilY * eyeH * 0.28f
        val pupilCol = darken(halo, 0.68f)
        pupilPaint.shader = RadialGradient(
            px, py, pr.coerceAtLeast(1f),
            intArrayOf(pupilCol, pupilCol, withAlpha(pupilCol, 0)),
            floatArrayOf(0f, 0.68f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(px, py, pr, pupilPaint)
        pupilPaint.shader = null

        // Wet catchlight: a bright glint plus a smaller secondary — the single
        // strongest cue that this is a living eye and not painted-on plastic.
        val hlR = eyeW * 0.12f
        highlightPaint.alpha = 235
        canvas.drawCircle(px - eyeW * 0.14f, py - eyeH * 0.13f, hlR, highlightPaint)
        highlightPaint.alpha = 150
        canvas.drawCircle(px + eyeW * 0.07f, py + eyeH * 0.05f, hlR * 0.45f, highlightPaint)

        // Upper lid: covers (1-openness) mostly from the top, tilted by lidAngle.
        val closed = 1f - p.openness
        if (closed > 0.005f) {
            val topLid = closed * eyeH * 0.72f
            canvas.save()
            canvas.rotate(p.lidAngle, ex, bounds.top + topLid)
            canvas.drawRect(
                bounds.left - eyeW, bounds.top - eyeH,
                bounds.right + eyeW, bounds.top + topLid, lidPaint
            )
            canvas.restore()

            // Lower lid rises a little as the eye closes.
            val bottomLid = closed * eyeH * 0.28f
            canvas.drawRect(
                bounds.left - eyeW, bounds.bottom - bottomLid,
                bounds.right + eyeW, bounds.bottom + eyeH, lidPaint
            )
        }
        canvas.restore()
    }

    // --- color helpers -----------------------------------------------------

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt().coerceIn(0, 255),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt().coerceIn(0, 255),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt().coerceIn(0, 255),
        )
    }

    private fun lighten(c: Int, t: Float) = blend(c, Color.WHITE, t)
    private fun darken(c: Int, t: Float) = blend(c, Color.BLACK, t)
    private fun withAlpha(c: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun drawFocusFrame(canvas: Canvas, cx: Float, cy: Float, eyeGap: Float) {
        if (!engine.shouldShowFocusFrame()) return
        focusPaint.alpha = if (engine.visualEnergy() > 0.14f) 170 else 110
        val bracketY = cy - height * 0.17f
        val bracketHeight = height * 0.34f
        val leftX = cx - eyeGap / 2f - width * 0.12f
        val rightX = cx + eyeGap / 2f + width * 0.12f
        val span = width * 0.028f
        canvas.drawLine(leftX, bracketY, leftX, bracketY + bracketHeight, focusPaint)
        canvas.drawLine(leftX, bracketY, leftX + span, bracketY, focusPaint)
        canvas.drawLine(leftX, bracketY + bracketHeight, leftX + span, bracketY + bracketHeight, focusPaint)
        canvas.drawLine(rightX, bracketY, rightX, bracketY + bracketHeight, focusPaint)
        canvas.drawLine(rightX - span, bracketY, rightX, bracketY, focusPaint)
        canvas.drawLine(rightX - span, bracketY + bracketHeight, rightX, bracketY + bracketHeight, focusPaint)
    }

    private data class PaletteColors(val primary: Int, val secondary: Int)

    private fun paletteColors(palette: CloudPalette): PaletteColors = when (palette) {
        CloudPalette.WARM -> PaletteColors(
            primary = Color.parseColor("#E9A25B"),
            secondary = Color.parseColor("#FFD6A0"),
        )
        CloudPalette.VIOLET -> PaletteColors(
            primary = Color.parseColor("#8E74E8"),
            secondary = Color.parseColor("#D2C8FF"),
        )
        CloudPalette.TEAL -> PaletteColors(
            primary = Color.parseColor("#53C7B7"),
            secondary = Color.parseColor("#A8F1E8"),
        )
        CloudPalette.DUSK -> PaletteColors(
            primary = Color.parseColor("#7E8BC9"),
            secondary = Color.parseColor("#C8D0FF"),
        )
        CloudPalette.COOL -> PaletteColors(
            primary = Color.parseColor("#5B83A6"),
            secondary = Color.parseColor("#91C4E8"),
        )
    }

    /** Debug-only: the cloud's scene cards. On the wall Mawa is a creature, not
     * a dashboard — this content lives on mawa-brain.vercel.app instead. */
    private fun drawScenePanels(canvas: Canvas) {
        for (panel in scenePanels.take(4)) {
            val left = panel.slot == PanelSlot.TOP_LEFT || panel.slot == PanelSlot.BOTTOM_LEFT
            val top = panel.slot == PanelSlot.TOP_LEFT || panel.slot == PanelSlot.TOP_RIGHT
            val anchorX = if (left) width * 0.045f else width * 0.955f
            val anchorY = if (top) height * 0.07f else height * 0.78f
            val align = if (left) Paint.Align.LEFT else Paint.Align.RIGHT
            panelPaint.textAlign = align
            panelPaint.color = try {
                Color.parseColor(panel.accent)
            } catch (_: IllegalArgumentException) {
                Color.parseColor("#8FA6C0")
            }

            panelPaint.alpha = 155
            panelPaint.textSize = height * 0.024f
            canvas.drawText(panel.eyebrow.uppercase(), anchorX, anchorY, panelPaint)
            panelPaint.alpha = 220
            panelPaint.textSize = height * 0.046f
            canvas.drawText(panel.title, anchorX, anchorY + height * 0.055f, panelPaint)
            panelPaint.alpha = 135
            panelPaint.textSize = height * 0.027f
            canvas.drawText(panel.detail, anchorX, anchorY + height * 0.095f, panelPaint)

            val ruleWidth = width * 0.12f
            val ruleStart = if (left) anchorX else anchorX - ruleWidth
            panelRulePaint.alpha = 120
            canvas.drawLine(
                ruleStart,
                anchorY + height * 0.112f,
                ruleStart + ruleWidth,
                anchorY + height * 0.112f,
                panelRulePaint,
            )
        }
    }

    /**
     * Calibration mode (5-tap to toggle): center crosshair, a red dot showing
     * where the camera currently sees your face (mirrored so it moves the way
     * you do), live numbers, and the one instruction that matters.
     */
    private fun drawCalibrationOverlay(canvas: Canvas) {
        // Center crosshair
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), gridPaint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, gridPaint)

        // Where the camera thinks your face is (mirrored for intuitiveness):
        // walk left, the dot moves left. Goal: dot near the crosshair center
        // when you stand at your usual spot — then long-press.
        rawFace?.let { (rx, ry) ->
            canvas.drawCircle((1f - rx) * width, ry * height, 14f, faceDotPaint)
        }

        canvas.drawText("CALIBRATE: stand at your spot, LONG-PRESS. 5-tap to exit.", 24f, 48f, debugPaint)
        var y = height - 32f
        debugText.split("\n").reversed().forEach { line ->
            canvas.drawText(line, 24f, y, debugPaint)
            y -= 42f
        }
    }

    private fun rebuildParticles(kind: WeatherCondition) {
        flakes.clear()
        val n = when (kind) {
            WeatherCondition.RAIN, WeatherCondition.THUNDER -> 90
            WeatherCondition.SNOW -> 70
            WeatherCondition.FOG -> 12
            else -> 0
        }
        val w = if (width > 0) width.toFloat() else 1920f
        val h = if (height > 0) height.toFloat() else 1080f
        repeat(n) {
            flakes.add(
                Flake(
                    x = Random.nextFloat() * w,
                    y = Random.nextFloat() * h,
                    v = when (kind) {
                        WeatherCondition.RAIN, WeatherCondition.THUNDER -> h * (0.9f + Random.nextFloat() * 0.6f)
                        WeatherCondition.SNOW -> h * (0.06f + Random.nextFloat() * 0.06f)
                        else -> h * 0.02f
                    },
                    drift = (Random.nextFloat() - 0.5f) * w * 0.05f,
                    r = when (kind) {
                        WeatherCondition.SNOW -> 3f + Random.nextFloat() * 4f
                        WeatherCondition.FOG -> w * (0.15f + Random.nextFloat() * 0.2f)
                        else -> 0f
                    },
                )
            )
        }
    }

    private fun updateAndDrawWeather(canvas: Canvas, dt: Float) {
        val kind = weather
        if (kind != flakeKind) {
            rebuildParticles(kind)
            flakeKind = kind
        }
        when (kind) {
            WeatherCondition.RAIN, WeatherCondition.THUNDER -> {
                for (f in flakes) {
                    f.y += f.v * dt
                    f.x += f.drift * dt
                    if (f.y > height) { f.y = -20f; f.x = Random.nextFloat() * width }
                    canvas.drawLine(f.x, f.y, f.x + f.drift * 0.04f, f.y + 22f, rainPaint)
                }
                if (kind == WeatherCondition.THUNDER) {
                    flashClock -= dt
                    if (flashClock <= 0f && Random.nextFloat() < 0.004f) flashClock = 0.12f
                    if (flashClock > 0f) canvas.drawColor(Color.argb(55, 200, 210, 235))
                }
            }
            WeatherCondition.SNOW -> {
                for (f in flakes) {
                    f.y += f.v * dt
                    f.x += f.drift * dt + sin(f.y / 60f) * 6f * dt
                    if (f.y > height) { f.y = -10f; f.x = Random.nextFloat() * width }
                    canvas.drawCircle(f.x, f.y, f.r, snowPaint)
                }
            }
            WeatherCondition.FOG -> {
                for (f in flakes) {
                    f.x += f.drift * dt
                    if (f.x > width + f.r) f.x = -f.r
                    canvas.drawCircle(f.x, f.y, f.r, fogPaint)
                }
            }
            else -> {}
        }
    }

    private fun drawZzz(canvas: Canvas, baseX: Float, baseY: Float) {
        for (i in 0..2) {
            val phase = (zClock * 0.35f + i * 0.33f) % 1f
            val y = baseY - phase * (height * 0.14f)
            val x = baseX + sin(phase * Math.PI).toFloat() * 24f
            zPaint.alpha = ((1f - phase) * 200f).toInt().coerceIn(0, 200)
            zPaint.textSize = 40f + i * 14f
            canvas.drawText("z", x, y, zPaint)
        }
    }
}
