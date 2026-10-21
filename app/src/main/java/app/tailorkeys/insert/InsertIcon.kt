package app.tailorkeys.insert

import android.graphics.*
import android.graphics.drawable.Drawable

/** The same 24-unit, rounded line icons as the approved panel artboards. */
internal class InsertIcon(private val kind: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.75f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val path = Path()
        when (kind) {
            "Lock" -> { canvas.drawRoundRect(5f,10f,19f,21f,2f,2f,paint); canvas.drawArc(8f,2f,16f,15f,180f,180f,false,paint) }
            "Pin" -> { path.moveTo(8f,3f); path.lineTo(16f,3f); path.lineTo(15f,10f); path.lineTo(18f,14f); path.lineTo(6f,14f); path.lineTo(9f,10f); path.close(); paint.style=Paint.Style.FILL; canvas.drawPath(path,paint); paint.style=Paint.Style.STROKE; canvas.drawLine(12f,14f,12f,22f,paint) }
            "Emoji" -> { canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawPoint(9f, 10f, paint); canvas.drawPoint(15f, 10f, paint); canvas.drawArc(8f, 10f, 16f, 16f, 25f, 130f, false, paint) }
            "Clipboard" -> { canvas.drawRoundRect(5f, 4.5f, 19f, 21f, 2.5f, 2.5f, paint); canvas.drawRoundRect(9f, 3f, 15f, 6f, 1f, 1f, paint); canvas.drawLine(9f, 11f, 15f, 11f, paint); canvas.drawLine(9f, 15f, 13f, 15f, paint) }
            "Voice" -> { canvas.drawRoundRect(9f, 3f, 15f, 14f, 3f, 3f, paint); canvas.drawArc(5.5f, 4.5f, 18.5f, 17.5f, 0f, 180f, false, paint); canvas.drawLine(12f, 17.5f, 12f, 21f, paint); canvas.drawLine(9f, 21f, 15f, 21f, paint) }
            "Search" -> { canvas.drawCircle(10.5f, 10.5f, 6.5f, paint); canvas.drawLine(15f, 15f, 21f, 21f, paint) }
            "Recent" -> { canvas.drawCircle(12f, 12f, 8.5f, paint); canvas.drawLine(12f, 7.5f, 12f, 12f, paint); canvas.drawLine(12f, 12f, 15f, 14f, paint) }
            "People" -> { canvas.drawCircle(12f, 7f, 3.5f, paint); canvas.drawArc(5f, 12f, 19f, 25f, 180f, 180f, false, paint) }
            "Animals" -> { path.moveTo(5f, 9f); path.lineTo(4f, 3f); path.lineTo(9f, 6f); path.lineTo(15f, 6f); path.lineTo(20f, 3f); path.lineTo(19f, 9f); path.cubicTo(23f, 23f, 1f, 23f, 5f, 9f); canvas.drawPath(path, paint); canvas.drawPoint(9f, 12f, paint); canvas.drawPoint(15f, 12f, paint); canvas.drawLine(11f, 16f, 13f, 16f, paint) }
            "Food" -> { canvas.drawRoundRect(4f, 8f, 16f, 19f, 2f, 2f, paint); canvas.drawArc(14f, 9f, 21f, 16f, -90f, 180f, false, paint); canvas.drawLine(7f, 3f, 7f, 5f, paint); canvas.drawLine(12f, 3f, 12f, 5f, paint) }
            "Travel" -> { canvas.drawRoundRect(3f, 10f, 21f, 18f, 2f, 2f, paint); path.moveTo(5f, 10f); path.lineTo(7f, 5f); path.lineTo(17f, 5f); path.lineTo(19f, 10f); canvas.drawPath(path, paint); canvas.drawLine(6f, 18f, 6f, 21f, paint); canvas.drawLine(18f, 18f, 18f, 21f, paint) }
            "Activities" -> { canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawLine(3f, 12f, 21f, 12f, paint); canvas.drawLine(12f, 3f, 12f, 21f, paint); canvas.drawOval(8f, 3f, 16f, 21f, paint) }
            "Objects" -> { canvas.drawCircle(12f, 9f, 6f, paint); canvas.drawLine(9f, 15f, 9f, 19f, paint); canvas.drawLine(15f, 15f, 15f, 19f, paint); canvas.drawLine(9f, 19f, 15f, 19f, paint); canvas.drawLine(10f, 22f, 14f, 22f, paint) }
            "Symbols" -> { path.moveTo(12f, 20f); path.cubicTo(-5f, 9f, 8f, -2f, 12f, 7f); path.cubicTo(16f, -2f, 29f, 9f, 12f, 20f); canvas.drawPath(path, paint) }
            "Flags" -> { canvas.drawLine(5f, 3f, 5f, 22f, paint); path.moveTo(5f, 4f); path.lineTo(20f, 4f); path.lineTo(17f, 9f); path.lineTo(20f, 14f); path.lineTo(5f, 14f); canvas.drawPath(path, paint) }
            "Backspace" -> { path.moveTo(21f, 5f); path.lineTo(9f, 5f); path.lineTo(3f, 12f); path.lineTo(9f, 19f); path.lineTo(21f, 19f); path.close(); canvas.drawPath(path, paint); canvas.drawLine(12f, 9.5f, 17f, 14.5f, paint); canvas.drawLine(17f, 9.5f, 12f, 14.5f, paint) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
