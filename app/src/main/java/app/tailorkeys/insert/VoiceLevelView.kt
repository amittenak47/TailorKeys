package app.tailorkeys.insert

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.sin

/** Level bars follow recognizer callbacks, with no idle animation or background draw loop. */
internal class VoiceLevelView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var amplitude = 0f
    fun update(rms: Float, color: Int, listening: Boolean) {
        paint.color = color
        amplitude = if (!listening) 0f else if (ValueAnimator.areAnimatorsEnabled()) (rms / 10f).coerceIn(0f, 1f) else .4f
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        paint.strokeWidth = 4 * density
        val step = minOf(10 * density, width / 23f)
        for (i in -10..10) {
            val wave = (.25f + .75f * kotlin.math.abs(sin(i * 1.7f))) * amplitude
            val half = 2 * density + (height / 2f - 4 * density).coerceAtLeast(0f) * wave
            val x = width / 2f + i * step
            paint.alpha = if (amplitude > 0) 230 else 90
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
    }
}
