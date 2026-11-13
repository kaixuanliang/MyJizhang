package com.mine.myjizhang

import android.app.Application
import com.mine.myjizhang.data.AppDatabase
import com.mine.myjizhang.util.CrashHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 必须在任何数据库操作之前装好，否则启动期异常拿不到栈
        CrashHandler.install(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { AppDatabase.seed(this@App) }
                .onFailure { CrashHandler.save(this@App, Thread.currentThread(), it) }
        }
    }
}
