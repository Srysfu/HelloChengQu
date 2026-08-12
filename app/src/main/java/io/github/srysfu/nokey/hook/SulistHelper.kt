package io.github.srysfu.nokey.hook

import de.robv.android.xposed.XposedBridge
import java.util.concurrent.TimeUnit

/**
 * su 白名单（sulist）自动配置工具。
 *
 * 背景：Magisk/Kitsune 的 sulist 模式（`magisk --sqlite "SELECT ... FROM sulist"`）中，判断是否向
 * 某个进程注入 su 的依据是 sulist 表（位于 /data/adb/magisk.db）内精确匹配 `package_name + process`。
 * 只有命中白名单的进程，其 namespace 才会在 boot 时被 magiskd 注入 su（`* Mount MagiskSU`），
 * 从而进程内 `exec su` 可无弹窗静默获得 root（本模块保活等能力依赖此机制）。
 *
 * 本工具在模块 UI 首次打开时自动检测并补齐小爱（com.miui.voiceassist）与乘趣（com.ingeek.nokey）
 * 的 sulist 条目，免去用户在 Magisk 设置里手动勾选，并额外覆盖子进程（见下）。
 *
 * sulist 表 `process` 字段语义：
 *   - 非空：仅精确匹配该 process name（如主进程 = 包名）；
 *   - 空字符串 ""：匹配该 package 名下的【所有进程】（含 `:xxx` 等子进程）。
 * 因此对每个应用写入两条：① 主进程精确条目；② 空 process 通配条目，兜底子进程。
 *
 * 硬约束：sulist 修改后须 magiskd 重新初始化（【重启设备】）才加载进注入白名单，
 * 仅重启目标 app 进程无效。故所有写入成功路径都应提示用户重启设备。
 *
 * root 执行范式复用于 VoiceAssistHook.launchNokeyViaRoot：
 *   Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", cmd)) + waitFor 超时保护。
 */
object SulistHelper {

    private const val TAG = "NokeySulist"

    /** 需要写入 sulist 的应用集合：packageName 及其显示名（仅用于日志） */
    data class AppEntry(val packageName: String, val label: String)

    /** 需要纳入 sulist 白名单的应用（小爱为保活宿主，乘趣为保活对象） */
    val TARGET_APPS: List<AppEntry> = listOf(
        AppEntry("com.miui.voiceassist", "小爱同学"),
        AppEntry("com.ingeek.nokey", "乘趣")
    )

    /** 命令执行超时（秒）：与 VoiceAssistHook.launchNokeyViaRoot 一致 */
    private const val EXEC_TIMEOUT_SECONDS = 5L

    /**
     * 查询 sulist 表中是否已存在指定 packageName+process 条目。
     * 返回 true 表示已存在；false 表示不存在或查询失败（保守视为需要写入）。
     */
    fun isEntryPresent(packageName: String, process: String): Boolean {
        val escapedProcess = process.replace("'", "''")
        val sql =
            "SELECT package_name FROM sulist WHERE package_name='$packageName' AND process='$escapedProcess';"
        val result = execSql(sql) ?: return false
        return result.isNotBlank()
    }

    /**
     * 检测目标应用的所有 sulist 条目（主进程 + 空 process 通配）是否已齐备。
     * @return 缺失的条目标记列表，空表示已全部就位。
     */
    fun missingEntries(): List<String> {
        val missing = mutableListOf<String>()
        for (app in TARGET_APPS) {
            if (!isEntryPresent(app.packageName, app.packageName)) {
                missing += "${app.label}主进程(${app.packageName})"
            }
            if (!isEntryPresent(app.packageName, "")) {
                missing += "${app.label}子进程通配(${app.packageName}:*)"
            }
        }
        return missing
    }

    /**
     * 自动补齐 sulist 白名单。
     * 对每个应用写入主进程精确条目 + 空 process 通配条目（覆盖子进程）。
     * 已存在的条目跳过（不重复插入）。
     * @return 本轮实际新增写入的条目数
     */
    fun ensureEntries(): Int {
        var added = 0
        for (app in TARGET_APPS) {
            // 主进程精确条目
            if (!isEntryPresent(app.packageName, app.packageName)) {
                if (insertEntry(app.packageName, app.packageName)) added++
            }
            // 子进程通配条目（process 为空串，匹配所有子进程）
            if (!isEntryPresent(app.packageName, "")) {
                if (insertEntry(app.packageName, "")) added++
            }
        }
        return added
    }

    /**
     * 向 sulist 表插入一条 package_name + process 记录。
     * process 传空字符串 "" 表示匹配该包名下所有进程（覆盖子进程）。
     * 需 root 权限执行（模块进程自身在白名单内、持有 root 能力）。
     */
    fun insertEntry(packageName: String, process: String): Boolean {
        val escapedProcess = process.replace("'", "''")
        val sql =
            "INSERT INTO sulist (package_name, process) VALUES ('$packageName', '$escapedProcess');"
        val exitCode = execSqlCode(sql)
        if (exitCode == 0) {
            XposedBridge.log("[$TAG] 已写入 sulist: ${displayEntry(packageName, process)}")
            return true
        }
        XposedBridge.log("[$TAG] sulist 写入失败(code=$exitCode): ${displayEntry(packageName, process)}")
        return false
    }

    /** 日志用条目展示：空 process 显示为子进程通配 */
    private fun displayEntry(packageName: String, process: String): String {
        return if (process.isEmpty()) "$packageName(:* 子进程通配)" else "$packageName/$process"
    }

    /** 执行一条 magisk --sqlite 查询类语句，返回结果文本（多行拼接）；失败/null 返回 null */
    private fun execSql(sql: String): String? {
        return try {
            val proc = Runtime.getRuntime()
                .exec(arrayOf("/system/bin/su", "-c", "magisk --sqlite \"$sql\""))
            try {
                if (proc.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    val out = proc.inputStream.bufferedReader().readText().trim()
                    proc.inputStream.close()
                    proc.errorStream.close()
                    out
                } else {
                    proc.destroyForcibly()
                    proc.inputStream.close()
                    proc.errorStream.close()
                    XposedBridge.log("[$TAG] sql 查询超时: $sql")
                    null
                }
            } catch (ie: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
                null
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] sql 查询异常: ${t.message}")
            null
        }
    }

    /** 执行一条 magisk --sqlite 写语句，返回 exit code；异常返回 -1 */
    private fun execSqlCode(sql: String): Int {
        return try {
            val proc = Runtime.getRuntime()
                .exec(arrayOf("/system/bin/su", "-c", "magisk --sqlite \"$sql\""))
            try {
                if (proc.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    val code = proc.exitValue()
                    proc.inputStream.close()
                    proc.errorStream.close()
                    code
                } else {
                    proc.destroyForcibly()
                    proc.inputStream.close()
                    proc.errorStream.close()
                    XposedBridge.log("[$TAG] sql 写入超时: $sql")
                    -1
                }
            } catch (ie: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
                -1
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] sql 写入异常: ${t.message}")
            -1
        }
    }

    /** 检查设备是否具备 root 执行能力（su 可用） */
    fun isRootAvailable(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", "id"))
            try {
                if (proc.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    val out = proc.inputStream.bufferedReader().readText()
                    proc.inputStream.close()
                    proc.errorStream.close()
                    out.contains("uid=0")
                } else {
                    proc.destroyForcibly()
                    proc.inputStream.close()
                    proc.errorStream.close()
                    false
                }
            } catch (ie: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
                false
            }
        } catch (t: Throwable) {
            false
        }
    }
}
