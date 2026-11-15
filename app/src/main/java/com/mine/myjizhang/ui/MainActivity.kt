package com.mine.myjizhang.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mine.myjizhang.R
import com.mine.myjizhang.data.DataBus
import com.mine.myjizhang.ui.assets.AssetsFragment
import com.mine.myjizhang.ui.bills.BillsFragment
import com.mine.myjizhang.ui.calendar.CalendarFragment
import com.mine.myjizhang.ui.edit.BillEditActivity
import com.mine.myjizhang.ui.settings.SettingsFragment
import com.mine.myjizhang.ui.stats.StatsFragment
import com.mine.myjizhang.util.CrashHandler
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var fab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById(R.id.fragment_container)
        bottomNav = findViewById(R.id.bottom_nav)
        fab = findViewById(R.id.fab_add)

        fab.setOnClickListener {
            BillEditActivity.start(this, null)
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_bills -> show(0, ::BillsFragment)
                R.id.nav_calendar -> show(1, ::CalendarFragment)
                R.id.nav_assets -> show(2, ::AssetsFragment)
                R.id.nav_stats -> show(3, ::StatsFragment)
                R.id.nav_settings -> show(4, ::SettingsFragment)
            }
            true
        }
        bottomNav.selectedItemId = R.id.nav_bills

        // 上一轮若崩过，把崩溃栈摆出来（可复制），不再只有一句「闪退」
        window.decorView.post { showPendingCrash() }

        // 数据变更（导入 / 切账本 / 清空 / 恢复备份）后刷新当前页
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DataBus.version.collect { refreshVisibleTab() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从账本管理、记一笔、资产详情等页面返回时，当前页可能已经不是最新的了
        refreshVisibleTab()
    }

    private fun showPendingCrash() {
        val text = CrashHandler.read(this) ?: return
        CrashHandler.clear(this)
        val body = TextView(this).apply {
            this.text = text.take(8000)
            textSize = 12f
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("上次运行出错了")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("复制错误信息") { _, _ ->
                CrashHandler.copy(this@MainActivity, text)
                Toast.makeText(this@MainActivity, "已复制，粘贴发给开发者即可定位", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 按 tag 管理 5 个常驻 Fragment：显示目标、隐藏其余。
     * tag 查找保证 Activity 重建后不会重复创建导致页面叠加。
     *
     * 注意：hide() 的 Fragment 仍是 RESUMED，切回来不会回调 onResume，
     * 所以这里要显式让目标页重新查库（否则切账本 / 导入后列表还是旧的）。
     */
    private fun show(index: Int, creator: () -> Fragment) {
        val fm = supportFragmentManager
        val tag = "tab_$index"
        val target = fm.findFragmentByTag(tag) ?: creator()
        val reused = target.isAdded
        fm.beginTransaction().apply {
            for (i in 0..4) {
                if (i == index) continue
                fm.findFragmentByTag("tab_$i")?.let { hide(it) }
            }
            if (target.isAdded) show(target) else add(R.id.fragment_container, target, tag)
            commitAllowingStateLoss()
        }
        // 复用的页面不会走 onResume，主动刷新；新建的页面在 onResume 里自己刷新
        if (reused) (target as? TabRefreshable)?.refreshTab()
        // 记账按钮只在账单页显示
        fab.visibility = if (index == 0) View.VISIBLE else View.GONE
    }

    /** 只刷新当前可见的页面（隐藏页面等切回来时再刷，省一次查库） */
    private fun refreshVisibleTab() {
        val fm = supportFragmentManager
        for (i in 0..4) {
            val f = fm.findFragmentByTag("tab_$i") ?: continue
            if (!f.isAdded || f.isHidden) continue
            (f as? TabRefreshable)?.refreshTab()
        }
    }
}
