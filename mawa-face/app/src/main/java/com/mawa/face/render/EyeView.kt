package com.mawa.face.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * Fullscreen eye renderer. Pure black background (OLED pixels off), two
 * capsule-shaped eyes, drawn at display refresh rate via
 * postInvalidateOnAnimation. All motion comes from [engine].
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
    var scenePanels: List<ScenePanel> = emptyList()

    private var lastFrameNs = 0L
    private var zClock = 0f
    private var flashClock = 0f

    private val scleraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5F2EA")   // warm off-white
        style = Paint.Style.FILL
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0B0B0B")
        style = Paint.Style.FILL
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
        val eyeGap = width * 0.28f

        drawEye(canvas, cx - eyeGap / 2f, cy, engine.left)
        drawEye(canvas, cx + eyeGap / 2f, cy, engine.right)

        updateAndDrawWeather(canvas, dt)
        drawScenePanels(canvas)

        if (engine.sleeping) {
            zClock += dt
            drawZzz(canvas, cx + eyeGap / 2f + width * 0.06f, cy - height * 0.16f)
        }

        if (debug) drawCalibrationOverlay(canvas)

        postInvalidateOnAnimation()
    }

    /** Render at most four short cloud-composed cards without crowding the face. */
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

    private fun drawEye(canvas: Canvas, ex: Float, ey: Float, p: EyeParams) {
        val eyeW = width * 0.135f
        val eyeH = eyeW * 1.45f * p.squash

        val bounds = RectF(ex - eyeW / 2, ey - eyeH / 2, ex + eyeW / 2, ey + eyeH / 2)
        eyePath.reset()
        eyePath.addRoundRect(bounds, eyeW / 2, eyeW / 2, Path.Direction.CW)

        // Sclera
        canvas.drawPath(eyePath, scleraPaint)

        // Pupil + lids clipped to the sclera shape
        canvas.save()
        canvas.clipPath(eyePath)

        val pr = eyeW * 0.30f * p.pupilScale
        val px = ex + p.pupilX * eyeW * 0.32f
        val py = ey + p.pupilY * eyeH * 0.30f
        canvas.drawCircle(px, py, pr, pupilPaint)

        // Upper lid: covers (1-openness) mostly from the top, tilted by lidAngle
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

            // Lower lid rises a little as the eye closes
            val bottomLid = closed * eyeH * 0.28f
            canvas.drawRect(
                bounds.left - eyeW, bounds.bottom - bottomLid,
                bounds.right + eyeW, bounds.bottom + eyeH, lidPaint
            )
        }
        canvas.restore()
    }
}
