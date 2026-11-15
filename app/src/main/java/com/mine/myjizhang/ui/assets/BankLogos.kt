package com.mine.myjizhang.ui.assets

import com.mine.myjizhang.R

/**
 * 账户图标表。
 *
 * ① 银行 / 支付平台：用官方图形标志（矢量），圆底取官方品牌色。
 *    单色标 → 品牌色圆底 + 白色标志；
 *    双色标（民生、平安、广发、华夏、上海、大连）→ 品牌色淡雾底 + 原色标志，保住双层图形。
 * ② 无官方图形标的通用账户（现金、股票、基金…）：保留手绘矢量图标。
 * ③ 其余（村镇银行、农商行等长尾 200+ 家）：仍走「品牌色圆标 + 汉字缩写」，见 AssetTypes.bindIcon。
 */
object BankLogos {

    /** 精确名称匹配 */
    private val exact = linkedMapOf(
        // ---- 银行 / 支付平台（官方标志） ----
        "工商银行" to R.drawable.bank_icbc,
        "建设银行" to R.drawable.bank_ccb,
        "农业银行" to R.drawable.bank_abc,
        "中国银行" to R.drawable.bank_boc,
        "交通银行" to R.drawable.bank_bocom,
        "招商银行" to R.drawable.bank_cmb,
        "邮储银行" to R.drawable.bank_psbc,
        "浦发银行" to R.drawable.bank_spdb,
        "中信银行" to R.drawable.bank_citic,
        "光大银行" to R.drawable.bank_ceb,
        "民生银行" to R.drawable.bank_cmbc,
        "兴业银行" to R.drawable.bank_cib,
        "平安银行" to R.drawable.bank_pab,
        "广发银行" to R.drawable.bank_cgb,
        "华夏银行" to R.drawable.bank_hxb,
        "浙商银行" to R.drawable.bank_czb,
        "北京银行" to R.drawable.bank_bob,
        "上海银行" to R.drawable.bank_bosh,
        "吉林银行" to R.drawable.bank_jlbank,
        "大连银行" to R.drawable.bank_dlbank,
        "支付宝" to R.drawable.bank_alipay,

        // ---- 通用账户 / 理财类 ----
        "微信" to R.drawable.logo_wechat,
        "QQ钱包" to R.drawable.logo_qq,
        "现金" to R.drawable.logo_cash,
        "数字人民币" to R.drawable.logo_dcep,
        "公积金" to R.drawable.logo_house,
        "医保账户" to R.drawable.logo_med,
        "医保" to R.drawable.logo_med,
        "股票" to R.drawable.logo_stock,
        "基金" to R.drawable.logo_fund,
        "虚拟币" to R.drawable.logo_coin,
        "黄金" to R.drawable.logo_gold,
        "债券" to R.drawable.logo_bond,
        "其他理财" to R.drawable.logo_invest,
        "信用卡" to R.drawable.logo_credit,
        "其他信贷" to R.drawable.logo_credit,
        "其他" to R.drawable.logo_wallet
    )

    /**
     * 关键字包含匹配（账户名里带这些词就套用对应图标）。
     * 顺序即优先级：「北京银行」这类全名要排在宽泛词前面，避免被抢走。
     */
    private val contains = linkedMapOf(
        // 银行（按全名 → 简称），先长后短
        "工商银行" to R.drawable.bank_icbc, "工行" to R.drawable.bank_icbc,
        "建设银行" to R.drawable.bank_ccb, "建行" to R.drawable.bank_ccb,
        "农业银行" to R.drawable.bank_abc, "农行" to R.drawable.bank_abc,
        "中国银行" to R.drawable.bank_boc, "中行" to R.drawable.bank_boc,
        "交通银行" to R.drawable.bank_bocom, "交行" to R.drawable.bank_bocom,
        "招商银行" to R.drawable.bank_cmb, "招行" to R.drawable.bank_cmb,
        "邮政储蓄" to R.drawable.bank_psbc, "邮储" to R.drawable.bank_psbc,
        "浦东发展" to R.drawable.bank_spdb, "浦发" to R.drawable.bank_spdb,
        "中信银行" to R.drawable.bank_citic,
        "光大银行" to R.drawable.bank_ceb, "光大" to R.drawable.bank_ceb,
        "民生银行" to R.drawable.bank_cmbc,
        "兴业银行" to R.drawable.bank_cib, "兴业" to R.drawable.bank_cib,
        "平安银行" to R.drawable.bank_pab,
        "广发银行" to R.drawable.bank_cgb, "广东发展" to R.drawable.bank_cgb,
        "华夏银行" to R.drawable.bank_hxb,
        "浙商银行" to R.drawable.bank_czb,
        "北京银行" to R.drawable.bank_bob,
        "上海银行" to R.drawable.bank_bosh,
        "吉林银行" to R.drawable.bank_jlbank,
        "大连银行" to R.drawable.bank_dlbank,
        "支付宝" to R.drawable.bank_alipay,

        // 通用账户 / 理财类
        "股票" to R.drawable.logo_stock,
        "证券" to R.drawable.logo_stock,
        "基金" to R.drawable.logo_fund,
        "虚拟币" to R.drawable.logo_coin,
        "比特币" to R.drawable.logo_coin,
        "黄金" to R.drawable.logo_gold,
        "贵金属" to R.drawable.logo_gold,
        "债券" to R.drawable.logo_bond,
        "理财" to R.drawable.logo_invest,
        "公积金" to R.drawable.logo_house,
        "医保" to R.drawable.logo_med,
        "现金" to R.drawable.logo_cash,
        "微信" to R.drawable.logo_wechat
    )

    /** 返回 logo 资源 id，0 表示没有（走品牌色汉字圆标 / emoji 兜底） */
    fun res(name: String): Int {
        if (name.isBlank()) return 0
        exact[name]?.let { return it }
        contains.forEach { (key, id) -> if (name.contains(key)) return id }
        return 0
    }
}
