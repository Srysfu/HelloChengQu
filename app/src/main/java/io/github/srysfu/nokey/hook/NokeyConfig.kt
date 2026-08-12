package io.github.srysfu.nokey.hook

import java.io.File
import java.io.IOException

/**
 * 可自定义的功能配置。
 *
 * 用户通过模块的配置界面（MainActivity）编辑：
 *  - 各命令的唤醒词（commands）
 *  - 「已成功」反馈的提示音 URI（toneUri）
 *  - 「已成功」屏幕文案及其随机条目（successTexts）
 *
 * 序列化为简洁的 JSON 写入共享配置文件：
 *
 *   /data/local/tmp/nokey_cfg.json
 *
 * JSON 顶层结构：
 *   {
 *     "commands": [ {"code":N,"name":"...","words":["..."]}, ... ],
 *     "toneUri": "file:///system/media/audio/ui/WaterDrop_preview.ogg",
 *     "successTexts": ["已成功", "好的，搞定！", ...],
 *     "hideRecents": false,
 *     "keepAlive": false,
 *     "silentMode": false,
 *     "skinUnlock": false,
 *     "bypassCheck": false
 *   }
 *
 * hook 端（com.miui.voiceassist 进程）在小爱渲染文本时读取该文件，
 * 用其中定义的词表覆盖 CommandMatcher 的默认词表、用自定义 URI 播提示音、
 * 从 successTexts 随机取一条作「已成功」屏幕文案。
 *
 * 文件路径选在 /data/local/tmp 的原因：
 *   - 设备已 root，UI 端可用 su 写入；
 *   - hook 运行于 Zygote fork 出的目标进程，天然具备读取该 root 属主文件的权限；
 *   - 避免跨进程直接读取另一个 app 私有 SharedPreferences 的权限问题。
 */
object NokeyConfig {

    /** 共享配置文件绝对路径 */
    const val CONFIG_FILE = "/data/local/tmp/nokey_cfg.json"

    /** 配置文件的命令字段名 */
    const val KEY_CODE = "code"
    const val KEY_NAME = "name"
    const val KEY_WORDS = "words"

    /** 配置文件的顶层字段名（新功能） */
    const val KEY_TONE_URI = "toneUri"
    const val KEY_SUCCESS_TEXTS = "successTexts"
    /** 「隐藏后台卡片」开关（档位 1：隐藏乘趣最近任务卡片） */
    const val KEY_HIDE_RECENTS = "hideRecents"
    /** 「进程保活」开关：开启后 hook 用 startForegroundService 启动乘趣 KeepAliveService 保活 */
    const val KEY_KEEP_ALIVE = "keepAlive"
    /** 「全静默执行」开关：开启后小爱静默执行指令，屏幕无任何文字、无提示音（连「已成功」气泡都不渲染） */
    const val KEY_SILENT_MODE = "silentMode"
    /** 「皮肤解锁」开关：开启后 hook 解锁乘趣全部皮肤（SkinItem 六个判权方法强制放行） */
    const val KEY_SKIN_UNLOCK = "skinUnlock"
    /** 「绕过校验」开关：开启后 hook 绕过签名校验与安全环境检测（AppConstants.compareNokeySignaturesSHA1 + BaseInspector.O00000o0） */
    const val KEY_BYPASS_CHECK = "bypassCheck"

    /** 完整的配置对象：各维度各自独立（分开保存的落盘基座） */
    data class NokeyCfg(
        val commands: List<CommandMatcher.Command>,
        val toneUri: String?,
        val successTexts: List<String>,
        /** 是否隐藏乘趣的最近任务卡片；true 时乘趣不出现在最近任务列表 */
        val hideRecents: Boolean = false,
        /** 是否启用进程保活（启动乘趣 KeepAliveService 前台服务）；true 时 hook 补启动 */
        val keepAlive: Boolean = false,
        /** 是否全静默执行（档位 C）；true 时连「已成功」气泡都不渲染、连提示音都不播，屏幕无任何反馈 */
        val silentMode: Boolean = false,
        /** 是否启用皮肤解锁（解锁乘趣全部皮肤）；true 时 hook 强制放行六个判权方法 */
        val skinUnlock: Boolean = false,
        /** 是否启用绕过校验（签名校验 + 安全环境检测）；true 时 hook 强制放行通过 */
        val bypassCheck: Boolean = false
    )

