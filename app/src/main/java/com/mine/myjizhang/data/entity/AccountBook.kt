package com.mine.myjizhang.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 账本 */
@Entity(tableName = "AccountBook")
data class AccountBook(
    @PrimaryKey var id: String,
    var name: String,
    var positionWeight: Int = 0,
    var createTime: Long = 0L
)
