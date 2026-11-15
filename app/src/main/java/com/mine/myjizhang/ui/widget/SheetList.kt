package com.mine.myjizhang.ui.widget

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mine.myjizhang.R

/**
 * 底部选择弹窗：标题固定悬浮在顶部，下面的列表在固定高度内滚动。
 *
 * 之前的实现是把标题塞进 ScrollView 一起滚，列表一长标题就跟着滚走；
 * 而且高度完全由内容决定，弹窗会被撑到近乎全屏。这里统一处理：
 * - 标题 + 分隔线在 ScrollView 之外 → 永远不动
 * - 列表高度 = min(内容高度, 屏幕高度 × 0.58) → 条目少时弹窗矮，条目多时固定高度并内部滚动
 */
object SheetList {

    class Sheet(val dialog: BottomSheetDialog, val list: LinearLayout)

    /** 列表最大高度占屏幕的比例 */
    private const val MAX_HEIGHT_RATIO = 0.58f

    fun create(
        context: Context,
        title: String,
        itemCount: Int = 0,
        itemHeightDp: Int = 56,
        fillHeight: Boolean = false
    ): Sheet {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 悬浮标题
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(12))
        }
        header.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTextColor(context.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(header)

        root.addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(16))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        val maxH = (context.resources.displayMetrics.heightPixels * MAX_HEIGHT_RATIO).toInt()
        val contentH = dp(6) + dp(16) + itemCount.coerceAtLeast(1) * dp(itemHeightDp)
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (fillHeight) maxH else contentH.coerceAtMost(maxH)
        )
        root.addView(scroll)

        dialog.setContentView(root)

        // 关键：BottomSheetBehavior 默认会把手势吃掉——列表滚到一半时，
        // 上滑被识别成「拖动弹窗」，可弹窗已是展开态无法再往上，手势就此死掉，
        // 看起来就是「滑一段卡住、整个页面也动不了」。这里直接禁掉拖拽，
        // 让滚动手势全部交给内层 ScrollView。
        dialog.setOnShowListener {
            val sheetView = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            sheetView.layoutParams = sheetView.layoutParams.apply {
                height = LinearLayout.LayoutParams.WRAP_CONTENT
            }
            com.google.android.material.bottomsheet.BottomSheetBehavior
                .from(sheetView).apply {
                    isDraggable = false
                    skipCollapsed = true
                    state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                }
        }
        return Sheet(dialog, list)
    }

    /** 一行：可选图标 + 标题，固定行高（行高要和 create 传入的一致，否则高度算不准） */
    fun row(
        context: Context,
        title: String,
        heightDp: Int = 56,
        icon: View? = null,
        subtitle: String? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
            )
            setBackgroundResource(tv.resourceId)
            isClickable = true
            setOnClickListener { onClick() }
            if (icon != null) addView(icon)

            // 注意：局部变量不要叫 text，否则会遮蔽 TextView.text
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(if (icon != null) dp(14) else 0, 0, 0, 0)
            }
            textCol.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(context.getColor(R.color.text_primary))
                maxLines = 1
            })
            if (!subtitle.isNullOrBlank()) {
                textCol.addView(TextView(context).apply {
                    text = subtitle
                    textSize = 12f
                    setTextColor(context.getColor(R.color.text_secondary))
                    maxLines = 1
                })
            }
            addView(textCol)
        }
    }

    /** 32dp 的图标位，交给调用方用 AssetTypes.bindIcon 填充 */
    fun iconView(context: Context, sizeDp: Int = 32): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            (sizeDp * context.resources.displayMetrics.density).toInt(),
            (sizeDp * context.resources.displayMetrics.density).toInt()
        )
        includeFontPadding = false
    }
}
