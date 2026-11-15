package com.mine.myjizhang.ui.assets

/**
 * 银行条目：显示名 + 品牌标 + 别名（简称，"工行"这类）。
 * 别名只在匹配账户名时使用，不出现在选择列表里。
 */
data class BankEntry(val name: String, val bank: Bank, val alias: List<String> = emptyList())

/** 银行 logo：品牌色圆底 + 缩写字（仅长尾银行兜底使用；主流银行走官方矢量标） */
data class Bank(val short: String, val color: String)

/**
 * 支付平台 / 钱包类品牌。
 * 这些不在银行清单里，但图标规则一致：品牌色圆底 + 白色字标
 * （支付宝官方标识本来就是蓝底白「支」，花呗是蓝底白「花」，比手绘图形更准也更清楚）。
 */
object Brands {
    private val all = listOf(
        "京东白条" to Bank("白条", "#E1251B"),
        "京东金融" to Bank("京", "#E1251B"),
        "数字人民币" to Bank("币", "#C7000B"),
        "云闪付" to Bank("闪", "#E60012"),
        "支付宝" to Bank("支", "#1677FF"),
        "花呗" to Bank("花", "#1677FF"),
        "借呗" to Bank("借", "#1677FF"),
        "微信" to Bank("微", "#07C160"),
        "QQ钱包" to Bank("Q", "#12B7F5"),
        "美团" to Bank("美", "#FFB300"),
        "抖音" to Bank("抖", "#161823")
    )

    /** 长关键字优先，避免「京东白条」被「京东金融」抢走 */
    fun of(name: String): Bank? {
        if (name.isBlank()) return null
        all.forEach { (key, bank) -> if (name.contains(key)) return bank }
        return null
    }
}

/**
 * 主流银行清单（21 家）。
 *
 * 之前列了 248 家（含村镇银行、外资行），选择时要滑十几屏，实际用不到；
 * 现在只保留工农中建交邮 + 浦发、中信、光大、民生、兴业、平安、广发、华夏、
 * 浙商、北京、上海、吉林、大连——日常 95% 以上的场景都在里面。
 * 其余银行走「其他银行（手动输入）」，图标仍由 AssetTypes 按名称渲染汉字圆标。
 *
 * 品牌色取自各家官方标志文件（与 res/drawable/bank_*.xml 的圆底一致）。
 */
object Banks {
    val all = listOf(
        BankEntry("工商银行", Bank("工", "#D9222A"), listOf("工行", "中国工商银行", "ICBC")),
        BankEntry("农业银行", Bank("农", "#009B4C"), listOf("农行", "中国农业银行", "ABC")),
        BankEntry("中国银行", Bank("中", "#A6091E"), listOf("中行", "BOC")),
        BankEntry("建设银行", Bank("建", "#193E7E"), listOf("建行", "中国建设银行", "CCB")),
        BankEntry("交通银行", Bank("交", "#003F88"), listOf("交行", "BOCOM")),
        BankEntry("邮储银行", Bank("邮", "#00754A"), listOf("邮政储蓄", "邮政储蓄银行", "PSBC")),
        BankEntry("浦发银行", Bank("浦发", "#00549F"), listOf("浦东发展", "上海浦东发展银行", "SPDB")),
        BankEntry("中信银行", Bank("中信", "#D80C18"), listOf("CITIC")),
        BankEntry("光大银行", Bank("光大", "#7A1B3E"), listOf("中国光大银行", "CEB")),
        BankEntry("民生银行", Bank("民生", "#00875A"), listOf("中国民生银行", "CMBC")),
        BankEntry("兴业银行", Bank("兴", "#00427A"), listOf("CIB")),
        BankEntry("平安银行", Bank("平", "#F26F21"), listOf("PAB")),
        BankEntry("广发银行", Bank("广", "#D0021B"), listOf("广东发展", "CGB")),
        BankEntry("华夏银行", Bank("华夏", "#A50034"), listOf("HXB")),
        BankEntry("浙商银行", Bank("浙商", "#0057A7"), listOf("CZB")),
        BankEntry("北京银行", Bank("京", "#BE1E2D"), listOf("BOB")),
        BankEntry("上海银行", Bank("上", "#005B9D"), listOf("BOSC")),
        BankEntry("吉林银行", Bank("吉", "#005BAC"), listOf("JLBANK")),
        BankEntry("大连银行", Bank("大", "#B01C2E"), listOf("DLBANK"))
    )

    /**
     * 从账户名里找银行：长名优先，避免「中国银行」抢走「中国工商银行」。
     * 别名里的「工行」「建行」这类简写也认。
     */
    fun of(name: String): Bank? {
        if (name.isBlank()) return null
        for (e in all.sortedByDescending { it.name.length }) {
            if (name.contains(e.name)) return e.bank
        }
        for (e in all) {
            if (e.alias.any { name.contains(it) }) return e.bank
        }
        return null
    }
}
