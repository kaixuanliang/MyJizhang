package com.mine.myjizhang.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * 收支统计图（自研每日柱状图：红=支出，绿=收入）
 * 输入：按天索引数组，index 0 = 1 号
 */
class BarChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pay: List<Float> = emptyList()
    private var income: List<Float> = emptyList()

    private val payPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR_RED }
    private val incomePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR_GREEN }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT
        textSize = 10f * resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID_LINE
        strokeWidth = resources.displayMetrics.density
    }

    fun setData(pay: List<Float>, income: List<Float>) {
        this.pay = pay
        this.income = income
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val padLeft = 46f * d
        val padRight = 10f * d
        val padTop = 12f * d
        val padBottom = 22f * d
        val w = width.toFloat()
        val h = height.toFloat()
        val chartW = w - padLeft - padRight
        val chartH = h - padTop - padBottom

        val n = max(pay.size, income.size)
        if (n == 0) {
            axisPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无数据", w / 2, h / 2, axisPaint)
            return
        }

        var hi = max(pay.maxOrNull() ?: 0f, income.maxOrNull() ?: 0f)
        if (hi <= 0f) hi = 1f
        hi *= 1.15f

        fun y(v: Float): Float = padTop + (1 - v / hi) * chartH

        // y 轴 4 条网格线与标签
        axisPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..3) {
            val v = hi * i / 3f
            val yy = y(v)
            canvas.drawLine(padLeft, yy, w - padRight, yy, gridPaint)
            canvas.drawText(compact(v), padLeft - 4f * d, yy + 4f * d, axisPaint)
        }

        // x 轴日期标签
        axisPaint.textAlign = Paint.Align.CENTER
        val step = max(1, ceil(n / 6.0).toInt())
        for (i in 0 until n step step) {
            canvas.drawText((i + 1).toString(), barCenterX(i, n, padLeft, chartW), h - 6f * d, axisPaint)
        }
        if ((n - 1) % step != 0) {
            canvas.drawText(n.toString(), barCenterX(n - 1, n, padLeft, chartW), h - 6f * d, axisPaint)
        }

        // 每天两根柱：红（支出）+ 绿（收入）
        val slot = chartW / n
        val barW = minOf(slot * 0.36f, 14f * d)
        for (i in 0 until n) {
            val cx = padLeft + slot * i + slot / 2
            val p = pay.getOrElse(i) { 0f }
            val inc = income.getOrElse(i) { 0f }
            if (p > 0f) {
                val yy = y(p)
                canvas.drawRect(cx - barW - 1f * d, yy, cx - 1f * d, padTop + chartH, payPaint)
            }
            if (inc > 0f) {
                val yy = y(inc)
                canvas.drawRect(cx + 1f * d, yy, cx + barW + 1f * d, padTop + chartH, incomePaint)
            }
        }
    }

    private fun barCenterX(i: Int, n: Int, padLeft: Float, chartW: Float): Float =
        padLeft + chartW / n * i + chartW / n / 2

    private fun compact(v: Float): String {
        return if (v >= 10000) String.format(Locale.CHINA, "%,.0f", v)
        else if (v >= 1000) String.format(Locale.CHINA, "%,.0f", v)
        else String.format(Locale.CHINA, "%.0f", v)
    }

    companion object {
        val BAR_RED = 0xFFE86A6A.toInt()
        val BAR_GREEN = 0xFF5FBF8A.toInt()
        val AXIS_TEXT = 0xFF999999.toInt()
        val GRID_LINE = 0xFFEFEFEF.toInt()
    }
}
