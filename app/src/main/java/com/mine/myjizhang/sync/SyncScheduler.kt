package com.mine.myjizhang.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mine.myjizhang.data.Pref
import java.util.concurrent.TimeUnit

/**
 * 自动同步调度：把「间隔天数」翻译成 WorkManager 周期任务。
 *
 * - 间隔 0 / 未配置仓库 → 取消任务
 * - 间隔 1 / 7 / 30 天 → 注册唯一周期任务（同名任务只保留一个）
 * - 只在有网时执行；失败按指数退避重试（30 分钟起）
 *
 * 注意：WorkManager 的周期任务没有「精确到几点」的概念，
 * 系统会把执行时间对齐到省电批次，1 天间隔实际可能是 24~30 小时一次。
 */
object SyncScheduler {

    private const val WORK_NAME = "github_auto_sync"

    /** 允许的最短间隔：WorkManager 硬性下限是 15 分钟，这里取 1 天 */
    private val ALLOWED = setOf(1, 7, 30)

    /**
     * @param replace true = 配置变更时重建任务（会重新计时）；
     *                false = App 启动时兜底，已有任务保持不动。
     */
    fun apply(context: Context, replace: Boolean = true) {
        val wm = WorkManager.getInstance(context)
        val cfg = Pref.syncConfig(context)
        val days = cfg.autoSyncDays

        if (days <= 0 || cfg.repo.isBlank() || cfg.token.isBlank()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }

        val intervalDays = if (days in ALLOWED) days else 1
        val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(
            intervalDays.toLong(), TimeUnit.DAYS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()

        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** 关闭自动同步（清空配置 / 用户选「关闭」时） */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