    /**
     * 输出完整各维度的 JSON 字符串（手写轻量序列化器，避免第三方 JSON 库）。
     * 所有字段总是写出；null/空则写空数组或空串，保证结构稳定。
     */
    fun toJson(
        commands: List<CommandMatcher.Command>,
        toneUri: String? = null,
        successTexts: List<String>? = null,
        hideRecents: Boolean? = null,
        keepAlive: Boolean? = null,
        silentMode: Boolean? = null,
        skinUnlock: Boolean? = null,
        bypassCheck: Boolean? = null
    ): String {
        val sb = StringBuilder()
        // commands
        sb.append("{\"commands\":[")
        commands.forEachIndexed { i, cmd ->
            sb.append("{\"")
                .append(KEY_CODE).append("\":").append(cmd.code)
                .append(",\"")
                .append(KEY_NAME).append("\":\"").append(esc(cmd.name))
                .append("\",\"")
                .append(KEY_WORDS).append("\":[")
            cmd.keywords.forEachIndexed { j, kw ->
                sb.append("\"").append(esc(kw)).append("\"")
                if (j < cmd.keywords.size - 1) sb.append(",")
            }
            sb.append("]}")
            if (i < commands.size - 1) sb.append(",")
        }
        sb.append("],")
        // toneUri
        sb.append("\"").append(KEY_TONE_URI).append("\":\"").append(esc(toneUri ?: "")).append("\",")
        // successTexts
        val texts = successTexts.orEmpty().filter { it.isNotBlank() }
        sb.append("\"").append(KEY_SUCCESS_TEXTS).append("\":[")
        texts.forEachIndexed { i, t -> sb.append("\"").append(esc(t)).append("\""); if (i < texts.size - 1) sb.append(",") }
        sb.append("],")
        // hideRecents
        sb.append("\"").append(KEY_HIDE_RECENTS).append("\":").append(hideRecents ?: false)
        sb.append(",")
        // keepAlive
        sb.append("\"").append(KEY_KEEP_ALIVE).append("\":").append(keepAlive ?: false)
        sb.append(",")
        // silentMode
        sb.append("\"").append(KEY_SILENT_MODE).append("\":").append(silentMode ?: false)
        sb.append(",")
        // skinUnlock
        sb.append("\"").append(KEY_SKIN_UNLOCK).append("\":").append(skinUnlock ?: false)
        sb.append(",")
        // bypassCheck
        sb.append("\"").append(KEY_BYPASS_CHECK).append("\":").append(bypassCheck ?: false)
        sb.append("}")
        return sb.toString()
    }

