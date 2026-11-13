package com.mine.myjizhang.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 分类 type: 0=支出 1=收入 */
@Entity(tableName = "Category")
data class Category(
    @PrimaryKey var id: String,
    var name: String,
    var type: Int,
    var icon: String = "💡",
    var positionWeight: Int = 0,
    var hide: Boolean = false,
    /** 上级分类 id（空串 = 一级分类），支持「食 → 正餐 / 外卖 / 咖啡」两级结构 */
    var parentId: String = "",
    /** 支出性质：0 未设定 1 固定支出 2 弹性支出 3 不计入收支 */
    var nature: Int = 0
) {
    companion object {
        const val NATURE_NONE = 0
        const val NATURE_FIXED = 1
        const val NATURE_FLEX = 2
        const val NATURE_EXCLUDED = 3

        fun natureName(n: Int): String = when (n) {
            NATURE_FIXED -> "固定支出"
            NATURE_FLEX -> "弹性支出"
            NATURE_EXCLUDED -> "不计收支"
            else -> "未设定"
        }
    }
}
