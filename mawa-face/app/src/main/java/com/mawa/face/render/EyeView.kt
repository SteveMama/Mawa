package com.mawa.face.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

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

    private var lastFrameNs = 0L

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
        color = Color.parseColor("#446644")
        textSize = 28f
    }
    private val eyePath = Path()

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0.016f else min(0.05f, (now - lastFrameNs) / 1e9f)
        lastFrameNs = now

        engine.update(dt)

        canvas.drawColor(Color.BLACK)

        val cx = width / 2f + engine.driftX
        val cy = height / 2f + engine.driftY
        val eyeGap = width * 0.28f

        drawEye(canvas, cx - eyeGap / 2f, cy, engine.left)
        drawEye(canvas, cx + eyeGap / 2f, cy, engine.right)

        if (debug) {
            canvas.drawText(debugText, 24f, height - 32f, debugPaint)
        }

        postInvalidateOnAnimation()
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
