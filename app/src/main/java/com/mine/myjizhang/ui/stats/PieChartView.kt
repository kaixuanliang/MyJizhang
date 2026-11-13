package com.mine.myjizhang.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 简单饼图控件（自绘，无第三方依赖）。
 */
class PieChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val label: String, val value: Double, val color: Int)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var slices: List<Slice> = emptyList()
    private var centerText: String = ""

    fun setData(list: List<Slice>, totalText: String) {
        slices = list
        centerText = totalText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slices.isEmpty()) return
        val total = slices.sumOf { it.value }
        if (total <= 0) return

        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        val diameter = minOf(w, h) * 0.82f
        val left = paddingLeft + (w - diameter) / 2
        val top = paddingTop + (h - diameter) / 2
        val rect = RectF(left, top, left + diameter, top + diameter)

        var startAngle = -90f
        for (s in slices) {
            val sweep = (s.value / total * 360).toFloat()
            paint.color = s.color
            canvas.drawArc(rect, startAngle, sweep, true, paint)
            startAngle += sweep
        }
        // 中心留白圆环效果
        paint.color = Color.WHITE
        val inner = diameter * 0.42f
        canvas.drawCircle(rect.centerX(), rect.centerY(), inner / 2, paint)
        textPaint.color = context.getColor(android.R.color.black)
        textPaint.textSize = diameter * 0.09f
        canvas.drawText(centerText, rect.centerX(), rect.centerY() + diameter * 0.03f, textPaint)
    }

    companion object {
        val PALETTE = intArrayOf(
            Color.parseColor("#534AB7"),
            Color.parseColor("#1D9E75"),
            Color.parseColor("#D85A30"),
            Color.parseColor("#378ADD"),
            Color.parseColor("#BA7517"),
            Color.parseColor("#D4537E"),
            Color.parseColor("#639922"),
            Color.parseColor("#888780")
        )
    }
}
