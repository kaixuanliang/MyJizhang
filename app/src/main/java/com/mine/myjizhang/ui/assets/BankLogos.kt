package com.mine.myjizhang.ui.assets

import com.mine.myjizhang.R

/**
 * 银行/账户真实 logo 映射（矢量 drawable，对齐官方标识）。
 * res() 先精确匹配账户名，再按关键字包含匹配（如「中国工商银行6204」→ 工行 logo）。
 */
object BankLogos {

    /** 精确名称匹配（支付平台 / 投资理财 / 特殊账户） */
    private val exact = linkedMapOf(
        "微信" to R.drawable.logo_wechat,
        "支付宝" to R.drawable.logo_alipay,
        "花呗" to R.drawable.logo_huabei,
        "现金" to R.drawable.logo_cash,
        "QQ钱包" to R.drawable.logo_qq,
        "京东金融" to R.drawable.logo_jd,
        "京东白条" to R.drawable.logo_jd,
        "云闪付" to R.drawable.logo_upay,
        "数字人民币" to R.drawable.logo_dcep,
        "公积金" to R.drawable.logo_house,
        "医保账户" to R.drawable.logo_med,
        "医保" to R.drawable.logo_med,
        // 投资理财
        "股票" to R.drawable.logo_stock,
        "基金" to R.drawable.logo_fund,
        "虚拟币" to R.drawable.logo_coin,
        "黄金" to R.drawable.logo_gold,
        "债券" to R.drawable.logo_bond,
        "其他理财" to R.drawable.logo_invest,
        "信用卡" to R.drawable.logo_credit,
        "其他" to R.drawable.logo_wallet,
        "其他信贷" to R.drawable.logo_credit,
        // 银行全称（无关键字歧义的）
        "中国工商银行" to R.drawable.logo_icbc,
        "中国建设银行" to R.drawable.logo_ccb,
        "中国农业银行" to R.drawable.logo_abc,
        "中国银行" to R.drawable.logo_boc,
        "交通银行" to R.drawable.logo_bcm,
        "中国邮政储蓄银行" to R.drawable.logo_psbc,
        "招商银行" to R.drawable.logo_cmb,
        "中信银行" to R.drawable.logo_citic,
        "上海浦东发展银行" to R.drawable.logo_spdb,
        "中国民生银行" to R.drawable.logo_cmbc,
        "中国光大银行" to R.drawable.logo_ceb,
        "华夏银行" to R.drawable.logo_hxb,
        "兴业银行" to R.drawable.logo_cib,
        "平安银行" to R.drawable.logo_pab,
        "广发银行" to R.drawable.logo_gdb,
        "北京银行" to R.drawable.logo_bocb
    )

    /** 关键字包含匹配：长关键字在前，避免误命中 */
    private val contains = linkedMapOf(
        "工商银行" to R.drawable.logo_icbc,
        "建设银行" to R.drawable.logo_ccb,
        "农业银行" to R.drawable.logo_abc,
        "邮政储蓄" to R.drawable.logo_psbc,
        "招商银行" to R.drawable.logo_cmb,
        "浦发" to R.drawable.logo_spdb,
        "民生银行" to R.drawable.logo_cmbc,
        "光大银行" to R.drawable.logo_ceb,
        "华夏银行" to R.drawable.logo_hxb,
        "兴业银行" to R.drawable.logo_cib,
        "平安银行" to R.drawable.logo_pab,
        "广发银行" to R.drawable.logo_gdb,
        "北京银行" to R.drawable.logo_bocb,
        "中信银行" to R.drawable.logo_citic,
        "交通银行" to R.drawable.logo_bcm,
        "中国银行" to R.drawable.logo_boc,
        // 投资理财类账户名（如「股票账户」「虚拟币钱包」）
        "股票" to R.drawable.logo_stock,
        "证券" to R.drawable.logo_stock,
        "基金" to R.drawable.logo_fund,
        "虚拟币" to R.drawable.logo_coin,
        "比特币" to R.drawable.logo_coin,
        "黄金" to R.drawable.logo_gold,
        "贵金属" to R.drawable.logo_gold,
        "债券" to R.drawable.logo_bond,
        "理财" to R.drawable.logo_invest,
        "花呗" to R.drawable.logo_huabei,
        "白条" to R.drawable.logo_jd,
        "支付宝" to R.drawable.logo_alipay,
        "微信" to R.drawable.logo_wechat,
        "公积金" to R.drawable.logo_house,
        "医保" to R.drawable.logo_med,
        "现金" to R.drawable.logo_cash
    )

    /** 返回 logo 资源 id，0 表示没有（走文字缩写 / emoji 兜底） */
    fun res(name: String): Int {
        if (name.isBlank()) return 0
        exact[name]?.let { return it }
        contains.forEach { (key, id) -> if (name.contains(key)) return id }
        return 0
    }
}
