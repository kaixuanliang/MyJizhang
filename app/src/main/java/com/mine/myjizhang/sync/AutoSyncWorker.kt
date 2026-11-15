package com.mine.myjizhang.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.data.Pref
import com.mine.myjizhang.util.CrashHandler

/**
 * 定时自动同步：把本地全量数据上传到配置好的 GitHub 仓库（只上传，不下载，不会覆盖本地）。
 *
 * 由 [SyncScheduler] 以周期任务方式调度；重启、更新 App 后 WorkManager 会自动恢复。
 * 国产 ROM（EMUI / MIUI 等）会做后台管控，最坏情况下任务会被推迟甚至跳过，
 * 所以设置页里同时给了一个「允许后台运行」入口，引导用户关掉省电限制。
 */
class AutoSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val cfg = Pref.syncConfig(ctx)

        // 用户可能已经把配置删掉 / 关掉了自动同步，任务到期就直接结束
        if (cfg.repo.isBlank() || cfg.token.isBlank()) return Result.success()
        if (cfg.autoSyncDays <= 0) return Result.success()

        if (!isOnline(ctx)) {
            // 没网不算失败，交给 WorkManager 按退避策略重试
            return Result.retry()
        }

        return try {
            val db = AppDatabase.get(ctx)
            when (val r = GitHubSyncer.upload(cfg, db)) {
                is SyncResult.Success -> {
                    // 上传成功：刷新「上次同步」时间，设置页显示为「自动同步」
                    val now = System.currentTimeMillis()
                    Pref.saveSyncConfig(
                        ctx,
                        Pref.syncConfig(ctx).copy(lastSyncTime = now, lastSyncAction = "自动同步")
                    )
                    Pref.setAutoSyncState(ctx, now, "成功：${r.message}")
                    Result.success()
                }
                is SyncResult.Failure -> {
                    Pref.setAutoSyncState(
                        ctx,
                        Pref.autoSyncLastTime(ctx),
                        "失败：${r.message}"
                    )
                    // 网络抖动 / 5xx 可以再试，连续失败 3 次就先放弃，等下一个周期
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            }
        } catch (e: Exception) {
            Pref.setAutoSyncState(ctx, Pref.autoSyncLastTime(ctx), "失败：${e.message ?: "未知错误"}")
            CrashHandler.save(ctx, Thread.currentThread(), e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun isOnline(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (e: Exception) {
        true // 判断不了就当有网，让请求自己去失败

    }
}
