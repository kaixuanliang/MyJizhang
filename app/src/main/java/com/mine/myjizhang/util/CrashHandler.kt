package com.mine.myjizhang.util

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃兜底。
 *
 * 闪退最麻烦的不是修，而是**看不到栈**。这里把未捕获异常连同设备信息写到
 * filesDir/crash/last_crash.txt，下次启动由 MainActivity 弹窗展示，可一键复制。
 *
 * 特意不依赖 BuildConfig（AGP 8 默认关闭），版本号走 PackageManager；
 * 数据库版本直接读 PRAGMA user_version，绕开 Room，避免崩溃时二次触发初始化。
 */
object CrashHandler {

    private const val DIR = "crash"
    private const val NAME = "last_crash.txt"

    fun file(ctx: Context): File =
        File(File(ctx.filesDir, DIR).apply { mkdirs() }, NAME)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun save(ctx: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val header = buildString {
            append("时间：").append(time).append('\n')
            append("版本：").append(versionName(ctx)).append('\n')
            append("设备：").append(Build.BRAND).append(' ').append(Build.MODEL)
            append(" / Android ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("线程：").append(thread.name).append('\n')
            append("数据库：").append(dbVersion(ctx)).append('\n')
            append("————————————\n")
        }
        runCatching { file(ctx).writeText(header + sw.toString()) }
    }

    fun read(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    fun copy(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.setPrimaryClip(ClipData.newPlainText("记账本错误信息", text)) }
    }

    private fun versionName(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, 0)
        }
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("?")

    private fun dbVersion(ctx: Context): String = runCatching {
        val f = ctx.getDatabasePath("jizhang.db")
        if (!f.exists()) {
            "未创建"
        } else {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) "v${c.getInt(0)}" else "?"
                }
            }
        }
    }.getOrDefault("?")
}
