package com.mine.myjizhang.ui.assets

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat

/** 资产类型定义（「请选择资产类型」弹窗用） */
data class AssetTypeInfo(
    val name: String,      // 类型名（存入 Asset.subType）
    val group: String,     // 分组：信贷账户 / 充值账户 / 资金账户 / 投资理财账户
    val emoji: String,     // 图标 emoji（无官方 logo 时兜底）
    val color: String,     // 圆底颜色 #RRGGBB
    val credit: Boolean    // 是否信贷账户（有额度、有欠款）
)

object AssetTypes {

    const val GROUP_CREDIT = "信贷账户"
    const val GROUP_PREPAID = "充值账户"
    const val GROUP_FUND = "资金账户"
    const val GROUP_INVEST = "投资理财账户"

    /** 页面分组顺序（对齐原版）：信贷 → 充值 → 资金 → 投资理财 */
    val groups = listOf(GROUP_CREDIT, GROUP_PREPAID, GROUP_FUND, GROUP_INVEST)

    val all = listOf(
        // ---------- 信贷账户 ----------
        AssetTypeInfo("信用卡", GROUP_CREDIT, "💳", "#4A63D9", true),
        AssetTypeInfo("花呗", GROUP_CREDIT, "花", "#1677FF", true),
        AssetTypeInfo("京东白条", GROUP_CREDIT, "白", "#E2231A", true),
        AssetTypeInfo("其他信贷", GROUP_CREDIT, "🏦", "#7B5EA7", true),

        // ---------- 充值账户（预充值卡类：钱先充进去，再慢慢消费） ----------
        AssetTypeInfo("话费", GROUP_PREPAID, "📱", "#1677FF", false),
        AssetTypeInfo("公交卡", GROUP_PREPAID, "🚌", "#2AAE67", false),
        AssetTypeInfo("饭卡", GROUP_PREPAID, "🍚", "#E8590C", false),
        AssetTypeInfo("会员卡", GROUP_PREPAID, "🎫", "#E2231A", false),
        AssetTypeInfo("油卡", GROUP_PREPAID, "⛽", "#0E8C7F", false),
        AssetTypeInfo("游戏点卡", GROUP_PREPAID, "🕹", "#6C5CE7", false),
        AssetTypeInfo("校园卡", GROUP_PREPAID, "🎓", "#2E6DA4", false),
        AssetTypeInfo("押金", GROUP_PREPAID, "押", "#7C8695", false),

        // ---------- 资金账户（钱在手上 / 银行 / 支付平台） ----------
        AssetTypeInfo("现金", GROUP_FUND, "💵", "#E24B4A", false),
        AssetTypeInfo("微信", GROUP_FUND, "微", "#2AAE67", false),
        AssetTypeInfo("支付宝", GROUP_FUND, "支", "#1677FF", false),
        AssetTypeInfo("储蓄卡", GROUP_FUND, "💳", "#3D9BE9", false),
        AssetTypeInfo("公积金", GROUP_FUND, "公", "#2E6DA4", false),
        AssetTypeInfo("QQ钱包", GROUP_FUND, "Q", "#12B7F5", false),
        AssetTypeInfo("京东金融", GROUP_FUND, "京", "#E2231A", false),
        AssetTypeInfo("云闪付", GROUP_FUND, "闪", "#D7000F", false),
        AssetTypeInfo("医保账户", GROUP_FUND, "医", "#2AAE67", false),
        AssetTypeInfo("数字人民币", GROUP_FUND, "数", "#E24B4A", false),
        AssetTypeInfo("其他", GROUP_FUND, "🗂", "#7C8695", false),

        // ---------- 投资理财账户 ----------
        AssetTypeInfo("股票", GROUP_INVEST, "📈", "#E8590C", false),
        AssetTypeInfo("基金", GROUP_INVEST, "基", "#2F6FED", false),
        AssetTypeInfo("虚拟币", GROUP_INVEST, "₿", "#F7931A", false),
        AssetTypeInfo("黄金", GROUP_INVEST, "金", "#C9931B", false),
        AssetTypeInfo("债券", GROUP_INVEST, "债", "#0E8C7F", false),
        AssetTypeInfo("定期存款", GROUP_INVEST, "🏛", "#2E6DA4", false),
        AssetTypeInfo("其他理财", GROUP_INVEST, "🍀", "#6C5CE7", false)
    )

    private val byName = all.associateBy { it.name }

    fun info(subType: String): AssetTypeInfo? = byName[subType]

