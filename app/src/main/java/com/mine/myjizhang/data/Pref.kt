package com.mine.myjizhang.data

import android.content.Context
import com.mine.myjizhang.sync.SyncConfig

/** 轻量偏好存储 */
object Pref {
    private const val NAME = "jizhang_pref"
    private const val KEY_BOOK = "current_book_id"
    private const val KEY_SYNC = "sync_config_json"
    private const val KEY_HIDE_AMOUNT = "hide_amounts"
    private const val KEY_LOAN_ALGO = "loan_algo_version"

    /** 借贷冲销算法版本：1 = 允许跨人冲销（有缺陷），2 = 只冲同人 */
    fun loanAlgoVersion(context: Context): Int =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LOAN_ALGO, 1)

    fun setLoanAlgoVersion(context: Context, value: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LOAN_ALGO, value).apply()
    }

    fun hideAmounts(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_AMOUNT, false)

    fun setHideAmounts(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HIDE_AMOUNT, value).apply()
    }

    fun currentBookId(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_BOOK, "") ?: ""

    fun setCurrentBookId(context: Context, id: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BOOK, id).apply()
    }

    fun syncConfig(context: Context): SyncConfig {
        val raw = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString(KEY_SYNC, null) ?: return SyncConfig()
        return try {
            val o = org.json.JSONObject(raw)
            SyncConfig(
                repo = o.optString("repo"),
                branch = o.optString("branch", "main").ifBlank { "main" },
                token = o.optString("token"),
                password = o.optString("password"),
                path = o.optString("path", "jizhang-backup.bin").ifBlank { "jizhang-backup.bin" },
                lastSyncTime = o.optLong("lastSyncTime"),
                lastSyncAction = o.optString("lastSyncAction")
            )
        } catch (e: Exception) {
            SyncConfig()
        }
    }

    fun saveSyncConfig(context: Context, cfg: SyncConfig) {
        val o = org.json.JSONObject()
            .put("repo", cfg.repo)
            .put("branch", cfg.branch)
            .put("token", cfg.token)
            .put("password", cfg.password)
            .put("path", cfg.path)
            .put("lastSyncTime", cfg.lastSyncTime)
            .put("lastSyncAction", cfg.lastSyncAction)
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SYNC, o.toString()).apply()
    }
}
