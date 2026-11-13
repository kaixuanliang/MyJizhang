package com.mine.myjizhang.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类月度预算。categoryId 为空串时表示账本总预算。
 * month 为空串时表示「每月循环」，否则形如 2026-11。
 */
@Entity(tableName = "Budget", indices = [Index("bookId")])
data class Budget(
    @PrimaryKey var id: String,
    var bookId: String,
    var categoryId: String = "",
    var month: String = "",
    var amount: Double = 0.0,
    var createTime: Long = 0L
)
