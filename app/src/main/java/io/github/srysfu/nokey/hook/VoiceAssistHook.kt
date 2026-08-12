package io.github.srysfu.nokey.hook

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * 目标进程：com.miui.voiceassist（小爱同学）
 *
 * 路径 A1：hook 系统方法 android.widget.TextView.setText。
 * 当小爱界面渲染出含车辆指令口令的文本时（无论来自用户复述气泡还是系统回复），
 * 判定"识别+语义解析已完成"，从小爱进程发送 NOKEY_CMD 广播给 com.ingeek.nokey。
 *
 * 路径 A2（回应控制）：口令命中并成功发送广播后，进入一个短"响应窗口"。
 * 在窗口内：
 *   - 拦截小爱随后渲染出来的【回应文本】（文字通道，TextView.setText 全重载）
 *   - 静音小爱随后播报的【语音回应】（语音通道，AudioTrack.write 拦截）
 * 将两处原本"乱七八糟"的回应统一替换/静音为「已成功」反馈（系统提示音 + 屏幕文字）。
 * 注意：设备无标准 TTS 引擎，语音反馈改为 MediaPlayer 播系统通知提示音（方案 B），
 * 语义由屏幕"已成功"文字补充（见 playSuccessTone()）。
 *
 * 诊断模式：替换时打印 TextView 身份（类名 / hashCode / isShown / 父层级 / 调用栈），
 * 用于定位用户真正可见的那个气泡 TextView（此前发现被替换文本并非可见气泡来源）。
 */
object VoiceAssistHook {

    private const val TAG = "NokeyVoice"

    /** 防抖窗口：同一命令码 N 毫秒内不重复发送，避免系统重复渲染时连发 */
    private const val DEBOUNCE_MS = 4000L

    /** 响应窗口：口令广播发出后，在此窗口内把小爱对该指令的回应替换为「已成功」 */
    private const val RESPONSE_WINDOW_MS = 6000L

    /**
     * 跨进程响应窗口同步广播 Action。
     *
     * 根因：responseWindowUntil 是进程内静态单例。dumpsys 证实小爱 LLM 语音的
     * AudioTrack 播报实际发生在主进程（5 个池化 AudioTrack 全在主进程 23012），
     * 而非 :core 进程。但为防御进程边界差异，仍保留跨进程广播同步：
     * 口令命中开启响应窗口时，由主进程通过此广播通知同 UID 的所有进程
     * （含 :core）同步本进程的 responseWindowUntil，使各进程的 AudioTrack
     * 静音 hook 都能命中。窗口各自用本进程时钟计算截止（now + RESPONSE_WINDOW_MS），
     * 6 秒后自动失效，无需显式关窗广播。
     */
    private const val ACTION_RESPONSE_WINDOW = "io.github.srysfu.nokey.hook.NOKEY_RESPONSE_WINDOW"

    /**
     * 启动阶段防护窗：进程启动后的前 N 毫秒内，小爱会重建/恢复历史会话，
     * 其中可能包含此前触发过口令的历史气泡（如「狗狗开门」）。
     * 若此时命中关键词并发送广播，会在用户毫无语音指令的情况下重复执行一次车辆操作。
     * 该窗内一律不解析口令、不开响应窗口、不发广播。
     *
     * 注意：历史会话恢复是【异步分批渲染】，持续时长远超启动后 2 秒，
     * 单靠固定短窗口无法全覆盖（此前实测 history 恢复在启动后 ~14s 仍命中）。
     * 因此这里保留一个【语音输入门禁】作为根治手段：只有真实语音识别文本
     * 进入 UI（hook lg0.h/lg0.i 的 delayShowAsr* 入口）后，才开放口令解析。
     * 无语音信号时（纯历史恢复/界面重绘）一律不解析口令。
     */
    private const val STARTUP_GUARD_MS = 2000L

    /**
     * 加强启动防护窗（兜底）：当语音输入门禁 hook 因版本/混淆等原因未能生效、
     * lastSpeechAsrTime 始终为 0（无语音信号可依赖）时，退化为固定长窗口，
     * 在启动后此窗内不解析口令，最大程度避免历史恢复误触发。
     */
    private const val STRONG_STARTUP_GUARD_MS = 20000L

    /**
     * 语音输入门禁窗口：一旦检测到真实语音识别文本（lg0.h/lg0.i 的 delayShowAsr* 被调用），
     * 在此窗口内允许口令解析；超出窗口则视为"无语音、纯渲染"，不再解析口令。
     * 窗口需覆盖【识别文本显示 → 对话卡片渲染】的间隔，取 6 秒较为稳妥。
     */
    private const val SPEECH_GATE_MS = 6000L

    /**
     * 外部宿主保活（阶段四）：
     *
     * 现状：乘趣进程被杀（kill -9 / force-stop / 滑动移除）后，AMS 不触发 START_STICKY，
     * 模块自身代码随进程消失，没有任何外部触发源主动拉起 → 不能马上拉起。
     * 方案：以小爱进程（常驻、已被本模块 hook）为外部宿主，周期性检测乘趣进程存活，
     * 若进程死亡则用显式 startForegroundService 拉起（已实测：显式 component 拉起可行）。
     *
     * 注意：小爱是普通应用 UID，拉起受限可能不如 system shell。但滑动移除/ kill -9（非 force-stop）
     * 不置 stopped，普通 UID 显式 startForegroundService 通常可行；force-stop 场景会置 stopped=true，
     * 普通 UID 拉起可能被系统拦截，此为已知边界。
     */
    /** 外部宿主保活检测周期（毫秒）：乘趣被杀后最长约在此周期内被拉起 */
    private const val HOST_POLL_MS = 8000L
    /** 连续两次检测之间，成功拉起后进入冷却，避免反复尝试刷屏 */
    private const val HOST_REL_COOLDOWN_MS = 60000L
    /** 检测后发现进程死亡、尝试拉起的最小间隔（毫秒），避免高频起拉 */
    private const val HOST_MIN_RETRY_MS = 30000L

    /**
     * 乘趣进程冷启动"动态接收器就绪"窗口（毫秒）。
     *
     * 方案B实时拉起后，乘趣进程从被杀到 Application.attach + 动态广播接收器（ACTION_NOKEY_CMD）
     * 注册完成存在 ~1~1.5s 的冷启动时延。若口令广播在这个窗口内发出，会因接收器尚未注册而丢失
     * （Android 静默丢弃，表现为"第一次唤醒拉起了进程但广播没执行，要喊第二次才行"）。
     * 该窗口内广播发出后需延迟重发，待接收器就绪后再投递一次。
     */
    private const val NOKEY_RECEIVER_READY_MS = 2500L


    /**
     * 回应文本长度上限：小爱大模型回答多为流式增量输出，正文可能远超以往阈值。
     * 这里适当放宽作为护栏，避免误伤正常对话。
     */
    private const val MAX_RESPONSE_LEN = 512

    /** 上一次触发的命令码与时间戳（volatile 保证 hook 回调多线程安全读） */
    @Volatile private var lastCommand = -1

    /**
     * 上一次【成功发送广播】的命令码与时间戳（volatile 保证多线程安全读）。
     * 防抖判定基于"真正发出去的那次"，而不是"门禁通过、准备发送"的时刻——
     * 若用错误时间源做防抖，会导致每次调用 sendCommandOnly 时 now-lastTriggerTime≈0，
     * 防抖恒命中、广播永远发不出去。故拆分为独立状态，仅在 sendBroadcast 成功后才更新。
     */
    @Volatile private var lastSentCommand = -1
    @Volatile private var lastSentTime = 0L

    /**
     * 待重发的命令码（冷启动窗口内广播丢失补偿）。
     *
     * 乘趣进程刚被实时拉起、动态接收器尚未注册时发出的 NOKEY_CMD 广播会被静默丢弃。
     * sendCommandOnly 检测到广播发生在"乘趣冷启动就绪窗口"内时，会安排一次延迟重发。
     * 用此标志去重：同一命令只排队一次，避免小爱连续渲染多个历史气泡时重复排队刷屏。
     * -1 表示当前无待重发命令。
     */
    @Volatile private var pendingRetryCommand = -1

    /** 待重发命令最近一次排队的时间戳（elapsedRealtime），用于合并/去抖重复安排 */
    @Volatile private var pendingRetryAt = 0L

    // ===== ACK 确认等待（根治锁屏/灭屏下广播静默丢失） =====
    // 根因：锁屏/灭屏后 MIUI/Android 将乘趣进程冻结进 cgroup frozen，isAppProcessAlive
    // 只看 runningAppProcesses 仍判定"存活"，ensureNokeyAlive 不拉起、不补投，广播投到
    // frozen 进程被系统挂起不执行 → 静默丢失（解锁才正常）。故从"盲信进程存活"升级为
    // "发送后确认接收端真的执行了命令"：发送端安排确认窗口，未收 ACK 则 root 唤醒 + 重发。
    /** ACK 确认等待的调度 Handler（跑主进程主线程，postDelayed 检查窗口） */
    private val ackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 待确认命令码（-1 = 当前无待确认命令） */
    @Volatile private var pendingAckCommand = -1
    /** 确认等待起始时间戳（wallClock，用于窗口内判定+日志） */
    @Volatile private var ackWaitStart = 0L
    /** 已进行的 ACK 重试轮次（0 = 初始等待尚未重试） */
    @Volatile private var ackRetryRound = 0
    /** ACK 等待窗口自适应递增序列（ms）：覆盖冷启动慢 / 空闲深冻结场景 */
    private val ACK_WAIT_WINDOWS = longArrayOf(1200L, 2000L, 3000L)
    /** 拉起冷却：相邻两次 ensureNokeyAlive（root 唤醒）的最短间隔，防高频拉起 */
    private const val ACK_LAUNCH_COOLDOWN_MS = 15000L
    /** 防重入：同一时刻只允许一轮 ACK 确认/重试流程在跑 */
    @Volatile private var ackRetryInFlight = false
    /** 上次发起 ACK 驱动拉起的时刻（用于冷却限流） */
    @Volatile private var ackLastLaunchAt = 0L

    /** 响应窗口截止时间戳（0 表示不在窗口内） */
    @Volatile private var responseWindowUntil = 0L

    /** 本次触发口令的原始渲染文本，用于排除匹配气泡本身 */
    @Volatile private var lastTriggerText = ""

    /**
     * 主动刷新防重入标志：
     * afterHook 阶段改 param.args[0] 已晚于 setText 实际执行，UI 不会变化。
     * 因此 for 可见 TextView 需在改参后主动 setText("已成功") 强制刷新 UI。
     * 该主动 setText 会再次进入本 hook，用此标志确保只执行一次，避免日志刷屏/无限递归。
     */
    @Volatile private var forceFlushing = false

    /**
     * 语音通道静音方案（MIX 混合 TTS 引擎）：
     *
     * 反编译确认小爱 LLM 语音播报走「米家 MIX 语音引擎」——
     *  服务端合成音频 → 回调 tl.f.onTtsData / tl.i.onPcmData 接收 PCM/音频数据
     *  → 最终经 android.media.AudioTrack 本地播放（gp.a.onPlayStart(AudioTrack) 佐证）。
     * 该管线不经过标准 android.speech.tts.TextToSpeech.speak（故 speak hook 零命中），
     * 且实际播放类为匿名内部类无法用 grep 定位。
     *
     * 因此采用「读窗口内拦截 AudioTrack 播放 + playSuccessTone() 播系统提示音」：
     *  1) hook AudioTrack.play() / write()：响应窗口内 setVolume(0f) / 掐断 PCM 静音小爱原文；
     *     记录被静音实例，stop() 时恢复。
     *  2) 口令命中开启响应窗口时，模块播系统通知提示音作「已成功」反馈（方案 B，
     *     设备无标准 TTS 引擎，见 playSuccessTone()）。
     *
     * Android framework 的 AudioTrack 不随 APK 混淆，是稳定可靠的拦截点。
     */
    private val mutedAudioTracks = ConcurrentHashMap.newKeySet<AudioTrack>()
    /**
     * 本进程内见过的所有 AudioTrack 实例（构造/play/start 时登记），供「开窗时的全量清扫」用。
     *
     * 根因：小爱 LLM 语音的 AudioTrack 是启动时预创建的池化实例，MIX 引擎在 ASR 识别后
     * 立即 start() 灌 PCM 播报——这一时点可能【先于】我们的口令 setText 触发点。而既有
     * write()/start()/play() hook 的静音只在 inResponseWindow() 之后才生效，导致窗口开启
     * 前已 start、已写入开头若干 PCM 块的播报漏出（用户反馈"语音回复一点点然后才静音"）。
     *
     * 修复：口令命中开启响应窗口的同一时刻，遍历本集合、把所有处于 PLAYSTATE_PLAYING 的
     * 实例直接 setVolume(0f) 压静音——Android 音量在混音段生效，能掐掉已进入硬件缓冲的
     * 开头碎片；已被压静的实例进 mutedAudioTracks，由 stop/release hook 统一恢复。集合是
     * Set，同一实例只登记一次，长期运行无内存膨胀（池化实例数量极少、稳定）。
     */
    private val allAudioTracks = ConcurrentHashMap.newKeySet<AudioTrack>()
    /** write() 拦截计数（仅用于节流日志，避免刷屏） */
    private var mutedWriteCount = 0

