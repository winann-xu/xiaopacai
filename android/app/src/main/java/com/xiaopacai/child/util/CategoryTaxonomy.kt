package com.xiaopacai.child.util

/**
 * [REQ] 应用分类体系（更细粒度，替代旧的 5 大类）
 *
 * 目的：家长进入「应用分类」点一次「一键自动分类」，绝大部分应用即可得到
 * 合理分类，家长只需确认或微调。
 *
 * 注意：app_category 表存储细粒度分类；引擎（拦截/限额）使用 [toEngineCategory]
 * 映射回粗粒度 game/social/video/study/other，避免破坏现有策略逻辑。
 */
object CategoryTaxonomy {

    /** 细粒度分类 → 中文名 */
    val CATEGORY_LABELS = linkedMapOf(
        "game" to "游戏",
        "social" to "社交聊天",
        "short_video" to "短视频/直播",
        "video" to "长视频/影视",
        "study" to "学习/教育",
        "browser" to "浏览器",
        "shopping" to "购物/消费",
        "music" to "音乐/音频",
        "reading" to "阅读/资讯",
        "tools" to "工具/系统",
        "other" to "其他"
    )

    val CATEGORY_OPTIONS: List<String> = CATEGORY_LABELS.keys.toList()

    /**
     * 分类规则（按顺序匹配，命中即返回）。
     * 更具体的包名关键词放前面，避免被泛化关键词误伤。
     */
    private val CLASSIFICATION_RULES = listOf(
        // ===== 短视频/直播 =====
        "douyin" to "short_video",
        "tiktok" to "short_video",
        "抖音" to "short_video",
        "aweme" to "short_video",
        "ugc.aweme" to "short_video",
        "快手" to "short_video",
        "kuaishou" to "short_video",
        "gifmaker" to "short_video",
        "gifshow" to "short_video",
        "smile.gif" to "short_video",
        "西瓜视频" to "short_video",
        "小红书" to "short_video",
        "xiaohongshu" to "short_video",
        "xingin" to "short_video",
        "火山小视频" to "short_video",
        "微视" to "short_video",
        "bilibili" to "video",
        "tv.danmaku" to "video",
        "直播" to "short_video",
        "live" to "short_video",
        "shopee" to "shopping",
        // ===== 长视频/影视 =====
        "youtube" to "video",
        "iqiyi" to "video",
        "youku" to "video",
        "netflix" to "video",
        "tencent.qqlive" to "video",
        "芒果tv" to "video",
        "mgtv" to "video",
        "twitch" to "video",
        "爱奇艺" to "video",
        "优酷" to "video",
        "腾讯视频" to "video",
        "video" to "video",
        "视频" to "video",
        "影视" to "video",
        "电影" to "video",
        // ===== 游戏 =====
        "minecraft" to "game",
        "roblox" to "game",
        "brawl" to "game",
        "clash" to "game",
        "genshin" to "game",
        "原神" to "game",
        "王者荣耀" to "game",
        "和平精英" to "game",
        "阴阳师" to "game",
        "steam" to "game",
        "epicgames" to "game",
        "tencent.tmgp" to "game",
        "puzzle" to "game",
        "game" to "game",
        "游戏" to "game",
        // ===== 社交聊天 =====
        "tencent.mm" to "social",
        "wechat" to "social",
        "微信" to "social",
        "tencent.mobileqq" to "social",
        "qq" to "social",
        "sina.weibo" to "social",
        "微博" to "social",
        "twitter" to "social",
        "facebook" to "social",
        "instagram" to "social",
        "snapchat" to "social",
        "telegram" to "social",
        "whatsapp" to "social",
        "discord" to "social",
        "line" to "social",
        "钉钉" to "social",
        "dingtalk" to "social",
        "飞书" to "social",
        "lark" to "social",
        "微信读书" to "reading",
        "社交" to "social",
        "聊天" to "social",
        // ===== 学习/教育 =====
        "edu" to "study",
        "学习" to "study",
        "study" to "study",
        "词典" to "study",
        "dictionary" to "study",
        "作业" to "study",
        "作业帮" to "study",
        "笔记" to "study",
        "网课" to "study",
        "course" to "study",
        "课堂" to "study",
        "翻译" to "study",
        "translate" to "study",
        "calculator" to "study",
        "wikipedia" to "study",
        "百词斩" to "study",
        "猿辅导" to "study",
        "学而思" to "study",
        "新东方" to "study",
        "腾讯课堂" to "study",
        "网易云课堂" to "study",
        "教育" to "study",
        // ===== 浏览器 =====
        "com.android.chrome" to "browser",
        "chrome" to "browser",
        "com.android.browser" to "browser",
        "sogou.mobile.explorer" to "browser",
        "qq.browser" to "browser",
        "uc.browser" to "browser",
        "firefox" to "browser",
        "edge" to "browser",
        "safari" to "browser",
        "浏览器" to "browser",
        "browser" to "browser",
        // ===== 购物/消费 =====
        "taobao" to "shopping",
        "淘宝" to "shopping",
        "tmall" to "shopping",
        "天猫" to "shopping",
        "jingdong" to "shopping",
        "京东" to "shopping",
        "pinduoduo" to "shopping",
        "拼多多" to "shopping",
        "meituan" to "shopping",
        "美团" to "shopping",
        "ele" to "shopping",
        "饿了么" to "shopping",
        "闲鱼" to "shopping",
        "amazon" to "shopping",
        "shopping" to "shopping",
        "购物" to "shopping",
        "电商" to "shopping",
        // ===== 音乐/音频 =====
        "netease.cloudmusic" to "music",
        "网易云音乐" to "music",
        "kugou" to "music",
        "酷狗" to "music",
        "kuwo" to "music",
        "qqmusic" to "music",
        "qq音乐" to "music",
        "spotify" to "music",
        "music" to "music",
        "音乐" to "music",
        "喜马拉雅" to "music",
        "蜻蜓fm" to "music",
        "podcast" to "music",
        // ===== 阅读/资讯 =====
        "zhihu" to "reading",
        "知乎" to "reading",
        "今日头条" to "reading",
        "toutiao" to "reading",
        "新闻" to "reading",
        "news" to "reading",
        "阅读" to "reading",
        "reader" to "reading",
        "书" to "reading",
        "book" to "reading",
        "kindle" to "reading",
        "起点" to "reading",
        "番茄小说" to "reading",
        // ===== 工具/系统 =====
        "calculator" to "tools",
        "计算器" to "tools",
        "clock" to "tools",
        "calendar" to "tools",
        "日历" to "tools",
        "天气" to "tools",
        "weather" to "tools",
        "地图" to "tools",
        "map" to "tools",
        "file" to "tools",
        "文件管理" to "tools",
        "settings" to "tools",
        "设置" to "tools",
        "camera" to "tools",
        "相机" to "tools",
        "gallery" to "tools",
        "相册" to "tools",
        "note" to "tools",
        "备忘录" to "tools",
        "recorder" to "tools",
        "录音" to "tools",
        "security" to "tools",
        "手机管家" to "tools",
        "system" to "tools",
        "系统" to "tools"
    )

    /**
     * 按规则分类（返回细粒度分类）
     */
    fun classify(packageName: String, appName: String): String {
        val searchText = "${packageName.lowercase()} ${appName.lowercase()}"
        for ((keyword, category) in CLASSIFICATION_RULES) {
            if (keyword.lowercase() in searchText) {
                return category
            }
        }
        return "other"
    }

    /**
     * 细粒度分类 → 引擎粗粒度分类（game/social/video/study/other）
     */
    fun toEngineCategory(category: String): String = when (category) {
        "short_video", "video" -> "video"
        "learning" -> "study"
        "browser", "shopping", "music", "reading", "tools" -> "other"
        else -> category
    }
}
