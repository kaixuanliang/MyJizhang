package com.mine.myjizhang.ui

/**
 * 常驻 Tab 页的刷新入口。
 *
 * Fragment 被 hide 之后仍然处于 RESUMED，切回来不会回调 onResume，
 * 由 MainActivity 在切页 / 返回前台 / 收到 DataBus 信号时主动调用。
 */
interface TabRefreshable {
    fun refreshTab()
}
