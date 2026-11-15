package com.mine.myjizhang.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import com.mine.myjizhang.R
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 分类占比环形图（自绘，无第三方依赖）。
 *
 * 只靠颜色区分占比不够用——哪一块是哪个分类，还得回头看下面的列表。
 * 这里给每一块拉一条引线到圆外，直接标出「分类 / 占比 / 金额」，一眼能对上。
 * 标签左右分列，按角度落位后再做一次防重叠下推，块再小也不会叠在一起。
 */
class PieChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Slice(val label: String, val value: Double, val color: Int)

    private class Arc(val slice: Slice, val start: Float, val sweep: Float) {
        val mid: Float get() = start + sweep / 2f
    }

    private class Label(
        val arc: Arc, val side: Int, val cos: Float, val sin: Float, var y: Float
    )

    private var slices: List<Slice> = emptyList()
    private var totalText: String = ""

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun c(id: Int) = context.getColor(id)

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        color = c(R.color.text_primary)
        isFakeBoldText = true
    }
    private val pctPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = c(R.color.text_secondary)
    }
    private val amountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = c(R.color.text_secondary)
        isFakeBoldText = true
    }
    private val centerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
        color = c(R.color.text_primary)
        isFakeBoldText = true
    }
    private val centerSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(9f)
        color = c(R.color.text_secondary)
    }

    fun setData(list: List<Slice>, totalText: String) {
        slices = list.filter { it.value > 0 }
        this.totalText = totalText
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availW = (width - paddingLeft - paddingRight).toFloat()
        val availH = (height - paddingTop - paddingBottom).toFloat()
        if (availW <= 0f || availH <= 0f) return
        val cx = paddingLeft + availW / 2f
        val cy = paddingTop + availH / 2f

        // 半径要给两侧标签留位置，否则标签会顶出屏幕
        val outerR = min(availW * 0.245f, availH * 0.40f)
        val band = outerR * 0.42f
        val arcR = outerR - band / 2f
        val rect = RectF(cx - arcR, cy - arcR, cx + arcR, cy + arcR)

        val total = slices.sumOf { it.value }
        if (total <= 0.0) {
            arcPaint.color = c(R.color.divider)
            arcPaint.strokeWidth = band
            canvas.drawArc(rect, 0f, 360f, false, arcPaint)
            canvas.drawText("暂无数据", cx, cy + dp(4f), centerSubPaint)
            return
        }

        // ---- 1. 环形切片 ----
        // 0.5% 的占比只有 1.8°、0.03% 不到 0.1°，直接按真实角度画等于没画。
        // 给过小的块补到「最小可见角度」，补掉的度数从占比大的块按比例扣回来，总和仍是 360°。
        val raw = slices.map { (it.value / total * 360.0).toFloat() }.toFloatArray()
        val sweepOf = FloatArray(raw.size) { raw[it] }
        val minSweep = 3f
        var deficit = 0f
        for (i in sweepOf.indices) {
            if (sweepOf[i] < minSweep) {
                deficit += minSweep - sweepOf[i]
                sweepOf[i] = minSweep
            }
        }
        if (deficit > 0f) {
            val donors = raw.indices.filter { raw[it] > 10f }
            val donorSum = donors.sumOf { raw[it].toDouble() }.toFloat()
            if (donorSum > deficit) {
                for (i in donors) sweepOf[i] = raw[i] - raw[i] / donorSum * deficit
            }
        }
        val sweepSum = sweepOf.sum()
        if (sweepSum > 0f) {
            val k = 360f / sweepSum
            for (i in sweepOf.indices) sweepOf[i] *= k
        }

        var start = -90f
        val arcs = ArrayList<Arc>(slices.size)
        for ((i, s) in slices.withIndex()) {
            arcs += Arc(s, start, sweepOf[i])
            start += sweepOf[i]
        }
        arcPaint.strokeWidth = band
        for (a in arcs) {
            // 缝隙只留给正常的块：细条再挖掉 1.8° 就真的看不见了
            val g = if (a.sweep > 6f && arcs.size > 1) 1.8f else 0f
            val sw = a.sweep - g
            if (sw <= 0f) continue
            arcPaint.color = a.slice.color
            canvas.drawArc(rect, a.start + g / 2f, sw, false, arcPaint)
        }

        // ---- 2. 中心合计（金额长时自动缩小，别冲出内圈）----
        val innerW = (arcR - band / 2f) * 2f * 0.84f
        centerPaint.textSize = dp(15f)
        while (centerPaint.measureText(totalText) > innerW && centerPaint.textSize > dp(9f)) {
            centerPaint.textSize -= dp(0.5f)
        }
        canvas.drawText("合计", cx, cy - dp(3f), centerSubPaint)
        canvas.drawText(totalText, cx, cy + dp(13f), centerPaint)

        // ---- 3. 外侧引线 + 标签 ----
        // 标签统一挂在左右两条「纵轨」上（离圆心固定距离），而不是跟着角度落到
        // 圆环正上方 —— 否则靠顶部的标签会直接压在色块上。
        val anchorR = outerR + dp(1f)
        val stubR = outerR + dp(9f)
        val railR = outerR + dp(14f)
        val minStep = dp(34f)
        // 标签是两行（分类+占比 / 金额），上下各留出半行，否则第一行会被顶边裁掉
        val topLimit = paddingTop + dp(13f)
        val bottomLimit = paddingTop + availH - dp(18f)

        val labels = ArrayList<Label>(arcs.size)
        for (a in arcs) {
            // 每一块都要标：小占比也是钱，藏起来反而让人以为漏统计了
            val rad = Math.toRadians(a.mid.toDouble())
            val cosv = cos(rad).toFloat()
            val sinv = sin(rad).toFloat()
            labels += Label(a, if (cosv >= 0f) 1 else -1, cosv, sinv, cy + sinv * anchorR)
        }

        for (side in intArrayOf(1, -1)) {
            val group = labels.filter { it.side == side }.sortedBy { it.y }
            if (group.isEmpty()) continue
            // 先按角度落位，自上而下推开，保证相邻标签至少隔 minStep
            val step = if (group.size == 1) 0f
            else min(minStep, (bottomLimit - topLimit) / (group.size - 1))
            var prev = Float.NEGATIVE_INFINITY
            for (l in group) {
                l.y = max(l.y, prev + step)
                prev = l.y
            }
            val over = group.last().y - bottomLimit
            if (over > 0f) group.forEach { it.y -= over }
            val under = topLimit - group.first().y
            if (under > 0f) group.forEach { it.y += under }

            val railX = cx + side * railR
            val textX = railX + side * dp(5f)
            for (l in group) {
                val ax = cx + l.cos * anchorR
                val ay = cy + l.sin * anchorR
                val sx = cx + l.cos * stubR
                val sy = cy + l.sin * stubR
                linePaint.color = l.arc.slice.color
                // 锚点 → 环外一点 → 拐到纵轨 → 短横线
                canvas.drawLine(ax, ay, sx, sy, linePaint)
                canvas.drawLine(sx, sy, railX - side * dp(7f), l.y, linePaint)
                canvas.drawLine(railX - side * dp(7f), l.y, railX, l.y, linePaint)
                dotPaint.color = l.arc.slice.color
                canvas.drawCircle(ax, ay, dp(1.6f), dotPaint)

                val maxW = if (side > 0) width - paddingRight - dp(2f) - textX
                else textX - paddingLeft - dp(2f)
                // 分类名单独一行，占比和金额放第二行：名字长的（其他 N 项）也不会被截成省略号
                val nameStr = TextUtils.ellipsize(
                    l.arc.slice.label, namePaint, maxW, TextUtils.TruncateAt.END
                ).toString()
                val pctStr = fmtPct(l.arc.slice.value, total)
                val amtStr = fmtAmount(l.arc.slice.value)

                if (side > 0) {
                    namePaint.textAlign = Paint.Align.LEFT
                    pctPaint.textAlign = Paint.Align.LEFT
                    amountPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(nameStr, textX, l.y - dp(2f), namePaint)
                    canvas.drawText(pctStr, textX, l.y + dp(12f), pctPaint)
                    canvas.drawText(
                        amtStr,
                        textX + pctPaint.measureText(pctStr) + dp(6f),
                        l.y + dp(12f),
                        amountPaint
                    )
                } else {
                    namePaint.textAlign = Paint.Align.RIGHT
                    pctPaint.textAlign = Paint.Align.RIGHT
                    amountPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(nameStr, textX, l.y - dp(2f), namePaint)
                    canvas.drawText(amtStr, textX, l.y + dp(12f), amountPaint)
                    canvas.drawText(
                        pctStr,
                        textX - amountPaint.measureText(amtStr) - dp(6f),
                        l.y + dp(12f),
                        pctPaint
                    )
                }
            }
        }
    }

    private fun fmtPct(v: Double, total: Double): String {
        val p = v / total * 100
        // 0.03% 显示成「0.0%」会让人以为是没算进去，改写成「<0.1%」
        return if (p < 0.1) "<0.1%" else String.format(Locale.CHINA, "%.1f%%", p)
    }

    private fun fmtAmount(v: Double): String = when {
        v >= 100000 -> String.format(Locale.CHINA, "%.0f万", v / 10000)
        v >= 10000 -> String.format(Locale.CHINA, "%.2f万", v / 10000)
        v >= 100 -> "¥" + String.format(Locale.CHINA, "%,.0f", v)
        else -> "¥" + String.format(Locale.CHINA, "%.2f", v)
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
