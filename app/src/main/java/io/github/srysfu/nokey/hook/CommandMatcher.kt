package io.github.srysfu.nokey.hook

/**
 * 口令关键词 → NOKEY 命令码 的匹配器。
 *
 * 核心作用：把"小爱界面渲染出来的文本"翻译成要发送的车辆命令码。
 * 纯字符串匹配、无任何 Android 依赖，方便单测。
 *
 * 与 MainHook 中的命令码定义保持一致：
 *   11 = 解锁, 12 = 锁车, 41 = 引擎启动, 42 = 引擎关闭, 51 = 车窗开, 52 = 车窗关
 */
object CommandMatcher {

    /** 单条命令定义 */
    data class Command(val code: Int, val name: String, val keywords: List<String>)

    /** 内置默认词表（用户未自定义时兜底） */
    val DEFAULT_COMMANDS: List<Command> = listOf(
        Command(0x0b, "解锁", listOf("解锁", "开锁")),
        Command(0x0c, "锁车", listOf("锁车", "上锁", "锁闭")),
        Command(0x29, "引擎启动", listOf("启动引擎", "引擎启动", "点火", "发动引擎", "启动车辆", "着车")),
        Command(0x2a, "引擎关闭", listOf("关闭引擎", "引擎关闭", "熄火", "关闭发动机", "停引擎")),
        Command(0x33, "车窗开", listOf("打开车窗", "车窗打开", "开窗", "升降车窗", "车窗降下", "降下车窗")),
        Command(0x34, "车窗关", listOf("关闭车窗", "车窗关闭", "关窗", "车窗升起", "关上窗", "升起车窗"))
    )

    /**
     * 当前生效词表。默认指向 [DEFAULT_COMMANDS]；
     * hook 端载入用户在配置界面自定义的词表后，会覆盖此引用。
     */
    @Volatile
    var currentCommands: List<Command> = DEFAULT_COMMANDS

    /** 兼容旧代码 / 测试引用的命名 */
    val COMMANDS: List<Command> get() = currentCommands

    /**
     * 小爱界面文本中可能出现的、表示"已经识别到命令并准备执行/已理解"的辅助词。
     * 用于在"用户复述气泡"与"系统回复"两种文本形态下都能命中。
     * 目前保留空集合，主要靠 [matchCommandCode] 的纯关键词逻辑。
     */
    private val TRIGGER_HINTS: List<String> = emptyList()

    /**
     * 从一段文本中解析出命令码；找不到返回 -1。
     *
     * 设计取舍：为避免"解锁屏幕 / 打开空调"等非目标语义误触发，
     * 这里采用【整文本包含匹配】——只要小爱界面渲染出的这段文本里出现了
     * 目标指令关键词（如"解锁"/"启动引擎"），即判定为该命令。
     */
    fun matchCommandCode(text: String?): Int {
        if (text.isNullOrBlank()) return -1
        val t = text.trim()
        if (t.length > 64) return -1   // 防御：超长文本是系统长篇回复而非口头指令

        for (cmd in COMMANDS) {
            for (kw in cmd.keywords) {
                if (t.contains(kw)) return cmd.code
            }
        }
        return -1
    }

    /** 由命令码取命令名，用于日志；未知返回十六进制 */
    fun nameOf(code: Int): String {
        return COMMANDS.firstOrNull { it.code == code }?.name ?: ("0x" + Integer.toHexString(code))
    }
}
