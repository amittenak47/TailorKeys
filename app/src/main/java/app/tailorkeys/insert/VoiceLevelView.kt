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
        val step = minOf(10 * density, width / 24f)
        for (i in 0 until 24) {
            val wave = (.25f + .75f * kotlin.math.abs(sin(i * 1.7f))) * amplitude
            val half = 2 * density + (height / 2f - 4 * density).coerceAtLeast(0f) * wave
            val x = width / 2f + (i - 11.5f) * step
            paint.alpha = if (amplitude > 0) 230 else 90
            canvas.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
    }
}

/** RMS-driven rings have no idle timer; reduced motion draws one steady ring. */
internal class VoiceStopView(context: Context) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var level=0f; private var color=0; private var ink=0; private var running=false
    fun update(rms: Float, color: Int, ink: Int, running: Boolean) { level=(rms/10).coerceIn(0f,1f); this.color=color; this.ink=ink; this.running=running; invalidate() }
    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
    override fun onDraw(canvas: Canvas) {
        val d=resources.displayMetrics.density; val x=width/2f; val y=height/2f
        paint.color=color
        if (running) {
            val animate=ValueAnimator.areAnimatorsEnabled()
            paint.alpha=36; canvas.drawCircle(x,y,(if (animate) 38+8*level else 39f)*d,paint)
            if (animate) { paint.alpha=24; canvas.drawCircle(x,y,(42+5*level)*d,paint) }
        }
        paint.alpha=255; canvas.drawCircle(x,y,32*d,paint); paint.color=ink
        if (running) canvas.drawRoundRect(x-9*d,y-9*d,x+9*d,y+9*d,3*d,3*d,paint)
        else { paint.textSize=15*resources.displayMetrics.scaledDensity; paint.textAlign=Paint.Align.CENTER; canvas.drawText("Start",x,y-(paint.ascent()+paint.descent())/2,paint) }
    }
}
