package com.mine.myjizhang.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 资产走势图（自研折线 + 渐变面积图，参考一飞记账样式）
 * 输入：按天索引的累计结余数组，index 0 = 1 号
 */
class LineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Float> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHART_BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AXIS_TEXT
        textSize = 10f * resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GRID_LINE
        strokeWidth = resources.displayMetrics.density
    }

    fun setData(values: List<Float>) {
        this.values = values
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

        if (values.isEmpty()) {
            axisPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("暂无数据", w / 2, h / 2, axisPaint)
            return
        }

        var lo = values.min()
        var hi = values.max()
        if (hi - lo < 0.01f) { lo -= 1f; hi += 1f }
        val range = hi - lo
        lo -= range * 0.15f
        hi += range * 0.15f

        fun y(v: Float): Float = padTop + (hi - v) / (hi - lo) * chartH
        fun x(i: Int): Float = if (values.size <= 1) padLeft + chartW / 2
        else padLeft + i.toFloat() / (values.size - 1) * chartW

        // y 轴 5 条网格线与标签
        axisPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val v = lo + (hi - lo) * i / 4f
            val yy = y(v)
            canvas.drawLine(padLeft, yy, w - padRight, yy, gridPaint)
            canvas.drawText(compact(v), padLeft - 4f * d, yy + 4f * d, axisPaint)
        }

        // x 轴日期标签
        axisPaint.textAlign = Paint.Align.CENTER
        val n = values.size
        val step = max(1, ceil(n / 6.0).toInt())
        for (i in 0 until n step step) {
            canvas.drawText((i + 1).toString(), x(i), h - 6f * d, axisPaint)
        }
        if ((n - 1) % step != 0) canvas.drawText(n.toString(), x(n - 1), h - 6f * d, axisPaint)

        // 折线
        val path = Path()
        values.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
        }
        // 渐变填充
        val fill = Path(path)
        fill.lineTo(x(values.size - 1), padTop + chartH)
        fill.lineTo(x(0), padTop + chartH)
        fill.close()
        fillPaint.shader = LinearGradient(
            0f, padTop, 0f, padTop + chartH,
            CHART_BLUE_FILL, CHART_BLUE_FILL_TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
    }

    private fun compact(v: Float): String {
        val abs = Math.abs(v)
        return if (abs >= 100000) String.format(Locale.CHINA, "%.0fk", v / 1000)
        else if (abs >= 10000) String.format(Locale.CHINA, "%,.0f", v)
        else if (abs == floor(abs)) String.format(Locale.CHINA, "%,.0f", v)
        else String.format(Locale.CHINA, "%,.1f", v)
    }

    companion object {
        val CHART_BLUE = 0xFF3D9BE9.toInt()
        val CHART_BLUE_FILL = 0x663D9BE9.toInt()
        val CHART_BLUE_FILL_TRANSPARENT = 0x003D9BE9.toInt()
        val AXIS_TEXT = 0xFF999999.toInt()
        val GRID_LINE = 0xFFEFEFEF.toInt()
    }
}