    /** 转义 JSON 字符串中的控制字符 */
    private fun esc(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * 解析 [toJson] 产生的配置字符串到命令列表。
     * 解析失败或结构不合法时返回 null（调用方应回退默认词表）。
     */
    fun fromJson(json: String): List<CommandMatcher.Command>? {
        // 命令数组为空视为「未配置」，返回 null 让调用方回退默认词表
        return fromJsonFull(json)?.commands?.ifEmpty { null }
    }

    /**
     * 解析 [toJson] 产生的完整配置（命令 + 提示音 URI + 成功文案）。
     *
     * 兼容旧配置：toneUri / successTexts 顶层字段缺失时不报错，
     * 分别回退 null 与空列表。
     */
    fun fromJsonFull(json: String): NokeyCfg? {
        return try {
            val cmds = ArrayList<CommandMatcher.Command>()
            var toneUri: String? = null
            val successTexts = ArrayList<String>()
            var hideRecents = false
            var keepAlive = false
            var silentMode = false
            var skinUnlock = false
            var bypassCheck = false
            var idx = 0

            // 按顶层 key 迭代（commands / toneUri / successTexts）
            idx = json.indexOf('{')
            if (idx < 0) return null
            idx++
            while (idx < json.length) {
                while (idx < json.length && (json[idx].isWhitespace() || json[idx] == ',')) idx++
                if (idx >= json.length || json[idx] == '}') break
                val key = readJsonString(json, idx) ?: return null
                idx = json.indexOf(':', idx)
                if (idx < 0) return null
                idx++
                when (key) {
                    KEY_CODE, KEY_NAME, KEY_WORDS -> return null // 仅接受顶层字段
                    KEY_TONE_URI -> {
                        val v = readJsonString(json, idx) ?: ""
                        toneUri = v.ifBlank { null }
                        idx = json.indexOfAny(charArrayOf(',', '}'), idx)
                        if (idx >= 0 && json[idx] == '}') { idx-- } // 由外层 +1 处理
                    }
                    "commands" -> {
                        // 复用命令列表解析；结束索引指向 commands 数组真正的 ']'
                        val cmdsIdx = json.indexOf('[', json.indexOf("\"commands\""))
                        val parsed = parseCommandsBlock(json, cmdsIdx)
                        if (parsed != null) {
                            cmds.addAll(parsed.first)
                            idx = parsed.second
                        } else {
                            return null
                        }
                    }
                    KEY_SUCCESS_TEXTS -> {
                        var arrStart = json.indexOf('[', idx)
                        if (arrStart < 0) return null
                        arrStart++
                        while (arrStart < json.length && json[arrStart] != ']') {
                            while (arrStart < json.length && (json[arrStart].isWhitespace() || json[arrStart] == ',')) arrStart++
                            if (arrStart >= json.length || json[arrStart] == ']') break
                            val t = readJsonString(json, arrStart) ?: break
                            if (t.isNotBlank()) successTexts.add(t)
                            arrStart = json.indexOfAny(charArrayOf(',', ']'), arrStart)
                        }
                        idx = json.indexOfAny(charArrayOf(',', '}'), arrStart)
                    }
                    KEY_HIDE_RECENTS -> {
                        // 布尔字段：跳过空白扫到 true/false
                        var b = idx
                        while (b < json.length && (json[b].isWhitespace() || json[b] == ':')) b++
                        hideRecents = if (json.startsWith("true", b)) true
                        else if (json.startsWith("false", b)) false
                        else false
                        idx = json.indexOfAny(charArrayOf(',', '}'), b)
                    }
                    KEY_KEEP_ALIVE -> {
                        // 布尔字段：跳过空白扫到 true/false
                        var k = idx
                        while (k < json.length && (json[k].isWhitespace() || json[k] == ':')) k++
                        keepAlive = if (json.startsWith("true", k)) true
                        else if (json.startsWith("false", k)) false
                        else false
                        idx = json.indexOfAny(charArrayOf(',', '}'), k)
                    }
                    KEY_SILENT_MODE -> {
                        // 布尔字段：跳过空白扫到 true/false
                        var sm = idx
                        while (sm < json.length && (json[sm].isWhitespace() || json[sm] == ':')) sm++
                        silentMode = if (json.startsWith("true", sm)) true
                        else if (json.startsWith("false", sm)) false
                        else false
                        idx = json.indexOfAny(charArrayOf(',', '}'), sm)
                    }
                    KEY_SKIN_UNLOCK -> {
                        // 布尔字段：跳过空白扫到 true/false
                        var su = idx
                        while (su < json.length && (json[su].isWhitespace() || json[su] == ':')) su++
                        skinUnlock = if (json.startsWith("true", su)) true
                        else if (json.startsWith("false", su)) false
                        else false
                        idx = json.indexOfAny(charArrayOf(',', '}'), su)
                    }
                    KEY_BYPASS_CHECK -> {
                        // 布尔字段：跳过空白扫到 true/false
                        var bc = idx
                        while (bc < json.length && (json[bc].isWhitespace() || json[bc] == ':')) bc++
                        bypassCheck = if (json.startsWith("true", bc)) true
                        else if (json.startsWith("false", bc)) false
                        else false
                        idx = json.indexOfAny(charArrayOf(',', '}'), bc)
                    }
                    else -> {
                        // 未知顶层字段：跳到下一个 ","
                        idx = json.indexOfAny(charArrayOf(',', '}'), idx)
                    }
                }
                if (idx >= 0) idx++
            }
            NokeyCfg(cmds, toneUri, successTexts, hideRecents, keepAlive, silentMode, skinUnlock, bypassCheck)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 从 [json] 的 [arrStart]（指向 commands 数组的 '['）解析命令列表。
     *
     * @return Pair(命令列表, commands 数组结束的 ']' 索引)；结构非法/解析失败返回 null。
     * 结束索引用于让调用方把外层游标精确推进到数组末尾，避免用 indexOf(']') 误命中
     * 命令内 words 数组的 ']'。
     */
    private fun parseCommandsBlock(json: String, arrStart: Int): Pair<List<CommandMatcher.Command>, Int>? {
        return try {
            val cmds = ArrayList<CommandMatcher.Command>()
            if (arrStart < 0) return null
            var idx = arrStart + 1
            var arrayEnd = idx
            while (idx < json.length) {
                while (idx < json.length && (json[idx].isWhitespace() || json[idx] == ',' || json[idx] == '{')) idx++
                if (idx >= json.length || json[idx] == ']') { arrayEnd = idx; break }

                var code = 0
                var name = ""
                val words = ArrayList<String>()

                while (idx < json.length && json[idx] != '}') {
                    while (idx < json.length && (json[idx].isWhitespace() || json[idx] == ',')) idx++
                    if (idx >= json.length || json[idx] == '}') break
                    val key = readJsonString(json, idx) ?: return null
                    idx = json.indexOf(':', idx) + 1
                    when (key) {
                        KEY_CODE -> { code = readJsonInt(json, idx); idx = json.indexOfAny(charArrayOf(',', '}'), idx) }
                        KEY_NAME -> {
                            val v = readJsonString(json, idx)
                            if (v == null) return null
                            name = v
                            idx = json.indexOfAny(charArrayOf(',', '}'), idx)
                        }
                        KEY_WORDS -> {
                            var ws = json.indexOf('[', idx)
                            if (ws < 0) return null
                            ws++
                            while (ws < json.length && json[ws] != ']') {
                                while (ws < json.length && (json[ws].isWhitespace() || json[ws] == ',')) ws++
                                if (ws >= json.length || json[ws] == ']') break
                                val w = readJsonString(json, ws) ?: break
                                words.add(w)
                                ws = json.indexOfAny(charArrayOf(',', ']'), ws)
                            }
                            idx = json.indexOfAny(charArrayOf(',', '}'), ws)
                        }
                        else -> return null
                    }
                }
                if (idx >= json.length) break
                idx++
                if (code > 0 && name.isNotEmpty()) {
                    cmds.add(CommandMatcher.Command(code, name, words.filter { it.isNotBlank() }))
                }
                arrayEnd = idx
                if (idx < json.length && json[idx] == ']') { arrayEnd = idx; break }
            }
            Pair(cmds, arrayEnd)
        } catch (t: Throwable) {
            null
        }
    }

    /** 从 [str] 的 [from] 位置读取一个 JSON 字符串（不含引号），失败返回 null */
    private fun readJsonString(str: String, from: Int): String? {
        val q = str.indexOf('"', from)
        if (q < 0) return null
        val sb = StringBuilder()
        var i = q + 1
        while (i < str.length) {
            val c = str[i]
            if (c == '\\' && i + 1 < str.length) {
                val n = str[i + 1]
                when (n) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return null
    }

    /** 从 [str] 的 [from] 位置读取一个整数 */
    private fun readJsonInt(str: String, from: Int): Int {
        var i = from
        while (i < str.length && (str[i] == ':' || str[i].isWhitespace())) i++
        val sb = StringBuilder()
        while (i < str.length && (str[i].isDigit() || str[i] == '-')) {
            sb.append(str[i]); i++
        }
        return sb.toString().toIntOrNull() ?: 0
    }

    // ============================================================
    // 文件读写（hook 端使用）
    // ============================================================

    /** 上一次读取到的文件修改时间，避免高频 IO */
    @Volatile private var lastMtime = 0L
    @Volatile private var cachedFull: NokeyCfg? = null

    /**
     * 读取配置文件的完整内容（命令 + 提示音 URI + 成功文案）。
     * 文件不存在/不可读/解析失败时返回 null。
     *
     * 缓存按文件 mtime 按需刷新：UI 端保存后文件 mtime 变化，hook 端下一次调用
     * 便会重读，无需重启小爱进程。高频 setText 回调不会反复读文件。
     */
    fun loadFull(force: Boolean = false): NokeyCfg? {
        return try {
            val f = File(CONFIG_FILE)
            // 不能依赖 f.canRead()：/data/local/tmp、/data/local 对 other 无 r 位仅 x 遍历位，
            // 小爱进程(uid 10175)能凭目录 x 权限直接 open 该 644 文件，但 canRead() 会误判。
            // 故只判存在，用 try-catch readText。
            if (!f.exists()) return null
            val mtime = f.lastModified()
            if (!force && cachedFull != null && mtime == lastMtime) {
                return cachedFull
            }
            val json = f.readText()
            if (json.isBlank()) return null
            val parsed = fromJsonFull(json) ?: return null
            lastMtime = mtime
            cachedFull = parsed
            parsed
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 读取自定义词表；文件不存在/无变化/无命令时返回 null（调用方用默认词表）。
     */
    fun loadCustom(force: Boolean = false): List<CommandMatcher.Command>? {
        val full = loadFull(force)
        return full?.commands?.ifEmpty { null }
    }

    /** 读取自定义提示音 URI；未配置/无效时返回 null（调用方回退系统默认通知音） */
    fun loadToneUri(force: Boolean = false): String? {
        val full = loadFull(force)
        return full?.toneUri?.takeIf { it.isNotBlank() }
    }

    /** 读取自定义成功文案列表；未配置时返回空列表（调用方回退「已成功」） */
    fun loadSuccessTexts(force: Boolean = false): List<String> {
        val full = loadFull(force)
        return full?.successTexts ?: emptyList()
    }

    /** 读取「隐藏后台卡片」开关；未配置/解析失败时回退 false（不隐藏） */
    fun loadHideRecents(force: Boolean = false): Boolean {
        val full = loadFull(force)
        return full?.hideRecents ?: false
    }

    /** 读取「进程保活」开关；未配置/解析失败时回退 false（不保活） */
    fun loadKeepAlive(force: Boolean = false): Boolean {
        val full = loadFull(force)
        return full?.keepAlive ?: false
    }

    /** 读取「全静默执行」开关；未配置/解析失败时回退 false（不静默，走正常「已成功」反馈） */
    fun loadSilentMode(force: Boolean = false): Boolean {
        val full = loadFull(force)
        return full?.silentMode ?: false
    }

    /** 读取「皮肤解锁」开关；未配置/解析失败时回退 false（不启用皮肤解锁） */
    fun loadSkinUnlock(force: Boolean = false): Boolean {
        val full = loadFull(force)
        return full?.skinUnlock ?: false
    }

    /** 读取「绕过校验」开关；未配置/解析失败时回退 false（不启用绕过校验） */
    fun loadBypassCheck(force: Boolean = false): Boolean {
        val full = loadFull(force)
        return full?.bypassCheck ?: false
    }

    /**
     * 将配置序列化后写回文件（root 场景由 UI 端以提权方式调用）。
     *
     * 支持部分字段写入：传入非空的 commands/toneUri/successTexts 时只更新对应维度，
     * 其余维度从当前配置读出再合并写回，保证先读后写语义、不覆盖其它已保存的设置。
     * 三者均为 null 时保持原配置不动（返回 true）。
     */
    fun writeTo(
        commands: List<CommandMatcher.Command>? = null,
        toneUri: String? = null,
        successTexts: List<String>? = null,
        hideRecents: Boolean? = null,
        keepAlive: Boolean? = null,
        silentMode: Boolean? = null,
        skinUnlock: Boolean? = null,
        bypassCheck: Boolean? = null,
        path: String = CONFIG_FILE
    ): Boolean {
        return try {
            val existing = loadFull(force = true)
            val merged = NokeyCfg(
                commands = commands ?: existing?.commands.orEmpty(),
                toneUri = toneUri ?: existing?.toneUri,
                successTexts = successTexts ?: existing?.successTexts.orEmpty(),
                hideRecents = hideRecents ?: (existing?.hideRecents ?: false),
                keepAlive = keepAlive ?: (existing?.keepAlive ?: false),
                silentMode = silentMode ?: (existing?.silentMode ?: false),
                skinUnlock = skinUnlock ?: (existing?.skinUnlock ?: false),
                bypassCheck = bypassCheck ?: (existing?.bypassCheck ?: false)
            )
            File(path).writeText(
                toJson(merged.commands, merged.toneUri, merged.successTexts, merged.hideRecents, merged.keepAlive, merged.silentMode, merged.skinUnlock, merged.bypassCheck)
            )
            // 写入成功后刷新缓存，避免 hook 端/UI 端读到旧值
            lastMtime = File(path).lastModified()
            cachedFull = null
            true
        } catch (t: IOException) {
            false
        } catch (t: SecurityException) {
            false
        } catch (t: Throwable) {
            false
        }
    }
}
