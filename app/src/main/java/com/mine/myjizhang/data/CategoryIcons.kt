package com.mine.myjizhang.data

/**
 * 分类图标表：名称 → emoji（语义必须正确，如「烟酒」= 🚬 而不是汽车）。
 * 支出 / 收入分开维护，避免同类内图标重复。
 */
object CategoryIcons {

    /**
     * 支出一级分类。顺序 = 「记一笔」里的展示顺序，按真实使用频率排：
     * 三餐、交通、购物、日用在前，细分项（咖啡、零食、水果、买菜）紧随其后，
     * 低频项（烟酒、汽车、水电煤、母婴）往后放。
     *
     * 注意：这里不能出现语义重复的项——历史版本同时有「三餐」和「餐饮」、
     * 「日用」和「日用品」、「运动」和「运动健身」，用户看着会犯迷糊，已合并。
     */
    val expenseOrder = listOf(
        "三餐", "交通", "购物", "日用", "咖啡饮料", "零食", "娱乐", "医疗",
        "住房", "通讯", "水果", "买菜", "学习", "人情", "服饰", "美妆",
        "数码", "运动", "旅行", "宠物", "烟酒", "汽车", "水电煤", "母婴",
        "手续费", "发红包", "债务-借出", "其他支出"
    )

    /** 收入分类：工资、奖金在最前，投资收益其次，特殊收入靠后 */
    val incomeOrder = listOf(
        "工资", "奖金", "外快", "理财", "收红包", "退款",
        "分红", "租金", "公积金", "其他收入"
    )

    private val expenseIcons = mapOf(
        "三餐" to "🍚", "交通" to "🚌", "购物" to "🛒", "日用" to "🧴",
        "咖啡饮料" to "☕", "零食" to "🍪", "娱乐" to "🎮", "医疗" to "💊",
        "住房" to "🏠", "通讯" to "📱", "水果" to "🍓", "买菜" to "🥬",
        "学习" to "📚", "人情" to "🤝", "服饰" to "👕", "美妆" to "💄",
        "数码" to "💻", "运动" to "🏸", "旅行" to "✈️", "宠物" to "🐾",
        "烟酒" to "🚬", "汽车" to "🚗", "水电煤" to "💡", "母婴" to "👶",
        "手续费" to "🧾", "发红包" to "🧧", "债务-借出" to "📤", "其他支出" to "💸",

        // 常见扩展分类（用户自建 / 导入命中时用）
        "早餐" to "🍳", "午餐" to "🍱", "晚餐" to "🍛", "夜宵" to "🍢",
        "餐饮" to "🍜", "外卖" to "🥡", "饮料" to "🥤", "零食饮料" to "🥤",
        "日用品" to "🧻", "日用百货" to "🧺", "打车" to "🚕", "加油" to "⛽",
        "停车" to "🅿️", "高铁" to "🚄", "机票" to "🛫", "地铁" to "🚇",
        "公交" to "🚌", "房租" to "🛏️", "物业" to "🏢", "话费" to "📞",
        "网络" to "📶", "宽带" to "🌐", "药品" to "💉", "看病" to "🏥",
        "挂号" to "🩺", "培训" to "🎓", "书本" to "📓", "考试" to "📝",
        "电影" to "🎬", "唱歌" to "🎤", "游戏" to "🕹️", "会员" to "🎟️",
        "旅游" to "🧳", "酒店" to "🏨", "健身" to "💪", "运动健身" to "🏋️",
        "维修" to "🔧", "快递" to "📦", "理发" to "💈", "洗衣" to "🧺",
        "保险" to "🛡️", "捐款" to "❤️", "罚款" to "⚠️", "彩票" to "🎫",
        "债务-借入" to "📥", "还借款" to "💵", "利息" to "💰", "学费" to "🏫",
        "彩礼" to "💍", "家电" to "🔌", "电器数码" to "📺", "化妆" to "💅",
        "礼品" to "🎀", "宝贝" to "🍼", "网文" to "📖", "宠物用品" to "🦴",
        "其他" to "🗂"
    )

    private val incomeIcons = mapOf(
        "工资" to "💰", "奖金" to "🏆", "外快" to "💼", "理财" to "📊",
        "收红包" to "🎊", "退款" to "↩️", "分红" to "📈", "租金" to "🏘",
        "公积金" to "🏦", "其他收入" to "🪙",
        // 常见扩展
        "薪金" to "💰", "薪水" to "💰", "补贴" to "🧾", "津贴" to "🧾",
        "利息收入" to "💹", "兼职" to "🧑‍💻", "副业" to "💻", "二手闲置" to "🏷",
        "红包" to "🧧", "报销" to "🧾", "中奖" to "🎁", "赔偿" to "⚖️",
        "转账收入" to "💸", "分红收益" to "📈", "基金收益" to "📊",
        "股票收益" to "📈", "其他" to "🗂"
    )

    /** 兜底池：映射未命中时按顺序取一个未被占用的，保证同类内不重复 */
    private val fallback = listOf(
        "🔖", "🗂", "🧩", "🎯", "🌟", "🌿", "🐚", "🧭", "🪴", "🎨",
        "🔔", "📌", "🧊", "🪄", "🎪", "🛎", "🧴", "🪑", "🔑", "📎"
    )

    /**
     * 历史版本的重复分类 → 现在保留的那一个。
     *
     * 老版本默认分类表里同时塞了「三餐」和「餐饮」、「日用」和「日用品」、
     * 「运动」和「运动健身」、「数码」和「电器数码」，记一笔时看着就像两套账。
     * 只合并语义完全等价的这几组（地铁、外卖这类细分场景保留，用户可能刻意在用）。
     * AppDatabase 启动时会按这张表把账单改挂到保留项上，再删掉多余分类。
     */
    val mergeAlias = mapOf(
        "餐饮" to "三餐",
        "日用品" to "日用",
        "运动健身" to "运动",
        "电器数码" to "数码"
    )

    fun iconOf(name: String, type: Int): String? {
        val table = if (type == 1) incomeIcons else expenseIcons
        table[name]?.let { return it }
        // 名称含关键字时做一次包含匹配（如「债务 - 借出」）
        val normalized = name.replace(" ", "").replace("—", "-")
        table.entries.firstOrNull { normalized.contains(it.key) }?.let { return it.value }
        return null
    }

    /** 新建分类时的图标：优先语义映射，其次兜底池中未占用的 */
    fun pick(name: String, type: Int, used: Set<String>): String {
        iconOf(name, type)?.let { if (it !in used) return it }
        fallback.firstOrNull { it !in used }?.let { return it }
        iconOf(name, type)?.let { return it }
        return if (type == 1) "🪙" else "💸"
    }

    /** 默认分类清单（名称 + 图标） */
    fun defaults(type: Int): List<Pair<String, String>> {
        val order = if (type == 1) incomeOrder else expenseOrder
        val table = if (type == 1) incomeIcons else expenseIcons
        return order.map { it to (table[it] ?: "🔖") }
    }

    /** 借贷相关分类名判断（导入时用于生成借入/借出记录） */
    fun loanDirection(name: String): Int? {
        val n = name.replace(" ", "").replace("—", "-")
        return when {
            // 明确是「对方还我钱」——即使带「借款」二字也归借出这条线
            n.contains("收回") || n.contains("收债") || n.contains("还我") -> 1
            n.contains("借出") || n.contains("应收") || n.contains("垫付") || n.contains("借给") -> 1
            n.contains("借入") || n.contains("应付") || n.contains("欠款") ||
                n.contains("借款") || n.contains("还款") || n.contains("还钱") -> 0
            else -> null
        }
    }
}