    fun isCredit(subType: String): Boolean = byName[subType]?.credit
        ?: subType.contains("信用卡") || subType.contains("信贷") || subType.contains("白条")

    /** 兼容旧数据：早期版本把「充值类」并进资金账户，这里给出旧值 → 新值的映射 */
    private val legacyGroup = mapOf(
        "话费" to GROUP_PREPAID, "公交卡" to GROUP_PREPAID, "饭卡" to GROUP_PREPAID,
        "会员卡" to GROUP_PREPAID, "押金" to GROUP_PREPAID
    )

    /** 根据账户名猜测类型（导入 / 老数据规范化用） */
    fun match(name: String): AssetTypeInfo {
        return when {
            name.contains("花呗") -> byName["花呗"]!!
            name.contains("白条") -> byName["京东白条"]!!
            name.contains("信用卡") || name.contains("贷记") -> byName["信用卡"]!!
            name.contains("虚拟币") || name.contains("比特币") || name.contains("币安") ||
                name.contains("USDT") || name.contains("以太") -> byName["虚拟币"]!!
            name.contains("股票") || name.contains("证券") || name.contains("炒股") -> byName["股票"]!!
            name.contains("基金") -> byName["基金"]!!
            name.contains("黄金") || name.contains("贵金属") -> byName["黄金"]!!
            name.contains("债券") -> byName["债券"]!!
            name.contains("定期") || name.contains("大额存单") -> byName["定期存款"]!!
            name.contains("理财") || name.contains("余额宝") || name.contains("零钱通") ->
                byName["其他理财"]!!
            name.contains("现金") -> byName["现金"]!!
            name.contains("微信") || name.contains("零钱") -> byName["微信"]!!
            name.contains("支付宝") -> byName["支付宝"]!!
            name.contains("公积金") -> byName["公积金"]!!
            name.contains("医保") -> byName["医保账户"]!!
            name.contains("公交") || name.contains("地铁卡") -> byName["公交卡"]!!
            name.contains("饭卡") || name.contains("食堂") -> byName["饭卡"]!!
            name.contains("话费") || name.contains("手机号") || name.contains("流量") -> byName["话费"]!!
            name.contains("油卡") || name.contains("加油卡") -> byName["油卡"]!!
            name.contains("会员卡") || name.contains("储值卡") -> byName["会员卡"]!!
            name.contains("游戏") -> byName["游戏点卡"]!!
            name.contains("校园卡") || name.contains("学生卡") -> byName["校园卡"]!!
            name.contains("押金") -> byName["押金"]!!
            name.contains("QQ") || name.contains("qq") -> byName["QQ钱包"]!!
            name.contains("京东") -> byName["京东金融"]!!
            name.contains("云闪付") -> byName["云闪付"]!!
            name.contains("数字人民币") || name.contains("e-CNY") -> byName["数字人民币"]!!
            // 银行清单只留了 19 家主流银行，长尾银行（江苏银行、农商行、村镇银行…）
            // 靠这个兜底仍然归到「储蓄卡」，不会因为名字不在清单里就掉进「其他」
            name.contains("银行") || name.contains("农商") ||
                name.contains("信用社") || name.contains("农信") -> byName["储蓄卡"]!!
            bankOf(name) != null -> byName["储蓄卡"]!!
            else -> byName["其他"]!!
        }
    }

    /** 兼容旧引用：银行列表已迁移到 Banks */
    val banks: List<Pair<String, Bank>> get() = Banks.all.map { it.name to it.bank }

    /** 从账户名里找银行（如「广发银行信用卡」） */
    fun bankOf(name: String): Bank? = Banks.of(name)