    /**
     * 模块自身播报（系统提示音）豁免标志。
     *
     * 背景：设备上无标准 Android TTS 引擎（tts_default_synth 为 null），小爱靠 native 灌 PCM
     * 走 AudioTrack 播报。因此放弃「模块 TTS 播『已成功』」，改用 MediaPlayer 播系统提示音。
     * 但模块自身播出的提示音同样会经过 AudioTrack.write 被 hookAudioMute 在窗口内掐断，
     * 这里用该标志放行模块自己的播报（播放前置 true、结束恢复 false），避免自家声音被自己静音。
     * 提示音极短（<1s），豁免窗口小，不会误放行小爱原文播报。
     */
    @Volatile private var ownSoundActive = false


    /** 是否已经 hook 过头（每个进程只需挂载一次） */
    @Volatile private var hooked = false

    /** 进程内 hook 挂载完成的时间戳，用于启动阶段防护窗判定 */
    @Volatile private var hookTimestamp = 0L

    /** 外部宿主保活是否启用：仅小爱主进程启用，避免主进程与 :core 竞争拉起 */
    @Volatile private var hostEnabled = false
    /** 外部宿主保活定时器是否已启动（防重入） */
    @Volatile private var hostScheduled = false
    /** 最近一次成功拉起源的时间戳（用于最低重试间隔限流） */
    @Volatile private var hostLastRelaunchTime = 0L
    /** 外部宿主保活日志节流：上次打"进程死"日志的时间 */
    @Volatile private var hostLastLogTime = 0L

    /**
     * 最近一次触发"真实语音识别文本进入 UI"的时间戳（monotonic now）。
     * 由 hookSpeechAsrGate() 在 lg0.h/lg0.i 的 delayShowAsr* 被调用时刷新；
     * 0 表示从未收到真实语音信号（此时退化为 STRONG_STARTUP_GUARD_MS 兜底门禁）。
     */
    @Volatile private var lastSpeechAsrTime = 0L

    /**
     * 在小爱进程中挂载 hook。
     * 由 MainHook 在识别到包名后调用。
     */
    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (hooked) return
        hooked = true
        hookTimestamp = System.currentTimeMillis()
        XposedBridge.log("[$TAG] 小爱进程 hook 挂载开始：$PACKAGE")

