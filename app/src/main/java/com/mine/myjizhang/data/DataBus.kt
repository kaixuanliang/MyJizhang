package com.mine.myjizhang.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局数据变更信号。
 *
 * MainActivity 用 hide/show 管理 5 个常驻 Fragment，切 Tab 时不会走 onResume，
 * 所以「导入账单 / 切换账本 / 清空账本 / 删除账本 / 从备份恢复」之后
 * 必须显式通知页面重新查库，否则页面还拿着旧的内存数据（典型表现：
 * 刚导入的账单账户名显示「已删除账户」，退出重进才正常）。
 */
object DataBus {

    private val _version = MutableStateFlow(0L)

    /** 每变更一次自增；页面收集它即可感知需要刷新 */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun bump() {
        _version.value = _version.value + 1
    }
}