    /**
     * 渲染图标（三档优先级）：
     * 1) 图形本身就是品牌标识的 → 直接用矢量 drawable（微信气泡 / 现金 / 信用卡 / 投资理财类…）
     * 2) 银行、支付平台 → 品牌色渐变圆标 + 白色汉字缩写（工/建/招/支/花…）
     * 3) 其它 → emoji 淡色圆底
     */
    fun bindIcon(view: TextView, name: String, subType: String, icon: String) {
        val ctx = view.context

        // ① 品牌矢量图优先（按账户名，其次按资产类型名）
        val logoRes = BankLogos.res(name).takeIf { it != 0 } ?: BankLogos.res(subType)
        if (logoRes != 0) {
            view.text = ""
            view.background = ContextCompat.getDrawable(ctx, logoRes)
            view.setTextColor(Color.WHITE)
            view.setPadding(0, 0, 0, 0)
            return
        }

        // ② 银行 / 支付平台：品牌色圆标 + 汉字（统一风格，32dp 下依然清楚）
        val brand = Banks.of(name)
            ?: Brands.of(name)
            ?: bankByShort(icon)
            ?: Banks.of(subType)
            ?: Brands.of(subType)
        if (brand != null && brand.short.isNotBlank()) {
            bindBadge(view, brand.short, brand.color)
            return
        }

        // ③ 存量的自定义文字缩写（老数据里 icon 存了「工」「建」这类）
        if (icon.isNotBlank() && icon.matches(BADGE_TEXT) && !icon.first().isEmoji()) {
            bindBadge(view, icon, info(subType)?.color ?: "#3D9BE9")
            return
        }

        // ④ emoji 淡色圆底
        val colorStr = info(subType)?.color ?: "#3D9BE9"
        view.text = icon.ifBlank { info(subType)?.emoji ?: "🏦" }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(applyAlpha(Color.parseColor(colorStr), 0x22))
        }
        view.setTextColor(Color.parseColor(colorStr))
        view.textSize = 20f
        view.setPadding(0, 0, 0, 0)
    }

    private val BADGE_TEXT = Regex("[\\p{ASCII}\\u4e00-\\u9fa5]{1,3}")

    private fun bankByShort(short: String): Bank? =
        if (short.isBlank()) null else Banks.all.firstOrNull { it.bank.short == short }?.bank

    /**
     * 品牌色渐变圆标 + 白色字标。
     * 字号按圆标尺寸换算（单字 52%、双字 40%），并关掉字体额外行距，
     * 避免「中信」「浦发」这类双字标被挤出圆外。
     */
    private fun bindBadge(view: TextView, text: String, colorStr: String) {
        val d = view.resources.displayMetrics.density
        val w = view.layoutParams?.width ?: 0
        val base = if (w > 0) w.toFloat() else 40 * d
        val ratio = when (text.length) {
            1 -> 0.52f
            2 -> 0.40f
            else -> 0.32f
        }
        val color = try {
            Color.parseColor(colorStr)
        } catch (e: Exception) {
            Color.parseColor("#3D9BE9")
        }
        view.text = text
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * ratio)
        view.typeface = Typeface.DEFAULT_BOLD
        view.gravity = Gravity.CENTER
        view.includeFontPadding = false
        view.maxLines = 1
        view.setTextColor(Color.WHITE)
        view.setPadding(0, 0, 0, 0)
        view.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(color, 0.16f), color)
        ).apply {
            shape = GradientDrawable.OVAL
        }
    }

    /** 顶部提亮一点，圆标不会显得死板 */
    private fun lighten(color: Int, ratio: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * ratio).toInt().coerceAtMost(255)
        val g = (Color.green(color) + (255 - Color.green(color)) * ratio).toInt().coerceAtMost(255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * ratio).toInt().coerceAtMost(255)
        return Color.rgb(r, g, b)
    }

    /**
     * 账户所属分组：
     * 1) subType 命中类型表 → 按类型表
     * 2) 信用卡 type=1 → 信贷
     * 3) 老数据 subType 为 "其他"/空 → 按账户名重新推断（股票/基金/虚拟币 归投资理财）
     */
    fun groupOf(subType: String, type: Int, name: String): String {
        byName[subType]?.let { info ->
            // 类型写的是「其他」时，名称更有语义（如名字里写着「虚拟币」）
            if (info.name == "其他") return match(name).group
            legacyGroup[info.name]?.let { return it }
            return info.group
        }
        if (type == 1) return GROUP_CREDIT
        return match(name).group
    }

    /** 老账户修正：subType 为空 / 其他 / 与名称语义严重不符时，按名称重新推断 */
    fun normalizeSubType(subType: String, type: Int, name: String): String {
        if (type == 1) {
            val t = if (subType.isBlank() || subType == "其他") match(name).name else subType
            return if (isCredit(t)) t else "信用卡"
        }
        if (subType.isBlank() || subType == "其他") return match(name).name
        // 名称里有明确的投资理财 / 充值语义，但类型写错了 → 以名称为准
        val guessed = match(name)
        if (guessed.name != "其他" && guessed.group != byName[subType]?.group &&
            guessed.group != GROUP_FUND
        ) {
            return guessed.name
        }
        return subType
    }

    private fun Char.isEmoji(): Boolean =
        this.code > 0x2000 && !Regex("[\\u4e00-\\u9fa5]").matches(this.toString())

    private fun applyAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