        // 载入用户在配置界面自定义的唤醒词；文件缺失时 loadCustom 返回 null，
        // CommandMatcher.currentCommands 维持默认词表兜底。
        try {
            NokeyConfig.loadCustom(force = true)?.let { custom ->
                CommandMatcher.currentCommands = custom
                XposedBridge.log("[$TAG] 已载入自定义词表：${custom.size} 条命令")
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 加载自定义词表失败（沿用默认词表）: ${t.message}")
        }

        try {
            // ===== 文字通道：hookAllMethods(TextView, "setText") 覆盖所有重载 =====
            // 覆盖范围：setText(CharSequence)、setText(CharSequence, BufferType)、
            //          setText(int)、setText(int, BufferType)、以及其他子类重载。
            XposedBridge.hookAllMethods(
                TextView::class.java,
                "setText",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 按需刷新词表：配置文件 mtime 有变化时（用户在 UI 保存后）
                            // loadCustom 会重新读取并返回新词表；否则返回 null 沿用当前。
                            NokeyConfig.loadCustom()?.let { custom ->
                                CommandMatcher.currentCommands = custom
                            }

                            // 尝试取出首个 CharSequence 参数作为判定主文本
                            val text = param.args.firstOrNull { it is CharSequence } as? CharSequence
                            val s = text?.toString() ?: return

                            // ===== placeText 真实语音信号源 =====
                            // 反编译确认：真实语音口令渲染的气泡文本天然带「placeText」后缀
                            // （如「狗狗开门 placeText」），而历史会话恢复的气泡（如「狗狗开门」）不带。
                            // 因此 setText 命中含「placeText」的文本，即可确认为真实语音指令。
                            // 据此刷新 lastSpeechAsrTime 作为 setText 门禁判定的真实语音信号，
                            // 弥补 hookSpeechAsrGate（lg0.h/lg0.i）运行期零触发的缺陷，
                            // 并消除 20 秒兜底长窗（STRONG_STARTUP_GUARD_MS）弱门禁隐患。
                            if (s.contains("placeText")) {
                                lastSpeechAsrTime = System.currentTimeMillis()
                                XposedBridge.log(
                                    "[$TAG] [ASR-GATE-placeText信号] 检测到真实语音口令气泡「${truncate(s)}」→ 刷新 lastSpeechAsrTime=$lastSpeechAsrTime"
                                )
                            }

                            // ===== 语音输入门禁 =====
                            // 历史会话恢复是异步分批渲染且无真实语音，单靠固定启动窗无法根治
                            // （此前实测历史气泡在启动后 ~14s 仍命中口令）。
                            // 门禁原则：只有在"真实语音识别文本进入 UI"之后才开放口令解析。
                            val nowG = System.currentTimeMillis()
                            val hasSpeechSignal = lastSpeechAsrTime > 0
                            val gateOk = if (hasSpeechSignal) {
                                // 有语音信号：要求刚过启动防护窗，且距最近一次真实语音 ≤ 门禁窗
                                (nowG - hookTimestamp >= STARTUP_GUARD_MS) &&
                                    (nowG - lastSpeechAsrTime < SPEECH_GATE_MS)
                            } else {
                                // 无语音信号（ASR hook 未生效，兜底）：固定长窗后才解析
                                nowG - hookTimestamp >= STRONG_STARTUP_GUARD_MS
                            }

                            // 判定是否命中口令关键词。
                            val code = CommandMatcher.matchCommandCode(s)

                            if (code > 0) {
                                // ===== 方案B修复：实时拉起前置（与门禁解耦） =====
                                // 历史教训：ensureNokeyAlive 原本只嵌在 sendCommandOnly（"口令命中+门禁通过"
                                // gateOk=true）之后。而门禁通过依赖 placeText 信号刷新 lastSpeechAsrTime，
                                // 一旦小爱侧渲染文本形态变化（新版本 capture 不到 placeText 后缀），
                                // 门禁恒走 STRONG_STARTUP_GUARD_MS=20s 兜底长窗，gateOk 恒为 false，
                                // sendCommandOnly 永不触发 → 乘趣实时拉起被 gate 卡死，只能等 8s 轮询。
                                //
                                // 据此将"乘趣实时拉起"从门禁链路中解耦前置：只要口令命中（code>0），
                                // 无论 gateOk 与否，都先尝试实时拉起乘趣。安全性论证：
                                // 拉起进程 ≠ 操作车辆（拉起来只是让进程就绪，后续喊话 gateOk 通过才发广播），
                                // 且 ensureNokeyAlive 内部有 30s 限流并与轮询共用一把锁——
                                // 即使历史恢复误触发，也只是拉起进程，不会误操作车辆。
                                //
                                // 注意：放在门禁判定之前，但仍保持与后续逻辑相同的 try-catch 边界安全。
                                ensureNokeyAlive(appContext() ?: return@afterHookedMethod)

                                // 口令命中。但【开窗+屏蔽+发广播】三者统一收敛到"真实语音门禁通过"这一前提下：
                                // 只有真实语音识别文本进入 UI（gateOk）时，本次口令才是用户真实说话，
                                // 才允许：(a) 开启响应窗口遮蔽乱七八糟回复，(b) 发送车辆控制广播。
                                //
                                // 若 gateOk==false（进程启动/历史会话恢复/列表重绘/无真实语音），
                                // 一律既不开窗、也不屏蔽、也不发广播——避免历史气泡或界面上的
                                // "解锁/开窗/锁车"等字眼在无人说话时触发，破坏小爱主界面或误操作车辆。
                                if (!gateOk) {
                                    XposedBridge.log(
                                        "[$TAG] 口令命中但未过门禁（启动/历史恢复/无语音），忽略：code=$code「${truncate(s)}」" +
                                            " gateOk=false"
                                    )
                                    return
                                }

                                lastCommand = code
                                lastTriggerText = s
                                responseWindowUntil = nowG + RESPONSE_WINDOW_MS
                                XposedBridge.log(
                                    "[$TAG] 口令命中(门禁通过) code=$code「${truncate(s)}」→ 开启响应窗口 $RESPONSE_WINDOW_MS ms，发送广播"
                                )

                                // 全量清扫已播放轨道：窗口此刻才开启，而小爱池化 AudioTrack 可能已在
                                // ASR 识别后立刻 start() 并写入开头 PCM（先于本 setText 触发点），其开头
                                // 声音已被 Android 混音段播出一部分。这里立即把所有处于 PLAYING 状态的
                                // 已知实例压静（音量在混音段即时生效），掐掉泄漏的"已播出一点点"；
                                // 之后窗口内任何 write()/play()/start() 再由 hook 拦截，双保险封堵。
                                muteAllActiveAudioTracks()

                                // 广播发送（真实车辆操作）受防抖约束；门禁已通过，此处直接下发。
                                sendCommandOnly(code, s)

                                // 跨进程响应窗口同步：主进程已在上面开启本进程响应窗口，
                                // 这里广播通知同 UID 的 :core 进程同步开窗，使其 AudioTrack 静音 hook 能命中。
                                broadcastResponseWindow()

                                // 语音反馈：小爱原文播报已被 AudioTrack 静音，
                                // 设备无 TTS 引擎，这里用 MediaPlayer 播系统提示音作「已成功」反馈
                                // （ownSoundActive 豁免标志让提示音免于被自身 write() hook 掐断）。
                                // 全静默(档位C)：连提示音都跳过，小爱彻底无声无文字执行。
                                if (!NokeyConfig.loadSilentMode()) {
                                    playSuccessTone()
                                }
                                return
                            }

                            // 非口令：若正处于响应窗口，判断是否替换
                            if (inResponseWindow()) {
                                replaceIfAiResponse(param, s, thisObject = param.thisObject)
                            }
                        } catch (t: Throwable) {
                            // 静默，不因一条文本的解析异常影响小爱本身
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] 已 hook TextView.setText（全部重载）")

            // ===== LLM 渲染管线 hook =====
            // 关键转折：用户观察到可见气泡标注"AI生成"，证明小爱对车辆指令的回答走 LLM 大模型路径，
            // 而非普通模板卡片（FlowTemplateToastCard）。反编译 APK 确认 LLM 流式文本渲染走的是
            //   DefaultTypeWriterTextView.setMarkdownText(String)（流式打字机）/ markwon 管线，
            //   最终数据由 c.d.setTotalText(String) 累积，并不经过标准 TextView.setText。
            // 因此 hook TextView.setText 全重载抓不到真实可见气泡。这里补上 LLM 渲染的两个入口。
            hookLlmRender(lpparam.classLoader)

            // ===== 框架层通用拦截：窗口内拦断 LLM 卡片容器显示（修复"仍弹回复框"） =====
            // 根因：此前只在云端文本渲染层（c.d.setTotalText/setMarkdownText）喂空串，
            // 但卡片容器（回复框）本体仍被 RN 创建并挂载进对话列表 → 用户看到空白气泡。
            // 该 hook 在 Android 框架层按视图特征拦截卡片容器的可见性，彻底阻止"弹框"。
            hookBubbleGate()

            // ===== 浮窗结果卡片拦截：修复"从顶部弹出"浮窗卡片 =====
            // 根因：小爱对车辆指令的回答除了对话列表气泡外，还会启动一个独立的透明悬浮
            // Activity（FloatActivity，承载 FloatViewRootLayout），把结果渲染进
            // FloatResultComponentView（经 ViewStub.inflate 挂载），从顶部弹出浮窗卡片。
            // 该浮窗卡片的显隐由 FloatResultComponentView.setVisible(boolean,boolean)
            // 与 FloatResultWrapperCardView.setWrapperCardViewVisible(boolean) 控制，
            // 与已被覆盖的 lingxi/RN 卡片路径完全独立 → 此前 hook 零命中。
            // 此 hook 在源头强置浮窗卡片不显示，实现"连浮窗卡片都不出现"的全静默。
            hookFloatCardGate(lpparam.classLoader)

            // ===== 语音输入门禁 hook =====
            // 识别"真实语音识别文本进入 UI"的时机（lg0.h/lg0.i 的 delayShowAsr* 入口），
            // 用于 setText 回调里区分"真实语音指令"与"历史恢复纯渲染"。
            hookSpeechAsrGate(lpparam.classLoader)

            // ===== 语音通道：hook TextToSpeech.speak 的所有重载（兜底/防御） =====
            // 此前确认小爱语音不走标准 TTS（speak 零命中），此 hook 保留作为兜底/验证。
            // 采用反射获取 TextToSpeech 类：Android framework 必然包含此类（缺引擎仅影响
            // TextToSpeech() 构造初始化，不影响类加载与 hook 挂载），故无需 import 它也能编译。
            try {
                val ttsClass = Class.forName("android.speech.tts.TextToSpeech")
                XposedBridge.hookAllMethods(
                    ttsClass,
                    "speak",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (param.args.isEmpty()) return
                                val a0 = param.args[0]
                                if (a0 is CharSequence && a0.isNotBlank()) {
                                    val s = a0.toString()
                                    if (inResponseWindow()) {
                                        replaceIfAiResponse(param, s, thisObject = param.thisObject)
                                    }
                                }
                            } catch (t: Throwable) {
                                // 静默
                            }
                        }
                    }
                )
                XposedBridge.log("[$TAG] 已 hook TextToSpeech.speak")
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] 反射获取 TextToSpeech 类失败，跳过兜底 hook: ${t.message}")
            }

            // ===== 语音通道：静音小爱 MIX 引擎的 AudioTrack 播报 =====
            // 小爱 LLM 语音走 MIX 混合 TTS 引擎，最终经 AudioTrack 播放（不经过标准 TTS）。
            // 在响应窗口内静音原文，由 playSuccessTone() 播系统提示音补「已成功」反馈（方案 B）。
            hookAudioMute()

            // ===== 跨进程响应窗口同步（防御保留） =====
            // dumpsys 实测小爱 LLM 语音的 AudioTrack 播报发生在主进程，此处 hook
            // Application.attach 注册 ACTION_RESPONSE_WINDOW 接收器，防御未来版本将
            // 播报拆到 :core 等子进程时仍能同步主进程开启的响应窗口。
            hookResponseWindowSync(lpparam.classLoader)

            // ===== 跨进程 ACK 确认接收（根治锁屏/灭屏广播丢失） =====
            // 注册 ACTION_NOKEY_ACK 接收器，接收乘趣进程执行命令成功后回发的 ACK；
            // 收到即撤销 sendCommandOnly 里启动的确认窗口（自适应重发随之停止）。
            hookAckReceiver(lpparam.classLoader)

            XposedBridge.log("[$TAG] hook 挂载完成（含回应控制）")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook 挂载失败: ${t.message}")
        }
    }

    /**
     * Hook 小爱 LLM 大模型回答的渲染入口。
     *
     * 两个关键点（反编译 APK 确认）：
     *  1) data model `c.d.setTotalText(String)`：LLM 流式回答的累积文本都会写入这里。
     *     把它在响应窗口内替换为「已成功」，则从源头锁死文本内容。
     *  2) `DefaultTypeWriterTextView.setMarkdownText(String)`：流式打字机把累积文本
     *     分派到可见 TextView（R.id.tv_text）渲染时的入口，参数0 即要显示的 markdown 全文。
     *     这里兜底替换，保证即使 data model 路径漏了，渲染层也看不到旧内容。
     *
     * 类/方法均来自 com.miui.voiceassist APK，编译期不可见，需用 Class.forName 反射。
     */
    private fun hookLlmRender(appClassLoader: ClassLoader) {
        // 1) data model c.d.setTotalText(String)
        try {
            val modelCls = Class.forName("com.xiaomi.voiceassistant.instruction.card.stream.c\$d", false, appClassLoader)
            XposedHelpers.findAndHookMethod(
                modelCls,
                "setTotalText",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val s = param.args[0]?.toString() ?: return
                            if (s.isBlank()) return
                            val inWin = inResponseWindow()
                            val now = System.currentTimeMillis()
                            val diag = describeTarget(param.thisObject)
                            // 【诊断探针】无条件打印时间与窗口状态，确认 LLM 渲染真实时序
                            if (s.isBlank() || true) {
                                XposedBridge.log(
                                    "[$TAG] [LLM-PROBE] c.d.setTotalText t+${now - hookTimestamp}ms inWin=$inWin winUntil+${if (inWin) (responseWindowUntil - now) else 0}ms「${truncate(s)}」$diag"
                                )
                            }
                            if (!inWin) return
                            // 全静默(档位C)：数据源喂空串 → data model 无内容 → card 不创建；并拦断渲染入口
                            if (NokeyConfig.loadSilentMode()) {
                                XposedBridge.log("[$TAG] [LLM][SILENT] c.d.setTotalText 静默拦断（喂空串） $diag")
                                param.args[0] = ""
                                param.result = null
                                return
                            }
                            val picked = pickSuccessText()
                            XposedBridge.log("[$TAG] [LLM] c.d.setTotalText「${truncate(s)}」→「$picked」 $diag")
                            param.args[0] = picked
                        } catch (t: Throwable) { /* 静默 */ }
                    }
                }
            )
            XposedBridge.log("[$TAG] 已 hook LLM data model：c.d.setTotalText")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook c.d.setTotalText 失败: ${t.message}")
        }

        // 2) DefaultTypeWriterTextView.setMarkdownText(String) —— 流式打字机渲染入口
        try {
            val typeWriterCls =
                Class.forName("com.xiaomi.voiceassistant.uidesign.markdown.DefaultTypeWriterTextView", false, appClassLoader)
            XposedHelpers.findAndHookMethod(
                typeWriterCls,
                "setMarkdownText",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val s = param.args[0]?.toString() ?: return
                            if (s.isBlank()) return
                            val inWin = inResponseWindow()
                            val now = System.currentTimeMillis()
                            val diag = describeTarget(param.thisObject)
                            // 【诊断探针】无条件打印时间与窗口状态
                            XposedBridge.log(
                                "[$TAG] [LLM-PROBE] setMarkdownText t+${now - hookTimestamp}ms inWin=$inWin winUntil+${if (inWin) (responseWindowUntil - now) else 0}ms「${truncate(s)}」$diag"
                            )
                            if (!inWin) return
                            // 全静默(档位C)：拦断流式打字机渲染入口，气泡不出
                            if (NokeyConfig.loadSilentMode()) {
                                XposedBridge.log("[$TAG] [LLM][SILENT] setMarkdownText 静默拦断（不渲染） $diag")
                                logStackShort()
                                param.args[0] = ""
                                param.result = null
                                return
                            }
                            val picked = pickSuccessText()
                            XposedBridge.log("[$TAG] [LLM] setMarkdownText「${truncate(s)}」→「$picked」 $diag")
                            logStackShort()
                            param.args[0] = picked
                        } catch (t: Throwable) { /* 静默 */ }
                    }
                }
            )
            XposedBridge.log("[$TAG] 已 hook DefaultTypeWriterTextView.setMarkdownText")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook setMarkdownText 失败: ${t.message}")
        }
    }

    // =================================================================
    // 框架层通用拦截：响应窗口内彻底拦断 LLM 卡片容器（回复框）的显示
    // =================================================================
    // 背景：本地"仍弹回复框"的根因是——C 档全静默只在文本渲染层
    // （c.d.setTotalText / DefaultTypeWriterTextView.setMarkdownText）喂空串，
    // 但 LLM 卡片容器（气泡/回复框本身）仍由 React Native 运行时创建并挂载进
    // 对话列表，即使文本为空用户也能看到空白的气泡容器。
    //
    // 方案：在 Android 框架层（ViewGroup.addView 与 View.setVisibility）做通用拦截，
    // 按视图特征识别"LLM 卡片容器"并抑制其显示。无论 RN 还是原生创建，卡片
    // 最终都以 View 型式挂载进 ViewGroup / 被 setVisibility 置为可见，这两点是
    // RN 与原生共同的必经通道，比去定位 RN 的具体 addView 绑定代码更可靠。
    private val llmHiddenViews = ConcurrentHashMap.newKeySet<Any>()

    private fun hookBubbleGate() {
        try {
            // 1) ViewGroup.addView 全重载：卡片被追加进对话列表的拦截点。
            //    在该 View 被 add 进父容器后立即置 GONE（不真正打断 addView 本身，
            //    以免破坏 RN 的视图树挂载逻辑引发异常）。
            XposedBridge.hookAllMethods(
                android.view.ViewGroup::class.java,
                "addView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!inResponseWindow()) return
                            if (!NokeyConfig.loadSilentMode()) return
                            val child = param.args.getOrNull(0) ?: return
                            if (!isLlmCard(child)) return
                            llmHiddenViews.add(child)
                            setViewGone(child)
                            XposedBridge.log(
                                "[$TAG] [BUBBLE] addView 拦断 LLM 卡片→GONE cls=${child.javaClass.name}"
                            )
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] [BUBBLE] addView 拦截异常: ${t.message}")
                        }
                    }
                }
            )

            // 2) View.setVisibility：任何试图把卡片置为可见的操作都被强制转 GONE。
            //    若 addView 拦截被绕过（如视图复用、RN 后置可见），此处在 before 阶段
            //    直接改写 args[0]=GONE，从源头掐断"显示"这一动作。
            XposedBridge.hookAllMethods(
                android.view.View::class.java,
                "setVisibility",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!inResponseWindow()) return
                            if (!NokeyConfig.loadSilentMode()) return
                            val v = param.thisObject ?: return
                            if (!isLlmCard(v)) return
                            val visibility = param.args.getOrNull(0) as? Int ?: return
                            if (visibility == android.view.View.VISIBLE || visibility == android.view.View.INVISIBLE) {
                                param.args[0] = android.view.View.GONE
                                llmHiddenViews.add(v)
                                XposedBridge.log(
                                    "[$TAG] [BUBBLE] setVisibility 拦断→GONE cls=${v.javaClass.name} orig=$visibility"
                                )
                            }
                        } catch (t: Throwable) {
                            /* 静默 */
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] 已 hook ViewGroup.addView + View.setVisibility（窗口内拦断 LLM 卡片容器）")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hookBubbleGate 挂载失败: ${t.message}")
        }
    }

    // =================================================================
    // 浮窗结果卡片源头拦截：C 档全静默直接压掉"从顶部弹出"的浮窗卡片
    // =================================================================
    // 背景：小爱的浮窗结果卡片（从顶部弹出、常显示车辆指令执行结果）承载在
    // 独立的透明悬浮 Activity（FloatActivity）里，核心容器为
    //   FloatResultComponentView（floatresult/FloatResultComponentView，setVisible 显隐核心）
    // 与
    //   FloatResultWrapperCardView（floatresult/view/FloatResultWrapperCardView，根包装）。
    //
    // 这两者才真正控制浮窗卡片"是否从顶部弹出显示"，与 lingxi/RN 对话列表气泡完全独立
    // 通道，故此前 hookBubbleGate 的 isLlmCard 特征（未覆盖 floatresult）对其零命中。
    //
    // 本方法直接在源头 hook 这两套显隐控制器，在响应窗口且 C 档静默时强置
    // "不显示"（setVisible(false,false) / setWrapperCardViewVisible(false)），
    // 从机制上保证浮窗卡片根本不出现。这是比框架层 addView/setVisibility 更精准、
    // 更贴近业务语义的拦截点（直接在"决定是否显示"的入口掐断）。
    //
    // 全限定类名/方法（反编译确认）：
    //   com.xiaomi.voiceassistant.mainui.uicontainer.floatresult.FloatResultComponentView.setVisible(boolean,boolean)
    //   com.xiaomi.voiceassistant.mainui.uicontainer.floatresult.view.FloatResultWrapperCardView.setWrapperCardViewVisible(boolean)
    private fun hookFloatCardGate(appClassLoader: ClassLoader) {
        try {
            // 1) FloatResultComponentView.setVisible(boolean z, boolean z2)
            //    核心显隐方法：内部执行 setVisibility(z ? 0 : 8) + 可选 notifyParentVisibilityChanged(z)。
            //    z=true 表示要显示浮窗卡片。在 C 档静默时强改参数为 false，令其显示动作失效。
            val compCls = Class.forName(
                "com.xiaomi.voiceassistant.mainui.uicontainer.floatresult.FloatResultComponentView",
                false,
                appClassLoader
            )
            XposedHelpers.findAndHookMethod(
                compCls,
                "setVisible",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!inResponseWindow()) return
                            if (!NokeyConfig.loadSilentMode()) return
                            val z = param.args[0] as? Boolean ?: return
                            if (z) {
                                // 企图显示浮窗卡片 → 强制不显示
                                param.args[0] = false
                                XposedBridge.log(
                                    "[$TAG] [FLOAT] FloatResultComponentView.setVisible 拦断显示→false " +
                                        "cls=${param.thisObject.javaClass.name}"
                                )
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] [FLOAT] setVisible 拦截异常: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] 已 hook FloatResultComponentView.setVisible（浮窗卡片源头显隐拦截）")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook FloatResultComponentView.setVisible 失败: ${t.message}")
        }

        try {
            // 2) FloatResultWrapperCardView.setWrapperCardViewVisible(boolean z)
            //    浮窗卡 FrameLayout 根包装的显隐控制。z=true 表示展开显示整张浮窗卡片。
            //    在 C 档静默时强改参数为 false，令整张卡片保持收起/隐藏。
            val wrapCls = Class.forName(
                "com.xiaomi.voiceassistant.mainui.uicontainer.floatresult.view.FloatResultWrapperCardView",
                false,
                appClassLoader
            )
            XposedHelpers.findAndHookMethod(
                wrapCls,
                "setWrapperCardViewVisible",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!inResponseWindow()) return
                            if (!NokeyConfig.loadSilentMode()) return
                            val z = param.args[0] as? Boolean ?: return
                            if (z) {
                                param.args[0] = false
                                XposedBridge.log(
                                    "[$TAG] [FLOAT] setWrapperCardViewVisible 拦断显示→false " +
                                        "cls=${param.thisObject.javaClass.name}"
                                )
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] [FLOAT] setWrapperCardViewVisible 拦截异常: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] 已 hook FloatResultWrapperCardView.setWrapperCardViewVisible")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook FloatResultWrapperCardView.setWrapperCardViewVisible 失败: ${t.message}")
        }
    }

    /** 尝试将指定 View 直接置为 GONE（对非 View 或异常静默） */
    private fun setViewGone(v: Any?) {
        if (v is android.view.View) {
            try { v.visibility = android.view.View.GONE } catch (t: Throwable) { /* 忽略 */ }
        }
    }

    /**
     * 判定某视图是否为"LLM 卡片容器"（回复框 / 浮窗结果卡片）。
     *
     * 特征识别（逐个判断，命中其一即视为 LLM 卡片）：
     *   a) 类名含 LingXi / lingxi / ling_xi（如 LingXiCardContainer 等 lingxi 系卡片）
     *   b) 视图自身或子视图内能找到 R.id.ling_xi_card_item_card_view(0x7f0a073a)、
     *      R.id.ling_xi_card_cover(0x7f0a0739) 等 lingxi 卡片独有 id（反编译资源表确认）
     *   c) 视图是 com.xiaomi.voiceassistant.reactnative 包下的卡片容器（LLM 走 RN 渲染）
     *   d) [新增·浮窗卡片] 类名属于 floatresult 浮窗结果卡片体系——
     *      这是"从顶部弹出"的浮窗结果卡片的根容器，此前零命中的根本原因。
     *      关键类（反编译确认全限定名）：
     *        - FloatResultComponentView  ：浮窗卡片顶层容器（setVisible 显隐核心）
     *        - FloatResultWrapperCardView：浮窗卡 FrameLayout 根包装
     *        - FloatResultCardView / FloatResultFooterView / 其他 FloatResult* 全部拦截
     *        - FloatConversationAdapter      ：浮窗会话列表适配器（浮窗 LLM 文本）
     *        - ViewStub 挂载用的 FloatViewRootLayout（承载整个浮窗 Activity 内容）
     */
    private fun isLlmCard(v: Any?): Boolean {
        if (v == null) return false
        val clsName = v.javaClass.name
        if (clsName.contains("LingXi", ignoreCase = true) ||
            clsName.contains("lingxi", ignoreCase = true) ||
            clsName.contains("ling_xi", ignoreCase = true)
        ) {
            return true
        }
        // reactnative 包下的卡片/气泡宿主类（LLM 渲染容器）
        if (clsName.startsWith("com.xiaomi.voiceassistant.reactnative") &&
            (clsName.contains("Card", ignoreCase = true) ||
                clsName.contains("Bubble", ignoreCase = true) ||
                clsName.contains("Message", ignoreCase = true) ||
                clsName.contains("Item", ignoreCase = true))
        ) {
            return true
        }
        // [新增] floatresult 浮窗结果卡片体系（从顶部弹出的浮窗卡片根容器）
        if (clsName.contains("floatresult", ignoreCase = true) &&
            (clsName.contains("FloatResult", ignoreCase = false) ||
                clsName.contains("FloatConversation", ignoreCase = false))
        ) {
            return true
        }
        // [新增] FloatViewRootLayout（承载浮窗 Activity 内容、ViewStub 挂载点的根布局）
        if (clsName.contains("FloatViewRootLayout")) return true
        // [新增] FloatActivity（承载浮窗卡片的透明悬浮 Activity 顶层视图）
        if (clsName.contains("mainui.home.FloatActivity")) {
            // 仅拦截其默认 DecorView 内容视图这一层（避免误伤底部工具栏等必要 UI）
            if (clsName.endsWith("FloatActivity")) return true
        }
        val vv = v as? android.view.View ?: return false
        // 遍历子视图，命中 lingxi 卡片独有 id 即判定为卡片容器
        try {
            // R.id.ling_xi_card_item_card_view = 0x7f0a073a
            if (vv.findViewById<android.view.View>(0x7f0a073a) != null) return true
            // R.id.ling_xi_card_cover = 0x7f0a0739
            if (vv.findViewById<android.view.View>(0x7f0a0739) != null) return true
            // R.id.ling_xi_left_center_text = 0x7f0a073d（类型为 TextView）
            if (vv.findViewById<android.widget.TextView>(0x7f0a073d) != null) return true
        } catch (t: Throwable) {
            /* 忽略遍历异常 */
        }
        return false
    }

    /**
     * 跨进程响应窗口同步。
     *
     * 背景：dumpsys 实测小爱 LLM 语音的 AudioTrack 播报实际发生在主进程，响应窗口
     * 也在主进程开启，故正常情况下无需跨进程同步。此处保留作为进程边界差异的防御：
     * 一旦未来版本将播报拆到 :core 等子进程，此机制仍能让各进程的 AudioTrack 静音
     * hook 同步命中主进程开启的窗口。因此：
     *   - 主进程在口令命中时 sendResponseWindowBroadcast() 广播 ACTaction_RESPONSE_WINDOW；
     *   - 所有同 UID 进程（含潜在子进程）此处注册的接收器收到广播后，
     *     用本进程时钟把 responseWindowUntil 设为 now + RESPONSE_WINDOW_MS，
     *     从而让本进程的 AudioTrack/text 静音-替换 hook 也能命中。
     *
     * 注册时机：handleLoadPackage 时 ActivityThread.currentApplication() 往往为 null，
     * 故 hook Application.attach，在应用真正创建后取真实 Context 动态注册接收器。
     * （与 MainHook 一致的模式；targetSdk>=33 用 RECEIVER_EXPORTED 收外部广播）
     */
    private fun hookResponseWindowSync(appClassLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val appCtx = param.thisObject as? Context ?: return
                            val receiver = object : BroadcastReceiver() {
                                override fun onReceive(ctx: Context, intent: Intent) {
                                    try {
                                        if (intent.action != ACTION_RESPONSE_WINDOW) return
                                        val now = System.currentTimeMillis()
                                        responseWindowUntil = now + RESPONSE_WINDOW_MS
                                        XposedBridge.log(
                                            "[$TAG] [WIN-SYNC] 收到响应窗口同步广播 → 本进程开启窗口到 +$RESPONSE_WINDOW_MS ms"
                                        )
                                    } catch (t: Throwable) { /* 静默 */ }
                                }
                            }
                            val filter = IntentFilter(ACTION_RESPONSE_WINDOW)
                            appCtx.registerReceiver(
                                receiver,
                                filter,
                                Context.RECEIVER_EXPORTED
                            )
                            XposedBridge.log("[$TAG] 已注册跨进程响应窗口同步接收器")

                            // ===== 阶段四：外部宿主保活 =====
                            // 仅在小爱【主进程】（processName == PACKAGE，而非 :core）启动保活定时器，
                            // 避免主进程与 :core 竞争拉起；宿主即小爱进程自身常驻的 Application。
                            try {
                                if (isMainProcess()) {
                                    startExternalHostKeepAlive(appCtx)
                                } else {
                                    XposedBridge.log("[$TAG] [HOST] 非主进程（:core），不担任外部宿主")
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("[$TAG] [HOST] 启动外部宿主保活失败: ${t.message}")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] 注册响应窗口同步接收器失败: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook 响应窗口同步接收器失败: ${t.message}")
        }
    }

    /**
     * 注册跨进程 ACK 确认接收器（根治锁屏/灭屏下广播静默丢失）。
     *
     * 架构：乘趣进程执行 NOKEY_CMD 命令成功后，MainHook 主动回发定向 ACK 广播
     * （ACTION_NOKEY_ACK + EXTRA_COMMAND），本模块（小爱进程）收 ACK 后撤销
     * sendCommandOnly 里启动的确认窗口，自适应重发随之停止。
     *
     * 与 hookResponseWindowSync 一致的模式：hook Application.attach，取真实 Context
     * 动态注册（targetSdk>=33 用 RECEIVER_EXPORTED 收跨 UID 显式广播）。主进程与
     * :core 都会注册；但 ACK 用 pendingAckCommand 判定去重——第二次（:core）收到时
     * pendingAckCommand 已复位为 -1，直接忽略，幂等安全。
     */
    private fun hookAckReceiver(appClassLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val appCtx = param.thisObject as? Context ?: return
                            val receiver = object : BroadcastReceiver() {
                                override fun onReceive(ctx: Context, intent: Intent) {
                                    try {
                                        if (intent.action != MainHook.ACTION_NOKEY_ACK) return
                                        val ackCmd = intent.getIntExtra(MainHook.EXTRA_COMMAND, -1)
                                        // 幂等：只有与当前待确认命令一致才撤销确认窗口
                                        val pending = pendingAckCommand
                                        if (pending < 0 || ackCmd != pending) {
                                            XposedBridge.log(
                                                "[$TAG] [ACK] 收到 ACK code=$ackCmd，" +
                                                    (if (pending < 0) "当前无待确认命令，忽略" else "与待确认 $pending 不符，忽略")
                                            )
                                            return
                                        }
                                        pendingAckCommand = -1
                                        ackRetryInFlight = false
                                        ackRetryRound = 0
                                        // 取消所有未触发的重发任务（当前 postDelayed 任务取消即停）
                                        try {
                                            ackHandler.removeCallbacksAndMessages(null)
                                        } catch (t: Throwable) { /* 静默 */ }
                                        XposedBridge.log(
                                            "[$TAG] [ACK] 确认命令 code=$ackCmd 已由乘趣执行，撤销确认窗口（历时 " +
                                                (System.currentTimeMillis() - ackWaitStart) + "ms）"
                                        )
                                    } catch (t: Throwable) {
                                        XposedBridge.log("[$TAG] [ACK] 处理 ACK 失败: ${t.message}")
                                    }
                                }
                            }
                            val filter = IntentFilter(MainHook.ACTION_NOKEY_ACK)
                            appCtx.registerReceiver(
                                receiver,
                                filter,
                                Context.RECEIVER_EXPORTED
                            )
                            XposedBridge.log("[$TAG] 已注册跨进程 ACK 确认接收器")
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] 注册 ACK 确认接收器失败: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook ACK 确认接收器失败: ${t.message}")
        }
    }

    /**
     * 启动 ACK 确认等待：sendCommandOnly 成功下发 NOKEY_CMD 后调用。
     *
     * 登记待确认命令并启动自适应递增的确认等待窗口扫描。乘趣执行成功会回发
     * ACTION_NOKEY_ACK 撤销等待（hookAckReceiver 兜底处理）；若窗口超时仍未
     * 收到 ACK，判定广播可能被冻结进程静默丢弃，触发 checkAckRetry 强制唤醒 + 重发。
     *
     * 防重入：ackRetryInFlight=true 时说明上一命令的重试链仍在飞，直接忽略本次调用，
     * 避免同一场景多路渲染叠加出重复的重发线。
     */
    private fun scheduleAckRetry(context: Context, code: Int) {
        try {
            if (ackRetryInFlight) {
                XposedBridge.log("[$TAG] [ACK] 已有重试链在飞，跳过重复调度 code=$code")
                return
            }
            ackHandler.removeCallbacksAndMessages(null)
            pendingAckCommand = code
            ackWaitStart = System.currentTimeMillis()
            ackRetryRound = 0
            ackRetryInFlight = true
            ackHandler.postDelayed({ checkAckRetry(context) }, ACK_WAIT_WINDOWS[0])
            XposedBridge.log(
                "[$TAG] [ACK] 启动确认等待 code=$code，首轮窗口 ${ACK_WAIT_WINDOWS[0]}ms"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [ACK] 启动确认等待失败: ${t.message}")
        }
    }

    /**
     * ACK 确认等待的窗口检查回调（跑主进程主线程 Handler）。
     *
     * - 若 pendingAckCommand 已被 hookAckReceiver 复位（-1）→ 已确认，关闭重试链。
     * - 若已达重试上限 → 打警示日志并复位，不再无限骚扰。
     * - 否则发起本轮「强制唤醒 + 重发」：
     *      frozen 场景下 ensureNokeyAlive 依赖 isAppProcessAlive 会把 frozen 进程误判为
     *      "存活"而不拉起（root 命令不受该判定约束），故此处【直接走 launchNokeyViaRoot】
     *      的 root `am start-foreground-service` 强制解除 frozen 唤醒乘趣，随后重发广播；
     *      root 命令内部有 5s waitFor 超时保护，故必放到子线程执行，避免阻塞主进程。
     *   每轮重发间隔取自 ACK_WAIT_WINDOWS 自适应递增序列。
     */
    private fun checkAckRetry(context: Context) {
        try {
            if (pendingAckCommand < 0) {
                // 已确认（hookAckReceiver 复位），关闭重试链
                ackRetryInFlight = false
                return
            }
            val round = ackRetryRound
            if (round >= ACK_WAIT_WINDOWS.size) {
                XposedBridge.log(
                    "[$TAG] [ACK] 重试 $round 次仍无确认，放弃（乘趣可能未执行 code=$pendingAckCommand，" +
                        "请留意车内是否响应）"
                )
                pendingAckCommand = -1
                ackRetryInFlight = false
                return
            }
            val targetCode = pendingAckCommand
            // 子线程执行 root 唤醒 + 重发，避免阻塞主线程
            Thread {
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - ackLastLaunchAt >= ACK_LAUNCH_COOLDOWN_MS) {
                        ackLastLaunchAt = now
                        if (launchNokeyViaRoot()) {
                            XposedBridge.log("[$TAG] [ACK] 第${round + 1}轮已通过 root 强制唤醒乘趣")
                        } else {
                            XposedBridge.log("[$TAG] [ACK] 第${round + 1}轮 root 唤醒未成功（进程可能已存活，继续重发）")
                        }
                    } else {
                        XposedBridge.log("[$TAG] [ACK] 第${round + 1}轮处于拉起冷却期，直接重发广播")
                    }
                    val intent = Intent(MainHook.ACTION_NOKEY_CMD)
                        .setPackage(MainHook.PACKAGE_TARGET)
                        .putExtra(MainHook.EXTRA_COMMAND, targetCode)
                    context.sendBroadcast(intent)
                    XposedBridge.log("[$TAG] [ACK] 第${round + 1}轮已重发 NOKEY_CMD code=$targetCode")
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] [ACK] 第${round + 1}轮重发异常: ${t.message}")
                } finally {
                    // 回到主线程安排下一轮
                    ackHandler.post {
                        try {
                            if (pendingAckCommand < 0) {
                                ackRetryInFlight = false
                                return@post
                            }
                            ackRetryRound++
                            if (ackRetryRound < ACK_WAIT_WINDOWS.size) {
                                val w = ACK_WAIT_WINDOWS[ackRetryRound]
                                ackHandler.postDelayed({ checkAckRetry(context) }, w)
                                XposedBridge.log("[$TAG] [ACK] 已重发 ${ackRetryRound} 轮，下一轮等待 $w ms")
                            } else {
                                // 达到上限，安排一次最终检查用于宣告放弃
                                ackHandler.postDelayed(
                                    { checkAckRetry(context) },
                                    ACK_WAIT_WINDOWS[ACK_WAIT_WINDOWS.size - 1]
                                )
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] [ACK] 安排下一轮失败: ${t.message}")
                        }
                    }
                }
            }.start()
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [ACK] 检查回调异常: ${t.message}")
            ackRetryInFlight = false
        }
    }

    // =====================================================================
    // 阶段四：外部宿主保活
    //
    // 根因：乘趣进程被 kill -9 / 滑动移除 / force-stop 杀死后，AMS 不触发
    // START_STICKY，模块自身代码随进程消失，无外部触发源主动拉起 → 不能及时恢复。
    //
    // 方案：以小爱进程（常驻、已被本模块 hook）为外部宿主，周期性检测乘趣进程
    // 存活，若进程死亡则用显式 startForegroundService 拉起（已实测：显式 component
    // 拉起可行）。小爱是普通 UID，拉起受限可能不如 system shell，但滑动移除/
    // kill -9（非 force-stop）不置 stopped，普通 UID 显式拉起通常可行。
    // =====================================================================

    /** 判断当前进程是否为小爱主进程（排除 :core 等子进程，避免重复担任宿主） */
    private fun isMainProcess(): Boolean {
        return try {
            val pid = Process.myPid()
            val line = java.io.File("/proc/$pid/cmdline").readText().trimEnd('\u0000')
            line == PACKAGE
        } catch (t: Throwable) {
            // 兜底：读不到进程名时，用包名进程判断（主进程进程名即包名）
            try {
                appContext()?.packageName == PACKAGE
            } catch (e: Throwable) {
                false
            }
        }
    }

    /** 启动外部宿主保活定时器（幂等，防重入） */
    private fun startExternalHostKeepAlive(hostCtx: Context) {
        if (hostScheduled) return
        hostScheduled = true
        hostEnabled = true

        // 主线程 Handler 周期性调度，避免占用额外线程且天然串行。
        // 首轮稍作延迟，等小爱自身驻留稳定后再承担宿主职责，避免启动早期误判。
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                try {
                    hostPollOnce(hostCtx)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] [HOST] 检测周期异常: ${t.message}")
                } finally {
                    // 只要宿主仍启用，就继续调度下一轮
                    if (hostEnabled) handler.postDelayed(this, HOST_POLL_MS)
                }
            }
        }
        handler.postDelayed(runnable, HOST_POLL_MS)
        XposedBridge.log("[$TAG] [HOST] 外部宿主保活已启动，周期 ${HOST_POLL_MS}ms（小爱主进程担任宿主）")
    }

    /** 单轮检测：乘趣进程若存活则跳过，死亡则尝试拉起 */
    private fun hostPollOnce(hostCtx: Context) {
        val alive = isAppProcessAlive(hostCtx, MainHook.PACKAGE_TARGET)
        if (alive) return

        val now = SystemClock.elapsedRealtime()
        // 最低重试间隔限流：避免进程在启动中尚未就绪时被高频反复拉起
        if (now - hostLastRelaunchTime < HOST_MIN_RETRY_MS) return

        // 日志节流：进程死亡期间避免每 8s 刷屏
        if (now - hostLastLogTime >= HOST_POLL_MS * 4) {
            hostLastLogTime = now
            XposedBridge.log("[$TAG] [HOST] 检测到乘趣进程死亡 → 尝试拉起")
        }

        launchNokey(hostCtx)
    }

    /**
     * 方案B：确保乘趣进程存活（供"口令命中实时触发"与"轮询保活"共用）。
     *
     * 触发时机：广播下发（sendCommandOnly）检测到乘趣死亡时立即调用，让乘趣在
     * "用户开口的那一刻"就被拉起，而不是干等 HOST_POLL_MS 轮询（最长 8s）才被发现，
     * 从而把"被杀后恢复"的感知延迟从原本的均值 ~4s 压到接近实机冷启动（1~2s）。
     *
     * 与轮询共用一个 30s 最小重试限流（hostLastRelaunchTime），避免窗口内反复说话
     * 造成高频重复拉起。root am 会 waitFor 阻塞，故切子线程执行，不拖住 hook 回调线程。
     */
    /**
     * 检查并确保乘趣进程存活。返回本次调用是否真正发起了拉起动作（含被限流拦截但
     * 进程确认为死的情形）。该返回值用于广播发送后判定是否需要冷启动补偿重发：
     * 只要乘趣进程此刻不在（本次广播的接收器必然尚未登录，广播必丢），调用方就应
     * 无条件安排一次延迟重发，而【不】依赖 hostLastRelaunchTime 的新旧——因为该值可能
     * 是 30s 限流窗口前的旧时间戳，用它判断会漏掉"乘趣已死但距上次拉起很久"的场景，
     * 这正是用户反馈"第一次唤醒拉起了进程但广播没执行、要喊第二次"的根因之一。
     */
    private fun ensureNokeyAlive(ctx: Context): Boolean {
        return try {
            if (isAppProcessAlive(ctx, MainHook.PACKAGE_TARGET)) return false
            val nowRt = SystemClock.elapsedRealtime()
            // 最低重试间隔限流：与轮询保活同一把锁，避免高频重复拉起
            if (nowRt - hostLastRelaunchTime < HOST_MIN_RETRY_MS) {
                XposedBridge.log("[$TAG] [HOST] 喊话实时拉起被 30s 限流拦截（距上次拉起 ${nowRt - hostLastRelaunchTime}ms）")
                // 进程确认死亡，即使被限流也应让调用方知道"需要延迟重发"（本次广播已丢）。
                return true
            }
            // 子线程执行：launchNokey 内部 root 分支会 waitFor 阻塞，不能占用 hook 回调线程
            Thread {
                try {
                    launchNokey(ctx)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] [HOST] 喊话实时拉起异常: ${t.message}")
                }
            }.start()
            true
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [HOST] ensureNokeyAlive 异常: ${t.message}")
            false
        }
    }

    /** 判断目标包名是否有存活进程 */
    private fun isAppProcessAlive(ctx: Context, pkg: String): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
            val processes = am.runningAppProcesses ?: return true
            processes.any { it.processName == pkg }
        } catch (t: Throwable) {
            // 无法查询时保守视为存活，避免误拉起
            true
        }
    }

    /**
     * 拉起乘趣保活服务（阶段四 方案c）：
     *
     * 优先以 root 特权执行 `am start-foreground-service` 显式拉起，绕过"普通 UID 跨 UID
     * 启动未导出 Service 被 Android 12+ 导出检查拦截"的硬限制。
     * 前提：小爱进程 UID 已加入 Magisk sulist 白名单，进程内 exec su 可获 root。
     * 失败/无 root 时回退到普通 startForegroundService（非 force-stop 场景仍可生效）。
     */
    private fun launchNokey(ctx: Context) {
        // 优先分支：root 特权 am 拉起
        if (launchNokeyViaRoot()) {
            hostLastRelaunchTime = SystemClock.elapsedRealtime()
            XposedBridge.log("[$TAG] [HOST] 已通过 root 特权拉起乘趣（su am start-foreground-service）")
            return
        }

        // 回退分支：普通 UID 显式拉起（保留原逻辑，非 force-stop 的 kill 场景可用）
        try {
            val appCtx = ctx.applicationContext ?: ctx
            val intent = Intent()
                .setAction("KeepAliveService.action.scan.start")
                .setClassName(
                    MainHook.PACKAGE_TARGET,
                    "com.ingeek.nokey.component.keepAlive.KeepAliveService"
                )
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                appCtx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            hostLastRelaunchTime = SystemClock.elapsedRealtime()
            XposedBridge.log("[$TAG] [HOST] 已显式拉起乘趣（startForegroundService 回退）")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [HOST] 拉起乘趣失败: ${t.message}")
        }
    }

    /**
     * 以 root 特权执行 `am start-foreground-service` 拉起乘趣保活服务。
     * 返回 true 表示成功拉起（命令 exitCode==0）。
     * 通过 Runtime.exec 在子线程执行，避免阻塞小爱主进程。
     */
    private fun launchNokeyViaRoot(): Boolean {
        return try {
            val cmd = "am start-foreground-service" +
                " -n com.ingeek.nokey/.component.keepAlive.KeepAliveService" +
                " -a KeepAliveService.action.scan.start"
            // 小爱 UID 已在 sulist 白名单（policy=2），exec su 不会弹授权而阻塞，
            // 直接用 su -c 执行 am，由下方 waitFor 超时保护防命令卡死。
            val proc = Runtime.getRuntime()
                .exec(arrayOf("/system/bin/su", "-c", cmd))
            try {
                if (proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.inputStream.close()
                    proc.errorStream.close()
                    proc.exitValue() == 0
                } else {
                    // 超时未返回, 强制销毁防止拖住宿主进程
                    proc.destroyForcibly()
                    proc.inputStream.close()
                    proc.errorStream.close()
                    XposedBridge.log("[$TAG] [HOST] root 拉起超时，已强制中止")
                    false
                }
            } catch (ie: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
                false
            }
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [HOST] root 拉起分支异常: ${t.message}")
            false
        }
    }

    /**
     * 主进程在口令命中开启响应窗口后，广播通知同 UID 其他进程（:core）同步开窗。
     */
    private fun broadcastResponseWindow() {
        try {
            val ctx = appContext() ?: return
            val intent = Intent(ACTION_RESPONSE_WINDOW)
                .setPackage(PACKAGE)  // 定向到小爱自身 UID，避免广播外泄
            ctx.sendBroadcast(intent)
            XposedBridge.log("[$TAG] [WIN-SYNC] 已广播响应窗口到同 UID 进程")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [WIN-SYNC] 广播响应窗口失败: ${t.message}")
        }
    }
    /**
     * 语音通道静音 hook：hook android.media.AudioTrack 的 write() / play() / stop() / release()。
     *
     * 关键诊断（dumpsys audio 实测铁证）：
     * 小爱 LLM 语音播报的 AudioTrack 全部在主进程 com.miui.voiceassist 创建（非 :core），
     * 且这些是应用启动时预创建的池化实例（state: idle/stopped），由 MIX 引擎经 native 层
     * 直接灌 PCM 并 start()，绕过 Java AudioTrack.play() 门面——故原 play() 单一 hook 零命中。
     *
     * 因此重构为覆盖真实路径：
     *   1) write(byte[]/short[]/ByteBuffer, ...) 全重载：这是 Java 侧灌入 PCM 的必经入口，
     *      窗口内将写入数据置空/长度置 0，从源头掐断声音（比 setVolume 更彻底，且对
     *      native 直接 start() 的池化实例同样有效——只要它从 Java 回调写 PCM 就必经 write()）。
     *   2) play() / start() 兜底：窗口内 setVolume(0f) 静音实例。
     *   3) 窗口内对所有可触及的 AudioTrack 实例压实音量 0f，确保 native 路径也被覆盖。
     *   4) stop() / release() 恢复音量（供下一个指令正常出声）。
     *
     * 副作用说明：窗口内所有 AudioTrack 写入都会被静音。这在小爱应答小爱原文播报时是
     * 期望行为；若系统音效（如按键音）恰在窗口内触发也会被静音，但窗口仅 6s 且通常伴随
     * 车辆操作，影响有限、可接受。
     */
    private fun hookAudioMute() {
        try {
            val atClass = AudioTrack::class.java

            // ===== write() 全重载：Java 灌 PCM 的必经入口（核心拦截） =====
            // write(byte[], int, int)、write(short[], int, int)、
            // write(ByteBuffer, int, int)、write(float[], int, int, int) 等。
            // 窗口内将 bytes 写入长度置 0，既不播放又避免改体积性能开销。
            XposedBridge.hookAllMethods(
                atClass,
                "write",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val at = param.thisObject as? AudioTrack
                            // 无论窗口状态都登记实例：play/start 的登记覆盖池化实例的构造，
                            // 这里 write() 再兜底登记，确保开窗清扫时能枚举到所有曾播放过的轨道。
                            if (at != null) allAudioTracks.add(at)
                            // ownSoundActive：模块自身播系统提示音期间放行，避免自家声音被自己静音
                            if (ownSoundActive) return
                            if (!inResponseWindow()) return
                            // 明确用 setResult 设置返回值（模拟"无数据可写"），比直接赋 param.result 更可靠。
                            // write() 各重载返回 int，传整数 0 即可。
                            param.setResult(0)
                            // 无条件命中日志：窗口期短、命中次数可控，节流日志会掩盖实际命中（此前
                            // mutedWriteCount%200 导致 write() 可能已拦截却不打日志，误判为"零命中"）。
                            XposedBridge.log("[$TAG] [MUTE] write() 已打断 count=${++mutedWriteCount} at=${at?.hashCode()} len=${param.args?.lastOrNull()}")
                            // 同步压低该实例音量作为双保险
                            if (at != null) {
                                at.setVolume(0f)
                                mutedAudioTracks.add(at)
                            }
                        } catch (t: Throwable) {
                            try { param.setResult(0) } catch (e: Throwable) { /* ignore */ }
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] 已 hook AudioTrack.write（全重载，窗口内掐断 PCM 播放）")

            // ===== play() / start()：播放启动兜底，窗口内静音 =====
            // start() 是 AudioTrack native 播放的真正入口（play() 内部调 start()），
            // 双 hook 确保无论走哪个门面都被覆盖。
            val mutePlay = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val at = param.thisObject as? AudioTrack ?: return
                        // 无论窗口状态都登记实例：覆盖池化实例在窗口开启前就已 start 的情形，
                        // 使 setText 开窗时的全量清扫能枚举到它并立刻压静。
                        allAudioTracks.add(at)
                        if (!inResponseWindow()) return
                        at.setVolume(0f)
                        mutedAudioTracks.add(at)
                        XposedBridge.log("[$TAG] [MUTE] 已静音 AudioTrack@${at.hashCode()}")
                    } catch (t: Throwable) { /* 静默 */ }
                }
            }
            XposedBridge.hookAllMethods(atClass, "play", mutePlay)
            try {
                XposedBridge.hookAllMethods(atClass, "start", mutePlay)
            } catch (t: Throwable) { /* start 可能因版本不存在，忽略 */ }

            // ===== 构造函数 hook：登记纯池化创建、从未 play/write 过的实例 =====
            // 小爱在主进程启动时预创建一批池化 AudioTrack（idle/stopped），MIX 引擎 native 直接
            // start() + 灌 PCM，可能从不途经 Java play() 门面、写入也可能因窗口前提前发生而只在
            // write() 登记过一次。构造 hook 保证每个新实例在诞生即入 allAudioTracks，使开窗时的
            // muteAllActiveAudioTracks() 清扫能枚举到【全部】已知轨道（含纯池化、尚未播放的），
            // 不漏任何一条可能马上被 start() 灌播报的轨道。
            try {
                atClass.declaredConstructors.forEach { con ->
                    XposedBridge.hookMethod(
                        con,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val at = param.thisObject as? AudioTrack ?: return
                                    allAudioTracks.add(at)
                                } catch (t: Throwable) { /* 静默 */ }
                            }
                        }
                    )
                }
                XposedBridge.log("[$TAG] 已 hook AudioTrack 构造函数（全量登记实例入 allAudioTracks）")
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] hook AudioTrack 构造函数失败（不影响 write/play/start 拦截）: ${t.message}")
            }

            // 恢复：stop()/release() 后取消静音（供下一个指令正常出声）
            val restore = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val at = param.thisObject as? AudioTrack ?: return
                        if (mutedAudioTracks.remove(at)) {
                            try { at.setVolume(1f) } catch (e: Throwable) { /* ignore */ }
                            XposedBridge.log("[$TAG] [MUTE] 已恢复 AudioTrack@${at.hashCode()}")
                        }
                    } catch (t: Throwable) { /* 静默 */ }
                }
            }
            XposedBridge.hookAllMethods(atClass, "stop", restore)
            XposedBridge.hookAllMethods(atClass, "release", restore)
            XposedBridge.log("[$TAG] 已 hook AudioTrack.write/play/start/stop/release（响应窗口内静音小爱原文播报）")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] hook AudioTrack 静音失败: ${t.message}")
        }
    }

    /**
     * 全量清扫已知 AudioTrack 实例，把所有正处于 PLAYING 状态者立即压实音量 0f。
     *
     * 解决的问题（针对"语音回复一点点然后才静音"泄漏）：
     * 小爱 LLM 语音的池化 AudioTrack 在 ASR 识别后立刻被 MIX 引擎 native start() 灌 PCM 播报，
     * 这一时点【先于】口令 setText 触发点（responseWindowUntil 此刻才开启）。窗口开启前已 start、
     * 已写入开头若干 PCM 块的实例，其开头声音已被 Android 混音段播出，既有的 write()/setVolume
     * 拦截（都以 inResponseWindow() 为前提）拦不下——于是漏出"好的，正在为您…"几个字。
     *
     * 方案：口令命中开窗的同一时刻调用本方法，遍历 allAudioTracks 把所有 PLAYSTATE_PLAYING 的
     * 实例直接 setVolume(0f)。Android 音量在混音段即时生效（音量是每条 AudioTrack 独立生效的），
     * 能掐掉已进入硬件缓冲的开头碎片，立即止住泄漏；被压静实例进 mutedAudioTracks，由既有
     * stop()/release() hook 统一恢复音量（下一个指令正常出声）。窗口内后续再 write()/play()/start()
     * 的轨道则仍由既有 inResponseWindow() hook 掐断，二者互补，杜绝任何一种先于窗口的漏声。
     *
     * 线程安全：allAudioTracks 为 ConcurrentHashMap.newKeySet，可安全遍历；setVolume 是线程安全的
     * 快速操作，且本方法只在 UI/setText 回调线程调用，与被 hook 的音视频线程无死锁风险。
     */
    private fun muteAllActiveAudioTracks() {
        try {
            var muted = 0
            var playing = 0
            for (at in allAudioTracks) {
                try {
                    // AudioTrack.PLAYSTATE_PLAYING == 3（小爱设备上实测），直接按值判断以兼容各版本常量缺失
                    if (at.playState == 3) {
                        playing++
                        at.setVolume(0f)
                        if (mutedAudioTracks.add(at)) muted++
                    }
                } catch (t: Throwable) { /* 单个实例失败不影响其余 */ }
            }
            XposedBridge.log(
                "[$TAG] [MUTE] 开窗清扫完成：allAudioTracks=${allAudioTracks.size} playing=$playing 新压静=$muted " +
                    "（泄漏开头片段已被音量压静，窗口内后续 write/play/start 由 hook 兜底拦截）"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [MUTE] 开窗清扫异常: ${t.message}")
        }
    }

    /**
     * 模块主动反馈「已成功」：播放系统通知提示音（方案 B）。
     *
     * 背景：设备上无标准 Android TTS 引擎（tts_default_synth 为 null），小爱靠 native 灌 PCM
     * 走 AudioTrack 播报。原「模块自建 TextToSpeech 播『已成功』」在此设备无法初始化（onInit 恒
     * ERROR、10s 超时），因此改用 MediaPlayer 播系统默认通知提示音（RingtoneManager）作语音反馈。
     * 语义完整仍由屏幕文字「已成功」补充（文字替换已生效）。
     *
     * 关键：播放前置 ownSoundActive=true 放行模块自身 AudioTrack.write（否则会被 hookAudioMute
     * 在窗口内掐断），onCompletion/onError/超时兜底恢复 false。提示音极短（<1s），豁免窗口小。
     */
    private fun playSuccessTone() {
        // 【诊断】无条件入口日志：确认 playSuccessTone 是否真正被调用
        XposedBridge.log("[$TAG] [TONE] 进入 playSuccessTone")
        try {
            val ctx = appContext()
            if (ctx == null) {
                // 关键诊断：appContext() 为 null 是此前"无任何 [TONE] 日志"的最大嫌疑
                XposedBridge.log("[$TAG] [TONE] appContext() 返回 null，无法播报（直接 return）")
                return
            }
            // 优先使用用户配置的提示音 URI（自定义或建议铃声），为空/解析失败回退系统默认
            var uri: Uri? = null
            var uriSource = "system-default"
            try {
                val cfgUri = NokeyConfig.loadToneUri()
                if (!cfgUri.isNullOrBlank()) {
                    val parsed = Uri.parse(cfgUri.trim())
                    if (parsed != null) {
                        uri = parsed
                        uriSource = "user-config"
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] [TONE] 解析自定义提示音 URI 失败，回退系统默认: ${t.message}")
            }
            if (uri == null) {
                uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                uriSource = "system-default"
            }
            XposedBridge.log("[$TAG] [TONE] 提示音 URI($uriSource)=${uri?.toString() ?: "(null)"}")
            if (uri == null) {
                XposedBridge.log("[$TAG] [TONE] 无可用提示音 URI，跳过播报")
                return
            }
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setDataSource(ctx, uri)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] [TONE] 设置提示音数据源失败: ${t.message}")
                try { mp.release() } catch (e: Throwable) { /* ignore */ }
                return
            }
            var released = false
            val finish = {
                ownSoundActive = false
                if (!released) {
                    released = true
                    try { mp.release() } catch (e: Throwable) { /* ignore */ }
                }
            }
            try {
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, _, _ -> finish(); true }
                // 播放前置豁免，放行模块自身 AudioTrack.write
                ownSoundActive = true
                mp.prepare()
                mp.start()
                XposedBridge.log("[$TAG] [TONE] 已播放系统提示音（「已成功」语义）")
                // 兜底：提示音最长约 5s，届时无论如何复位豁免标志，防止误放行小爱后续播报
                Thread {
                    try { Thread.sleep(5000L) } catch (t: Throwable) { /* ignore */ }
                    finish()
                }.start()
            } catch (t: Throwable) {
                finish()
                XposedBridge.log("[$TAG] [TONE] 播放提示音失败: ${t.message}")
            }
        } catch (t: Throwable) {
            ownSoundActive = false
            XposedBridge.log("[$TAG] [TONE] 主动反馈播放失败: ${t.message}")
        }
    }

    /**
     * 语音输入门禁 hook：识别"真实语音识别文本进入 UI"的时机。
     *
     * 反编译 com.miui.voiceassist 确认：真实语音识别结果经抽象基类 lg0.d 的两个
     * 子类进入 UI 渲染 ——
     *   lg0.h（DelayChangeAsrImpl）：
     *       delayShowAsrWithAnim(String)   —— 带动画展示识别文本
     *       delayShowAsrWithoutAnim(String) —— 不带动画展示识别文本
     *   lg0.i（DelayChangeAsrNoShowImpl）：
     *       delayShowAsrWithoutAnim(String) —— 不带动画展示识别文本
     *       （其 delayShowAsrWithAnim 未 override，继承自 lg0.h，故无需重复 hook）
     *
     * 这三个方法的入参 String 即真实语音识别文本（setAsrType(a.USER_ASR_RESULT)）。
     * 它们只被真实 ASR 结果触发；历史会话卡片渲染（FlowConversationAdapter →
     * FlowTemplateToastCard → Chann.setText）不经过这些方法。
     *
     * 因此：任一方法被调用即刷新 lastSpeechAsrTime，作为 setText 门禁判定的
     * "真实语音信号"。hook 失败（类不存在/混淆改名）时 lastSpeechAsrTime 恒为 0，
     * setText 门禁自动退化为 STRONG_STARTUP_GUARD_MS 兜底，不影响功能可用性。
     */
    private fun hookSpeechAsrGate(appClassLoader: ClassLoader) {
        // 打开语音信号开关的信号源：记录时间戳即可
        val signalUpdater = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                lastSpeechAsrTime = System.currentTimeMillis()
                val t = param.args.firstOrNull()?.toString()
                XposedBridge.log(
                    "[$TAG] [ASR-GATE] 真实语音识别文本进入 UI：「${truncate(t ?: "(null)")}」" +
                        " lastSpeechAsrTime=$lastSpeechAsrTime"
                )
            }
        }

        // 候选类：lg0.h（DelayChangeAsrImpl）、lg0.i（DelayChangeAsrNoShowImpl）
        // 用运行时 Class.forName 加载（编译期不可见），逐个尝试，失败不致命。
        val candidates = mapOf(
            "lg0.h" to arrayOf("delayShowAsrWithAnim", "delayShowAsrWithoutAnim"),
            "lg0.i" to arrayOf("delayShowAsrWithoutAnim")
        )
        candidates.forEach { (clsName, methods) ->
            try {
                val cls = Class.forName(clsName, false, appClassLoader)
                methods.forEach { method ->
                    try {
                        XposedHelpers.findAndHookMethod(cls, method, String::class.java, signalUpdater)
                        XposedBridge.log("[$TAG] [ASR-GATE] 已 hook $clsName.$method(String)")
                    } catch (m: Throwable) {
                        XposedBridge.log("[$TAG] [ASR-GATE] hook $clsName.$method 失败: ${m.message}")
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] [ASR-GATE] 加载 $clsName 失败（门禁退化为长窗兜底）: ${t.message}")
            }
        }
    }

    private fun truncate(s: String): String =
        if (s.length <= 40) s else s.take(40) + "…"

    /**
     * 从 NokeyConfig.loadSuccessTexts() 读取自定义「已成功」文案列表并随机取一条。
     * 空列表/读取失败时回退默认「已成功」（保持历史行为）。
     */
    private fun pickSuccessText(): String {
        return try {
            val texts = NokeyConfig.loadSuccessTexts()
            if (texts.isNotEmpty()) {
                texts[kotlin.random.Random.nextInt(texts.size)]
            } else {
                "已成功"
            }
        } catch (t: Throwable) {
            "已成功"
        }
    }

    /**
     * 判定一段文本是否为"小爱对指令的回应"，若是则把 param.args[0] 替换为「已成功」。
     * 适用于 setText（afterHook 改前参同样生效）与 TTS speak（beforeHook）。
     *
     * 诊断：打印 TextView 身份 + 调用栈，用于定位可见气泡的真实渲染入口。
     */
    private fun replaceIfAiResponse(
        param: XC_MethodHook.MethodHookParam,
        s: String,
        thisObject: Any?
    ) {
        // 排除空/空白文本（容器初始化、占位符等，非任何实际内容）
        if (s.isBlank()) return
        // 排除匹配气泡本身（正是它的渲染才打开了窗口）
        if (s == lastTriggerText) return
        // 排除超长文本（对话列表整体重绘、长文章摘录等），避免误伤
        if (s.length > MAX_RESPONSE_LEN) return

        // 诊断信息：TextView 身份 + 是否可见 + 调用栈
        val diag = describeTarget(thisObject)
        val picked = pickSuccessText()
        XposedBridge.log("[$TAG] 屏蔽小爱回应「${s}」→ 替换为「$picked」 $diag")
        logStackShort()

        param.args[0] = picked

        // ===== afterHook 主动刷新修复 =====
        // 关键 bug 修复：本 hook 走 afterHookedMethod —— setText 早已用原始文本执行完毕、
        // UI 已渲染，此时改 param.args[0] 不影响屏幕上已显示的内容（这与预期相悖）。
        // 因此对本 TextView 在改参后主动 setText(picked) 强制刷新 UI，才能真正改变可见气泡。
        if (thisObject is TextView && !forceFlushing) {
            forceFlushing = true
            try {
                (thisObject as TextView).setText(picked)
            } catch (t: Throwable) {
                // 静默：主动刷新失败不影响主流程
            } finally {
                forceFlushing = false
            }
        }
    }

    /** 生成目标对象（TextView）的诊断描述 */
    private fun describeTarget(obj: Any?): String {
        if (obj == null) return "(this=null)"
        val sb = StringBuilder()
        val cls = obj.javaClass
        sb.append("[").append(cls.name)
        // 尝试读取 isShown / parent / 资源ID 等
        try {
            if (obj is TextView) {
                val tv: TextView = obj
                sb.append(" shown=").append(tv.isShown)
                    .append(" w=").append(tv.width).append('x').append(tv.height)
                    .append(" curTextLen=").append(tv.text?.length ?: 0)
                val pid = tv.id
                if (pid != -1) { sb.append(" id=0x").append(Integer.toHexString(pid)) }
                val parent = tv.parent
                sb.append(" parent=").append(parent?.javaClass?.simpleName ?: "null")
            }
        } catch (t: Throwable) { sb.append(" (desc err)") }
        sb.append("]")
        return sb.toString()
    }

    /** 打印短调用栈（前若干帧），用于定位代码路径 */
    private fun logStackShort() {
        try {
            val st = Thread.currentThread().stackTrace
            val n = minOf(st.size, 16)
            val sb = StringBuilder("  stack:")
            for (i in 1 until n) {
                val e = st[i]
                val cn = e.className
                if (cn.startsWith("java.lang") || cn.startsWith("de.robv") || cn == "android.widget.TextView") continue
                sb.append(" <- ").append(e.className.substringAfterLast('.')).append('.').append(e.methodName)
            }
            XposedBridge.log("[$TAG]$sb")
        } catch (t: Throwable) {
            // 静默
        }
    }

    /** 是否正处于响应窗口内 */
    private fun inResponseWindow(): Boolean =
        responseWindowUntil > 0 && System.currentTimeMillis() < responseWindowUntil

    /**
     * 仅发送 NOKEY_CMD 广播（车辆操作），不在这里开启响应窗口。
     *
     * 响应窗口已由 keyword 命中处无条件开启（以实现"对关键词回复直接屏蔽"），
     * 这里专注广播发送，并保留防抖：与上次【成功发出】的同命令且在防抖窗内则忽略，
     * 避免系统反复渲染同名气泡时对车辆连续重复下发指令。
     *
     * 注意：防抖时间源必须是"上一次成功发送"（lastSentTime），而非"门禁通过、准备发送"
     * 的时刻。否则每次进入此方法时 now-lastSentTime≈0 恒小于防抖窗，广播会被静默吞掉。
     */
    private fun sendCommandOnly(code: Int, sourceText: String) {
        val now = System.currentTimeMillis()

        // 防抖：与上次【成功发出】同命令，且在防抖窗内，忽略重复下发
        if (code == lastSentCommand && now - lastSentTime < DEBOUNCE_MS) {
            XposedBridge.log(
                "[$TAG] 防抖命中：code=$code 距上次成功发送 $DEBOUNCE_MS ms 内，跳过重复下发"
            )
            return
        }

        // 获取进程级全局 Context —— 反射调用 ActivityThread.currentApplication()
        // （ActivityThread 是 hidden API，编译期不可见，运行时一定可用）
        val context = appContext() ?: run {
            XposedBridge.log("[$TAG] 获取 Application Context 失败，无法发送广播")
            return
        }

        try {
            // 根因修复：改发模块自建【动态】广播接收器（RECEIVER_EXPORTED）。
            //
            // 此前曾改用乘趣原生静态接收器 WidgetBroadcastReceiver 兜底，经逆向确认该
            // 组件声明为 android:exported=false。Android 8+ 对跨 UID（小爱 uid 10175 →
            // 乘趣 uid 10283）向 exported=false 组件发起显式广播一律静默丢弃（sendBroadcast
            // 异步不抛异常），这就是"小爱日志已发送但乘趣零探针、前台后台都不行"的根因。
            //
            // 方案 A：改投递到模块在乘趣进程内动态注册且 RECEIVER_EXPORTED 的接收器
            // （MainHook Application.attach 时注册，action = ACTION_NOKEY_CMD）。
            // setPackage 定向到乘趣避免歧义，RECEIVER_EXPORTED 允许外部进程（小爱）投递。
            // 该通道在乘趣进程存活时一定可达（前台、后台皆然）。
            //
            // 边界：乘趣被 force-stop 后进程被杀、动态接收器随之消失，广播会丢失——
            // 这正是配套"隐藏后台卡片"开关的意义：避免用户在最近任务里手动划掉乘趣，
            // 使其常驻后台、动态通道持续可用。
            val intent = Intent(MainHook.ACTION_NOKEY_CMD)
                .setPackage(MainHook.PACKAGE_TARGET)
                .putExtra(MainHook.EXTRA_COMMAND, code)
            // ===== 核心顺序重排：先确保乘趣存活，再发广播，一次到位 =====
            // 用户真实诉求："唤醒小爱 → 立马拉取进程并发送广播"，不要第二次喊话。
            // 原实现是【广播先发、进程后拉】：乘趣刚死/冷启动时接收器未注册，首条广播必丢，
            // 表现即"第一次喊拉起了进程但广播没执行"。
            //
            // 现在：先查乘趣进程是否存活。若已死，立即拉起（ensureNokeyAlive 内部异步子线程执行，
            // 并返回 wasNotAlive=true 告知调用方"乘趣此刻不在、接收器未就绪"）；随后再发首条广播。
            // 随后 tryRetryColdStartBroadcast 依据 wasNotAlive 判定：只要进程曾死，就无条件在
            // 接收器就绪窗口后补投一次，确保冷启动场景【同一轮调用内】广播一定到位，无需用户再喊。
            val wasNotAlive = ensureNokeyAlive(context)
            context.sendBroadcast(intent)
            // 仅在此处（真正发送成功后）才更新防抖状态，确保防抖时间源正确
            lastSentCommand = code
            lastSentTime = now
            // ===== ACK 确认等待（根治锁屏/灭屏广播静默丢失） =====
            // 广播已发出，启动确认窗口：乘趣执行成功会回发 ACTION_NOKEY_ACK 撤销等待；
            // 若超时未确认（典型：锁屏后乘趣被冻结 frozen，sendBroadcast 投递被系统挂起不执行），
            // checkAckRetry 会 root 强制唤醒 + 重发。此机制与下方冷启动补偿（tryRetryColdStartBroadcast）
            // 协同：冷启动补偿管"首轮补齐"，ACK 重试管"收不到执行确认就主动重发"，双保险。
            scheduleAckRetry(context, code)
            // ===== 冷启动广播丢失补偿 =====
            // 竞态：实时拉起乘趣后，进程冷启动（Application.attach + 动态接收器注册）需 ~1~1.5s，
            // 首条广播发在接收器就绪前会被 Android 静默丢弃 → "第一次唤醒广播没执行、要喊第二次"。
            // 修正后的判定：不再依赖 hostLastRelaunchTime 的新旧（该值可能是 30s 限流窗口前的旧时间戳，
            // 会漏掉"乘趣已死但距上次拉起很久"的场景）——只要 ensureNokeyAlive 返回 wasNotAlive=true
            // （乘趣进程此刻不在），就无条件安排一次延迟重发，等接收器就绪后再补投，一次到位。
            if (wasNotAlive) {
                tryRetryColdStartBroadcast(context, code)
            }
            // 成功下发后弹出 Toast 反馈（内容取自定义成功文案）。
            // appContext() 返回 Application 级 Context，且此处可能运行在 hook 回调线程，
            // 因此用主线程 Handler 包装，规避部分系统版本 Toast 不显示的问题。
            try {
                val msg = pickSuccessText()
                Handler(Looper.getMainLooper()).post {
                    try {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    } catch (t: Throwable) {
                        XposedBridge.log("[$TAG] Toast 展示失败: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] Toast 构建失败: ${t.message}")
            }
            // 命令执行成功后置反馈旁路：弹出系统通知 + 播放提示音。
            // 该旁路【不受】档位 C 全静默门控（loadSilentMode）约束——静默只屏蔽小爱自身的
            // 乱反馈，而这里是模块用自己的方式告知用户"命令已成功下发执行"。
            // 全程独立 try-catch，任何通知/提示音异常都不影响既有静默执行链路。
            try {
                showSuccessNotification(context, pickSuccessText(), code)
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] 成功通知展示失败: ${t.message}")
            }
            try {
                playSuccessTone()
            } catch (t: Throwable) {
                XposedBridge.log("[$TAG] 成功提示音播放失败: ${t.message}")
            }
            XposedBridge.log("[$TAG] 口令命中「${CommandMatcher.nameOf(code)}」 code=$code 「$sourceText」→ 已发送 NOKEY_CMD，已开启响应窗口")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 发送广播失败: ${t.message}")
        }
    }

    /**
     * 冷启动广播丢失补偿：若本次 NOKEY_CMD 广播发生在乘趣刚被拉起的就绪窗口内，
     * 动态接收器大概率尚未注册、广播被静默丢弃，则安排一次延迟重发（等接收器就绪后再投递一次）。
     *
     * 判定依据：距乘趣最近一次成功拉起（hostLastRelaunchTime）不足 NOKEY_RECEIVER_READY_MS，
     * 说明进程正处于冷启动早期、接收器几乎必然未就绪，需要补偿重发。
     *
     * 安全与去重：
     * - 重发只补投一条 NOKEY_CMD 广播（与正常下发同一命令、同一 setPackage/EXTRA），
     *   不重放 Toast/通知/提示音等反馈链，避免重复打扰。
     * - 用 pendingRetryCommand/pendingRetryAt 去重：同一命令只排队一次，且若又收到新的
     *   相同命令重排队则沿用早前排队，防止小爱连续渲染多个历史气泡时重复排队刷屏。
     * - 重发走独立 Handler 延迟执行，不占住 hook 回调线程。
     */
    private fun tryRetryColdStartBroadcast(context: Context, code: Int) {
        try {
            val nowRt = SystemClock.elapsedRealtime()
            // 判定修正：调用方（sendCommandOnly）已在 ensureNokeyAlive 返回 true（进程此刻不在，
            // 冷启动必丢首条广播）时才进入本方法，因此这里【不再】依赖 hostLastRelaunchTime 的新旧
            // 时序判定。原判定 `nowRt - hostLastRelaunchTime >= NOKEY_RECEIVER_READY_MS` 会因
            // hostLastRelaunchTime 可能停留在 30s 限流窗口前的旧值而漏掉"乘趣已死但距上次拉起很久"
            // 的场景——恰是"第一次唤醒拉了进程广播却没执行"的根因。现在进入本方法即意味着需要补偿，
            // 只保留去重守卫即可。

            // 去重：同一命令已排队则跳过（现有排队会在就绪窗口后补投一次，无需重复排队）。
            if (pendingRetryCommand == code && nowRt - pendingRetryAt < NOKEY_RECEIVER_READY_MS * 2L) {
                XposedBridge.log("[$TAG] 冷启动广播补偿：code=$code 已在重发队列中，跳过重复排队")
                return
            }

            pendingRetryCommand = code
            pendingRetryAt = nowRt
            XposedBridge.log(
                "[$TAG] 检测广播落在乘趣冷启动就绪窗内（距拉起 ${nowRt - hostLastRelaunchTime}ms）→ " +
                    "安排 ${NOKEY_RECEIVER_READY_MS}ms 后延迟重发 code=$code"
            )

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // 重发前确认：直接补投，不重走 sendCommandOnly（避免防抖/反馈链副作用）。
                    val retryCtx = appContext() ?: return@postDelayed
                    val retryIntent = Intent(MainHook.ACTION_NOKEY_CMD)
                        .setPackage(MainHook.PACKAGE_TARGET)
                        .putExtra(MainHook.EXTRA_COMMAND, code)
                    retryCtx.sendBroadcast(retryIntent)
                    XposedBridge.log("[$TAG] 冷启动广播补偿：已延迟重发 NOKEY_CMD code=$code")
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] 冷启动广播补偿重发异常: ${t.message}")
                } finally {
                    pendingRetryCommand = -1
                    pendingRetryAt = 0L
                }
            }, NOKEY_RECEIVER_READY_MS)
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 冷启动广播补偿安排异常: ${t.message}")
        }
    }

    /**
     * 命令执行成功后：【主动弹出系统通知】告知用户，作为对小爱"乱反馈闭嘴"的补偿反馈。
     *
     * 设计要点：
     * - 在【小爱进程】（com.miui.voiceassist）内直接弹，POST_NOTIFICATIONS 权限实测已授予
     *   （granted=true, GRANTED_BY_DEFAULT），Android 13+ 通知可靠显示，无需改 manifest。
     * - 优先使用系统图标 android.R.drawable.ic_dialog_info，规避跨包资源引用问题。
     * - 标题复用自定义「已成功」文案（pickSuccessText），内容为命令名（CommandMatcher.nameOf）。
     * - 固定 notification id（SUCCESS_NOTIFY_ID）复用，新通知覆盖旧通知，避免通知栏堆积。
     * - API 26+ 需先建 NotificationChannel；API 24-25 走旧版 Builder（无需 channel）。
     * - 全程 try-catch，任何异常都不得破坏既有静默执行链路。
     */
    private fun showSuccessNotification(context: Context, msg: String, code: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channelId = "nokey_cmd_success"
        val cmdName = runCatching { CommandMatcher.nameOf(code) }
            .getOrElse { ("0x" + Integer.toHexString(code)) }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 幂等创建通知渠道（已存在则静默复用，不重复创建）
                val existing = nm.getNotificationChannel(channelId)
                if (existing == null) {
                    val channel = NotificationChannel(
                        channelId,
                        "命令执行成功",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                    channel.description = "小爱指令拦截模块：命令成功执行的通知"
                    nm.createNotificationChannel(channel)
                }
            }
            // API 26+：需要传 channelId；API 24-25：无 channel 概念
            val builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(context, channelId)
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(context)
                }
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(msg)
                    .setContentText("命令「$cmdName」已执行成功")
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setTicker(msg)
            nm.notify(SUCCESS_NOTIFY_ID, builder.build())
            XposedBridge.log("[$TAG] [NOTIFY] 已弹出命令成功通知「$cmdName」")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] [NOTIFY] 弹通知失败: ${t.message}")
        }
    }

    /**
     * 命令执行成功通知的固定 ID（复用覆盖，避免通知栏堆积）。
     */
    private const val SUCCESS_NOTIFY_ID = 0x4E4B // "NK" —— 与模块关联的稳定通知 id

    const val PACKAGE = "com.miui.voiceassist"

    /**
     * 通过反射获取全局 Application Context。
     * 规避 ActivityThread 这种 hidden API 的编译期不可见问题。
     */
    private fun appContext(): android.content.Context? {
        return try {
            val clazz = Class.forName("android.app.ActivityThread")
            val method = clazz.getMethod("currentApplication")
            method.invoke(null) as? android.content.Context
        } catch (t: Throwable) {
            null
        }
    }
}