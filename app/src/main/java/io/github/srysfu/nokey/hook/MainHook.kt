package io.github.srysfu.nokey.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口。
 *
 * 目标应用：com.ingeek.nokey（某数字钥匙/车辆控制 App，代码经 R8 混淆）
 * 最优 Hook 点：com.ingeek.nokey.component.launcherWidget.k.a(int commandCode, h callback)
 *   - 完全同步非挂起方法
 *   - k.<init>()V 无参构造
 *   - 内部自行创建协程处理所有 Continuation，且会自动获取 sn
 *   - h 为回调接口（begin/a()/a(Z)/b(Z) 四个方法），传入匿名实现即可
 *
 * 触发链路：小爱同学/Tasker 通过显式广播 ACTION_NOKEY_CMD（携带 EXTRA_COMMAND 命令码）
 *   → 本模块在 com.ingeek.nokey 进程中注册的接收器 → MainHook.call(...) → k.a(int, h)
 */
class MainHook : IXposedHookLoadPackage {

    // ---------- 保活第二重保险：动态广播接收器状态 ----------
    // 供 KeepAliveService.onStartCommand hook 周期性补注册（防进程被杀导致动态接收器丢失）。
    // 由于 receiver/filter 无法全局序列化持有，这里仅缓存必要信息，由 ensureDynamicReceiver()
    // 依据 AppContext 就地重建注册。真正保证"每次进程重启接收器都在"的是 attach 阶段的注册 +
    // START_STICKY 拉起的服务循环；本字段用于 onStartCommand 心跳里的去重与补挂。
    private var dynamicReceiverRef: BroadcastReceiver? = null
    private var dynamicFilterRef: IntentFilter? = null
    private var registeredReceiverAppContext: Context? = null

    // ---------- 保活第三重保险：KeepAliveService 被系统销毁后的自愈重拉状态 ----------
    // 已实证：服务虽调用 startForeground() 但因未被系统计入 FGS 集合（startForegroundCount:0，
    // fgs 列表空，销毁伴随 Cancel FGS notification Reason:8），系统主线程 Handler 通过
    // ActivityThread.handleStopService 的回调将其 destroy（而非 START_STICKY 自动重启）。
    // 这里维护每个服务生命周期内的重拉次数与冷却时间戳，防止 afterHookedMethod 自愈重拉死循环。
    private var keepAliveRelReqCount = 0
    private var keepAliveRelReqLastTs = 0L
    // 每个服务生命周期的最大自愈重拉次数（超出后放弃，交由 START_STICKY 系统兜底）
    private val KEEP_ALIVE_REL_MAX = 3
    // 两次自愈重拉之间的最小冷却间隔（毫秒），防止系统连续 destroy 引发风暴
    private val KEEP_ALIVE_REL_COOLDOWN_MS = 3000L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {

        // ===== 按目标进程分发 =====
        // 小爱同学进程：hook TextView.setText，识别口令后从该进程发送 NOKEY_CMD 广播（路径 A1）
        if (lpparam.packageName == VoiceAssistHook.PACKAGE) {
            VoiceAssistHook.hook(lpparam)
            return
        }

        if (lpparam.packageName != PACKAGE_TARGET) return
        log("== 已加载 $PACKAGE_TARGET ==")

        val classLoader = lpparam.classLoader

        try {
            // ---------- 1) Hook k.a(int, h)，带日志 ----------
            val kClazz = XposedHelpers.findClass(
                "com.ingeek.nokey.component.launcherWidget.k",
                classLoader
            )
            val hClazz = XposedHelpers.findClass(
                "com.ingeek.nokey.component.launcherWidget.h",
                classLoader
            )

            XposedHelpers.findAndHookMethod(
                kClazz,
                "a",
                Int::class.javaPrimitiveType,
                hClazz,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = param.args[0] as Int
                        log("k.a 被调用，commandCode=$cmd (0x${Integer.toHexString(cmd)})")
                    }
                }
            )
            log("成功 hook k.a(int, h)")

            // ---------- 1.2) 合并的独立破解类 hook（皮肤解锁 + 绕过校验）----------
            // 由 /data/local/tmp/nokey_cfg.json 中的两路开关控制：skinUnlock / bypassCheck。
            // 开关读取在运行期进行（文件不存在/未开启时回退 false，不启用破解）；
            // NokeyBypassHook.hook 内部三个 hook 函数各自 try-catch，失败仅记日志，
            // 不影响基线 k.a / WidgetBroadcastReceiver / Application.attach 等 hook。
            NokeyBypassHook.hook(
                classLoader,
                NokeyConfig.loadSkinUnlock(force = true),
                NokeyConfig.loadBypassCheck(force = true)
            )

            // ---------- 1.5) Hook 原生静态 WidgetBroadcastReceiver.onReceive 探针 ----------
            // 目的：静态兜底通道的关键验证。LogUtils 日志不一定走 logcat，无法确定 receiver
            // 是否真正被系统投递执行。此处 hook onReceive 打印确定性日志（无论命令码是否为 0）。
            // 注意 onReceive 在广播处理线程回调，即使 cmd=0 被忽略分支 return，能走到这里
            // 就证明"静态组件 + FLAG_INCLUDE_STOPPED_PACKAGES 拉起进程 → 系统投递 → onReceive 执行"
            // 全链路走通。
            try {
                XposedHelpers.findAndHookMethod(
                    "com.ingeek.nokey.component.launcherWidget.WidgetBroadcastReceiver",
                    classLoader,
                    "onReceive",
                    android.content.Context::class.java,
                    android.content.Intent::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val intent = param.args[1] as? Intent
                                val action = intent?.action ?: "null"
                                val cmd = intent?.getIntExtra(STATIC_EXTRA_COMMAND, -2) ?: -2
                                log("[WidgetRecv] onReceive 执行 action=$action cmd=$cmd （静态兜底通道命中）")
                            } catch (t: Throwable) {
                                log("[WidgetRecv] onReceive 探针读取异常: ${t.message}")
                            }
                        }
                    }
                )
                log("成功 hook WidgetBroadcastReceiver.onReceive")
            } catch (t: Throwable) {
                log("hook WidgetBroadcastReceiver.onReceive 失败: ${t.message}")
            }

            // ---------- 2) 注册广播接收器（在目标进程中）----------
            // 使小爱同学/Tasker 能通过显式广播触发命令。
            // handleLoadPackage 时机过早，ActivityThread.currentApplication() 通常返回 null，
            // 因此 hook Application.attach(Context)，在目标应用真正创建后再拿真实 Application 注册。
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val appContext = param.thisObject as? android.content.Context
                            if (appContext == null) {
                                log("Application.attach 回调中 thisObject 非 Context，跳过")
                                return
                            }
                            val receiver = object : BroadcastReceiver() {
                                override fun onReceive(context: Context, intent: Intent) {
                                    if (intent.action == ACTION_NOKEY_CMD) {
                                        val cmd = intent.getIntExtra(EXTRA_COMMAND, -1)
                                        if (cmd >= 0) {
                                            log("收到广播触发命令 commandCode=$cmd (0x${Integer.toHexString(cmd)})")
                                            // ACK 确认机制：命令真正执行成功后，回发 ACK 给发送端（小爱进程）。
                                            // 发送端据此撤销"确认等待窗口"，避免根因场景B（进程被 frozen、
                                            // 广播投递到冻结进程不执行）下的盲目重试；执行失败不回发 ACK，
                                            // 让发送端走 root 唤醒 + 重发闭环。
                                            val ok = call(classLoader, cmd)
                                            if (ok) {
                                                ackToVoiceAssist(context, cmd)
                                            } else {
                                                log("命令执行失败 cmd=$cmd，不回发 ACK（发送端将触发重试）")
                                            }
                                        } else {
                                            log("广播 EXTRA_COMMAND 缺失或非法")
                                        }
                                    }
                                }
                            }
                            val filter = IntentFilter().apply {
                                addAction(ACTION_NOKEY_CMD)
                            }
                            // 缓存 receiver/filter 到类字段（供保活心脏 KeepAliveService.onStartCommand
                            // 周期性补挂复用。同进程内 receiver 实例与 filter 可跨注册复用一实例，去重由
                            // ensureDynamicReceiver 依据 AppContext 单例 + 引用一致性保证重复注册被跳过）
                            dynamicReceiverRef = receiver
                            dynamicFilterRef = filter
                            // 先用普通注册完成后记录已注册状态（供保活心脏二次补挂去重）
                            ensureDynamicReceiver(appContext, receiver, filter)
                            log("已注册广播接收器: $ACTION_NOKEY_CMD")

                            // ---------- 2.5) 保活：按配置启动乘趣 KeepAliveService（前台服务）---------- 
                            // 需求：方案 A（动态广播）+ 隐藏后台卡片后，最大的软肋是用户一旦从系统设置
                            // 里"强行停止"或内存压力下被"省电优化"杀掉进程，动态接收器与前台任务全部
                            // 随之消失，方案 A 失效。乘趣自身（com.ingeek.nokey）在后台【不会主动】
                            // 拉起其保活服务 KeepAliveService（前置逆向结论：grep 全 smali 确认无自动
                            // 启动点；当前进程仅 Run 着 Agoo/Channel/Umeng 等服务）。
                            //
                            // 本模块作为常驻宿主，在乘趣进程 Application.attach 后读取 keepAlive 开关，
                            // 若开启则带 action="KeepAliveService.action.scan.start" 显式 startForegroundService
                            // 拉起乘趣内部的 KeepAliveService。
                            //
                            // 【已逆向锁定的启动契约】：
                            //   - KeepAliveService = com.ingeek.nokey.component.keepAlive.KeepAliveService
                            //     （Manifest 第 288 行，exported=false + foregroundServiceType="connectedDevice"）
                            //   - onCreate/onStartCommand 内置 ServiceCompat.startForeground
                            //     （id=0x134db47，type=0x10）
                            //   - 【单条件 100% 绕过自杀检查】：只要带 action="KeepAliveService.action.scan.start"
                            //     启动，就完全跳过 XConfig.getBoolean("fgs-ShowKeepNotification-cached-key")
                            //     的自杀判定（该判定仅在主动 startKeepAliveService 双条件链路上触发）
                            //   - START_STICKY：进程被杀后系统会自动重启该服务，天然形成保活闭环
                            //
                            // 【技术要点】KeepAliveService 虽 exported=false，但本 hook 运行在乘趣进程
                            // 【内部】（appContext 即乘趣 Application），从同进程上下文显式构造
                            // component=className 的 Intent 启动内部前台服务完全合法，无需 exported。
                            // startForegroundService 必须由前台上下文调用——Application.attach 回调正处
                            // 进程初始化前线绪（进程刚启动仍在前台窗口期），满足约束；用 try-catch 包裹并
                            // 记录结果，任何异常不影响主流程继续。
                            try {
                                val keepAliveOn = NokeyConfig.loadKeepAlive(force = true)
                                log("保活开关 keepAlive=$keepAliveOn")
                                if (keepAliveOn) {
                                    // 用显式 component 启动最稳：不依赖类解析（Intent 只要能按
                                    // package+className 定位组件即可），component 定位内部服务
                                    // 完全合法，避免 findClass 在目标 classLoader 上解析失败的问题。
                                    val kaIntent = Intent().apply {
                                        action = "KeepAliveService.action.scan.start"
                                        setClassName(
                                            PACKAGE_TARGET,
                                            "com.ingeek.nokey.component.keepAlive.KeepAliveService"
                                        )
                                    }
                                    // API 26+ 用 startForegroundService；低版本退回 startService。
                                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                                        appContext.startForegroundService(kaIntent)
                                    } else {
                                        appContext.startService(kaIntent)
                                    }
                                    log("保活: 已请求启动 KeepAliveService (action=scan.start, keepAlive=$keepAliveOn)")
                                }
                            } catch (t: Throwable) {
                                log("保活启动 KeepAliveService 异常: ${t.message}")
                            }

                            // ---------- 2.5.1) 保活第二重保险：KeepAliveService.onStartCommand 静态心脏 ----------
                            // 前置结论：乘趣保活服务 KeepAliveService 以 START_STICKY 模式运行，进程一旦被
                            // 系统或 OOM 杀掉，会由系统自动重启该服务并【重入 onStartCommand】，天然形成周期
                            // 触发点。我们利用它为"动态广播接收器"做周期性补挂（第二重保险）：
                            //   · 若进程完整存活，attach 阶段注册的接收器仍在工作，onStartCommand 的补挂
                            //     会被 ensureDynamicReceiver 的去重逻辑跳过（同一 AppContext + 引用一致）；
                            //   · 若接收器因任何异常被系统回收/注销，服务每次 onStartCommand 都会复用类字段
                            //     缓存的同一 receiver/filter 重新注册，补挂回生效状态；
                            //   · 即便 attach 阶段因时序/异常未注册成功，服务启动后 onStartCommand 也必然
                            //     触发，在此将被带回的接收器真正注册上去。
                            // 这样与 START_STICKY 服务重启闭环叠加，构成"动态接收器永久存活"的双保险。
                            // 说明：onStartCommand 签名 (Intent?, Int, Int): Int（intent 可空、flags、startId）。
                            try {
                                XposedHelpers.findAndHookMethod(
                                    "com.ingeek.nokey.component.keepAlive.KeepAliveService",
                                    classLoader,
                                    "onStartCommand",
                                    android.content.Intent::class.java,
                                    Int::class.javaPrimitiveType,
                                    Int::class.javaPrimitiveType,
                                    object : XC_MethodHook() {
                                        override fun afterHookedMethod(param: MethodHookParam) {
                                            try {
                                                log("保活心脏: KeepAliveService.onStartCommand 触发，执行动态接收器补挂")
                                                // KeepAliveService 实现自 android.app.Service（Context 子类），
                                                // applicationContext 为进程全局单例，与 attach 阶段缓存一致，
                                                // 故 ensureDynamicReceiver 的 referential === 去重可正常命中。
                                                val ctx = (param.thisObject as? android.content.Context)
                                                    ?.applicationContext
                                                if (ctx == null) {
                                                    log("保活心脏: 无法取得 applicationContext，跳过本次补挂")
                                                    return
                                                }
                                                val recv = dynamicReceiverRef
                                                val filt = dynamicFilterRef
                                                if (recv != null && filt != null) {
                                                    // 复用并缓存引用一致的同一实例：进程内同 filter 的重新注册
                                                    // 会以新注册替换旧注册，接收器被回收时这里即重新生效。
                                                    ensureDynamicReceiver(ctx, recv, filt)
                                                } else {
                                                    // attach 先于本服务启动，理论不会缺缓存；缺则仅记录，
                                                    // 防御性避免空指针（attach 段始终会填充这两个字段）。
                                                    log("保活心脏: receiver/filter 缓存缺失（不应发生），跳过本次")
                                                }
                                            } catch (t: Throwable) {
                                                log("保活心脏: onStartCommand 补挂处理异常: ${t.message}")
                                            }
                                        }
                                    }
                                )
                                log("已挂载 KeepAliveService.onStartCommand 保活心脏 hook")
                            } catch (t: Throwable) {
                                log("挂载 KeepAliveService.onStartCommand hook 失败: ${t.message}")
                            }

                            // ---------- 2.5.2) 保活第三重保险：KeepAliveService.onDestroy 拦截 + 自愈重拉 ----------
                            // 【已定案责任方】冷启动实证（00:20:09.469）完整线程栈确认：销毁由系统主线程
                            // ActivityThread.main → Looper → Handler.dispatchMessage →
                            // ActivityThread$H.handleMessage(2458) → handleStopService(5103) →
                            // KeepAliveService.onDestroy 触发。【非 app 内部主动 stopService/stopSelf】，
                            // extractCaller 因无 ForegroundServiceUtil/乘趣内部/Binder 关键词归入 [未知] 但栈已定案。
                            // 【根因】服务虽调用 startForeground() 但系统 FGS 集合未计录它（startForegroundCount:0、
                            // fgs 空、销毁伴随 Cancel FGS notification Notiflags:96 Reason:8 CallingPid=服务进程），
                            // 系统判定"前台服务未生效"遂经主线程 STOP_SERVICE 消息将其销毁。
                            // 【增强双向保险】
                            //   ① before：拦截销毁，setResult 跳过服务内部 onDestroy 主体（阻断其第 169 行
                            //     stopForeground(1) 撤销通知，防止通知态被破坏），但仍打印此次销毁触发证据；
                            //   ② after：若服务确实走到了 onDestroy（setResult 未能阻止主线程回调的主体），
                            //     用 attach 阶段缓存的 appContext 以 action=scan.start 显式重拉 KeepAliveService，
                            //     与 START_STICKY 语义叠加形成自愈闭环；带次数/冷却限流防风暴。
                            try {
                                XposedHelpers.findAndHookMethod(
                                    "com.ingeek.nokey.component.keepAlive.KeepAliveService",
                                    classLoader,
                                    "onDestroy",
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            try {
                                                val st = Thread.currentThread().stackTrace
                                                    .joinToString("\n") { "at $it" }
                                                val caller = extractCaller(st)
                                                log(">>> 保活拦截: onDestroy 触发(主线程停服务回调)，尝试 before 拦截阻断 self-cleanup")
                                                log(">>> 保活拦截: 销毁线程栈=====\n$st\n=====END STACK")
                                                log(">>> 保活拦截: 调用来源=$caller")
                                                // 阻断服务内部 onDestroy 清理（尤其 stopForeground(1) 撤销通知），
                                                // 同时保住前台通知；若系统强制走回调主体，after 兜底自愈重拉。
                                                param.result = null
                                                log(">>> 保活拦截: 已 setResult 跳过 onDestroy 主体")
                                            } catch (t: Throwable) {
                                                log("保活拦截: onDestroy before 处理异常: ${t.message}")
                                            }
                                        }

                                        override fun afterHookedMethod(param: MethodHookParam) {
                                            try {
                                                // 无论 before 是否 setResult，after 都会执行。此处负责
                                                // "服务确实被销毁后"的自愈重拉，形成保活闭环。
                                                relaunchKeepAliveService(
                                                    param.thisObject as? android.content.Context
                                                )
                                            } catch (t: Throwable) {
                                                log("保活拦截: onDestroy after 重拉异常: ${t.message}")
                                            }
                                        }
                                    }
                                )
                                log("已挂载 KeepAliveService.onDestroy 拦截+自愈重拉 hook（保活三重保险）")
                            } catch (t: Throwable) {
                                log("挂载 KeepAliveService.onDestroy hook 失败: ${t.message}")
                            }

                            // ---------- 2.6) 「隐藏后台卡片」档位 1：隐藏乘趣最近任务卡片 ----------
                            // 需求：防止用户在最近任务里手动划掉乘趣（划掉等同 force-stop，会使动态
                            // 广播接收器消失、方案 A 失效）。开启后，乘趣所有 Activity 的任务不进入最近任务列表。
                            //
                            // 【重要实现说明 / 既有 task 无效根因】：
                            // FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS 由 AMS 在 startActivity 时（task 首次创建）
                            // 消费，据此判定该 task 是否纳入 recents。仅靠 onCreate 里对当前 ActivityRecord 的
                            // intent.addFlags() 不会触发 AMS 重算 recents，对"早已存在于 recents 的既有 task"
                            // （乘趣为常驻守护进程，SplashPageActivity 开机即建 task 且进程从不死亡，onCreate
                            // 从不在新 task 上触发）完全无效——这正是此前"开关开了没效果"的根因。
                            // 因此这里采用【双保险】：
                            //   ① attach 阶段先对既有 task 打 EXCLUDE flag（实时刷出 recents 的意图），
                            //      但【绝不在此阶段 finishAndRemoveTask】——见下"防自杀"注释。
                            //   ② onCreate 打标：对将来新建的 task，在创建瞬间给根 intent 打上
                            //     FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS，从源头阻止进入 recents；
                            //      并对"非当前正在创建的 task"的既有 task 执行真正的移除。
                            //
                            // 【关键修复 · attach 阶段防自杀（本轮第二处自杀面）】：
                            // 冷启动（进程被杀后重新拉起）时，AMS 会在 process init 之前就已为
                            // SplashPageActivity 创建好 task，此时该 in-flight task 已经静态存在于
                            // am.getAppTasks() 中。而 Application.attach() 的时机正好落在 process init、
                            // 首个 Activity.onCreate 之前。若在此阶段对 appTasks 逐项执行
                            // finishAndRemoveTask()，会把这【当前正在构建、Activity 尚未 onCreate】的
                            // task 直接结束，导致 UI 一启动就被销毁（实测：attach 日志出现、但 onCreate
                            // 日志完全缺失、activity 栈为空、进程双 PID 中一个闪退）。
                            // 因此 attach 阶段【只打标、绝不 finish】；真正的 finishAndRemoveTask 一律
                            // 收敛到 onCreate 阶段的兜底（那里已有"跳过当前 task"的防自杀保护）。
                            try {
                                val am = appContext.getSystemService(
                                    android.content.Context.ACTIVITY_SERVICE
                                ) as? android.app.ActivityManager
                                if (am != null) {
                                    try {
                                        val tasks = am.appTasks
                                        if (tasks != null && tasks.isNotEmpty()) {
                                            tasks.forEach { t ->
                                                try {
                                                    t.setExcludeFromRecents(true)
                                                } catch (t2: Throwable) {
                                                    log("attach 阶段 setExcludeFromRecents 单任务异常: ${t2.message}")
                                                }
                                                // 注意：此处【不】调用 finishAndRemoveTask。
                                                // 见上"防自杀"注释——attach 阶段正处首个 Activity 创建前的
                                                // in-flight 窗口，对其 finish 会自杀掉将要启动的 UI。
                                                // 真正的移除收敛在下方 onCreate 阶段的兜底（跳过当前 task）。
                                            }
                                            // 日志措辞改为"打标"而非"排除"，如实反映 attach 阶段动作。
                                            log("attach 阶段已对既有 task ${tasks.size} 个 setExcludeFromRecents 打标（不 finish，防自杀）")
                                        }
                                    } catch (t2: Throwable) {
                                        log("attach 阶段 appTasks 打标异常: ${t2.message}")
                                    }
                                }
                                XposedHelpers.findAndHookMethod(
                                    android.app.Activity::class.java,
                                    "onCreate",
                                    android.os.Bundle::class.java,
                                    object : XC_MethodHook() {
                                        override fun beforeHookedMethod(param: MethodHookParam) {
                                            try {
                                                // 【探针日志】确认 onCreate hook 是否被调用
                                                log("[探针] onCreate BEFORE: activity=" + (param.thisObject?.javaClass?.simpleName ?: "null"))
                                                val hide = NokeyConfig.loadHideRecents(force = true)
                                                log("[探针] loadHideRecents(force=true) = $hide")
                                                if (!hide) return
                                                val activity = param.thisObject as? android.app.Activity
                                                    ?: return
                                                activity.intent?.addFlags(
                                                    android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                                                )
                                                // 兜底：onCreate 阶段再次主动把本应用既有任务排除，
                                                // 覆盖窗口期（开关开启后、或首个 Activity 启动时）的既有卡片。
                                                //
                                                // 【关键修复 · 防自杀】此 hook 挂在全局 android.app.Activity.onCreate，
                                                // 每次有 Activity 启动都会触发。appTasks 返回的是本应用【全部】task，
                                                // 其中一定包含【当前正在执行 onCreate 的这个 Activity 所在的 task】。
                                                // 若对该 task 也执行 finishAndRemoveTask()，会把正在创建的根 Activity
                                                // 一并结束（task 在 onCreate 尚未完成时即被销毁），导致每次点开乘趣
                                                // UI 都是"刚进 onCreate 就被自杀"，表现为"app 打不开、闪退"。
                                                // 因此这里必须【跳过当前正在 onCreate 的 task】——只对 taskId 与当前
                                                // activity 所在 task 不同的（即真正处于后台的）既有 task 才执行
                                                // setExcludeFromRecents + finishAndRemoveTask；当前 task 仅打
                                                // EXCLUDE_FROM_RECENTS 标（由 AMS 在首次创建时消费，从源头不进 recents）。
                                                // 获取当前 Activity 所在 task 的 id。
                                                // 首选公共 API activity.getTaskId()（API 1 起可见，返回 mTaskId 的封装），
                                                // 实测本 ROM 上 mTaskId 私有字段反射会 NoSuchFieldError（不叫 mTaskId），
                                                // 但公共 getTaskId() 仍可用，能可靠拿到当前 taskId 以区分"当前 task"与
                                                // 真正处于后台的既有 task。
                                                val currentTaskId = try {
                                                    activity.getTaskId()
                                                } catch (tApi: Throwable) {
                                                    // 公共 API 不可用（极少数情况）时，退回私有字段反射
                                                    try {
                                                        XposedHelpers.getIntField(activity, "mTaskId")
                                                    } catch (tAsk: Throwable) {
                                                        log("[探针] getTaskId()/mTaskId 均不可用: ${tAsk.message}")
                                                        null
                                                    }
                                                }
                                                log("[探针] currentTaskId = $currentTaskId")
                                                (activity.getSystemService(
                                                    android.content.Context.ACTIVITY_SERVICE
                                                ) as? android.app.ActivityManager)?.appTasks?.forEach { t ->
                                                    val taskId = try {
                                                        t.taskInfo.id
                                                    } catch (tInfo: Throwable) {
                                                        null
                                                    }
                                                    // 防自杀：只有当 currentTaskId 能确定、且与当前 task 的 id 明确不同时，
                                                    // 才能安全判定该 task 是"真正处于后台的既有 task"并可执行 finishAndRemoveTask。
                                                    // 若 currentTaskId 为 null（反射失败/无法确定当前 task），我们【无法排除
                                                    // 当前正在 onCreate 的这个 task】——此时对任意 task 执行 finishAndRemoveTask
                                                    // 都有误杀当前 UI 的致命风险（实测：mTaskId 反射返回 null 后，当前 SplashPage
                                                    // 所在 task 被误判 isCurrentTask=false 而自杀，UI 打不开）。
                                                    // 故 currentTaskId 无法确定时，一律只打标、绝不 finish（保守安全侧）。
                                                    val canConfirmNotCurrent =
                                                        (currentTaskId != null && taskId != null && taskId != currentTaskId)
                                                    log("[探针] appTask taskId=$taskId currentTaskId=$currentTaskId canConfirmNotCurrent=$canConfirmNotCurrent")
                                                    try {
                                                        t.setExcludeFromRecents(true)
                                                    } catch (t2: Throwable) {
                                                        log("onCreate 主动排除异常: ${t2.message}")
                                                    }
                                                    if (canConfirmNotCurrent) {
                                                        // 明确判定为"不是当前正在 onCreate 的 task"——真正的后台既有 task。
                                                        // 这类 task 已无用户正在交互的根 Activity，执行 finishAndRemoveTask
                                                        // 移除崩溃卡片是安全的（也必然结束其根 Activity，见既有结论）。
                                                        try {
                                                            val rm = t.javaClass.getMethod("finishAndRemoveTask")
                                                            rm.isAccessible = true
                                                            rm.invoke(t)
                                                            log("onCreate 已移除后台 task(tid=$taskId)（非当前 task）")
                                                        } catch (t3: Throwable) {
                                                            log("onCreate AppTask.finishAndRemoveTask 异常: ${t3.message}")
                                                        }
                                                    } else {
                                                        // 无法确认该 task 是否为当前正在 onCreate 的 task（currentTaskId 为 null）
                                                        // 或它就是当前 task：只打标，绝不 finishAndRemoveTask，否则会把当前
                                                        // 正创建的根 Activity 自杀掉，UI 打不开。
                                                        log("onCreate 保守跳过 finishAndRemoveTask(tid=$taskId)（无法确认非当前 / 当前 task，仅打标）")
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                log("隐藏最近任务卡片 hook 异常: ${t.message}")
                                            }
                                        }
                                    }
                                )
                                log("已挂载「隐藏后台卡片」Activity.onCreate hook + 既有 task 主动排除（按配置实时判定）")
                            } catch (t: Throwable) {
                                log("配置隐藏后台卡片 hook 失败: ${t.message}")
                            }
                        } catch (t: Throwable) {
                            log("注册广播接收器失败: ${t.message}")
                        }
                    }
                }
            )
            log("已挂载 Application.attach hook（等待应用创建后注册广播）")

        } catch (t: Throwable) {
            log("初始化失败: ${t.message}")
        }
    }

    /**
     * 【保活第二重保险】注册/补挂乘趣进程内的动态广播接收器（ACTION_NOKEY_CMD）。
     *
     * Android 13+ (API 33+) 要求动态注册 receiver 时必须显式指定导出标志。因为我们需要接收
     * 外部进程（小爱/Tasker）发来的隐式广播，必须用 RECEIVER_EXPORTED。
     *
     * attach 阶段初次注册时调用；随后 KeepAliveService.onStartCommand 的周期性心脏也会调用它，
     * 用"同一 appContext + 同一 filter"去重，避免重复注册。重复注册同 filter 的 receiver 在
     * Android 上会被判定为新的注册（覆盖旧的），可能造成命令被投递多次，故用缓存记录已注册状态，
     * 仅当未注册或注册上下文发生进程重建时才真正 re-register。
     */
    private fun ensureDynamicReceiver(
        appContext: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter
    ) {
        try {
            // 去重：同一 appContext 且已注册过且 receiver 未被系统移除时，直接返回。
            // 无法直接查询"是否已注册"，保守策略：若引用一致且上下文未变，视为已注册。
            if (registeredReceiverAppContext === appContext && dynamicReceiverRef != null) {
                log("保活心脏: 动态接收器已注册，跳过重复注册")
                return
            }
            appContext.registerReceiver(
                receiver,
                filter,
                android.content.Context.RECEIVER_EXPORTED
            )
            dynamicReceiverRef = receiver
            dynamicFilterRef = filter
            registeredReceiverAppContext = appContext
            log("保活心脏: 动态广播接收器已(重新)注册 $ACTION_NOKEY_CMD")
        } catch (t: Throwable) {
            log("保活心脏: 注册/补挂动态接收器异常: ${t.message}")
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[NokeyControl] $msg")
    }

    /**
     * 自愈重拉 KeepAliveService：服务被系统 handleStopService 销毁后，用它自身的 Context
     * 以 action="KeepAliveService.action.scan.start" 显式组件重拉，与 START_STICKY 语义叠加
     * 形成保活闭环。带"每进程生命周期内次数上限 + 时间冷却"双重限流，防止系统连续 destroy 时
     * OOM 风暴/无限循环。startForegroundService 需前台上下文调用（服务刚被销毁、进程仍处前台
     * 窗口期，约束满足）。
     */
    private fun relaunchKeepAliveService(ctx: android.content.Context?) {
        if (ctx == null) { log("自愈重拉: 上下文为空，跳过"); return }
        val now = System.currentTimeMillis()
        if (now - keepAliveRelReqLastTs < KEEP_ALIVE_REL_COOLDOWN_MS) {
            log("自愈重拉: 处于冷却期内，跳过本次（距上次 ${now - keepAliveRelReqLastTs}ms）")
            return
        }
        if (keepAliveRelReqCount >= KEEP_ALIVE_REL_MAX) {
            log("自愈重拉: 已打满上限 $KEEP_ALIVE_REL_MAX 次，放弃主动重拉，交由 START_STICKY 系统兜底")
            return
        }
        keepAliveRelReqLastTs = now
        keepAliveRelReqCount++
        try {
            val relaunchCtx = ctx.applicationContext ?: ctx
            val ri = Intent().apply {
                action = "KeepAliveService.action.scan.start"
                setClassName(PACKAGE_TARGET, "com.ingeek.nokey.component.keepAlive.KeepAliveService")
            }
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                relaunchCtx.startForegroundService(ri)
            } else {
                relaunchCtx.startService(ri)
            }
            log("自愈重拉: 已重新拉起 KeepAliveService（第 $keepAliveRelReqCount/$KEEP_ALIVE_REL_MAX 次）")
        } catch (t: Throwable) {
            log("自愈重拉: 重拉 KeepAliveService 异常: ${t.message}")
        }
    }

    /**
     * 从线程栈中提取最可能的"销毁调用来源"标记，用于判定 onDestroy 责任方：
     *  - 若栈顶含 com.ingeek.nokey.*（app 自研代码）→ 判定为 app 内部链路主动销毁；
     *  - 若栈顶为 android.app.* / com.android.server.* / Binder 线程 → 判定为系统回调/治理。
     */
    private fun extractCaller(st: String): String {
        val stackLines = st.split("\n")
            .map { it.trim() }
            .filter { it.startsWith("at ") }
        val head = stackLines.take(14).joinToString(" | ")
        val inAppChain = stackLines.any { it.contains("com.ingeek.nokey") }
        val inFgp = stackLines.any { it.contains("ForegroundServiceUtil") }
        val systemThread = stackLines.any { it.contains("Binder") || it.contains("ApplicationThread") }
        val tag = when {
            inFgp -> "[app] ForegroundServiceUtil 链路"
            inAppChain -> "[app] 乘趣内部链路"
            systemThread -> "[system] Binder/ApplicationThread 系统回调"
            else -> "[未知] 需人工核验栈"
        }
        return "$tag :: $head"
    }

    companion object {
        const val PACKAGE_TARGET = "com.ingeek.nokey"

        /** 外部广播触发 Action */
        const val ACTION_NOKEY_CMD = "io.github.srysfu.nokey.hook.NOKEY_CMD"
        /** 外部广播触发的命令码 Extra（模块自建动态接收器使用） */
        const val EXTRA_COMMAND = "command_code"

        /**
         * ACK 确认广播 Action：命令执行成功后，乘趣进程回发给小爱进程的定向确认广播。
         *
         * 背景：锁屏/灭屏后系统将乘趣后台进程冻结（cgroup frozen），广播投递到冻结进程后
         * onReceive 不执行（runningAppProcesses 仍显示存活，isAppProcessAlive 误判存活，
         * 导致不拉起、不补投）。根治方案升级为"发送后确认接收端确实执行了命令"：
         * 乘趣 onReceive 中 call() 成功即回发本 ACK，发送端据此撤销确认窗口；未收到 ACK
         * 则 root 唤醒 frozen 进程 + 重发。
         */
        const val ACTION_NOKEY_ACK = "io.github.srysfu.nokey.hook.NOKEY_ACK"

        /** ACK 回发的目标包名：小爱进程（发送端） */
        const val PACKAGE_VOICE_ASSIST = "com.miui.voiceassist"

        /**
         * 乘趣原生静态广播接收器字段名（仅用于 WidgetBroadcastReceiver.onReceive 探针日志）。
         *
         * 背景回顾：模块早期以静态显式广播（指向 exported=false 的 WidgetBroadcastReceiver）作为
         * 兜底通道，期望在乘趣进程被杀后仍能拉起。但方案 A 已确认根因——乘趣 WidgetBroadcastReceiver
         * 在 manifest 声明 android:exported=false，跨 UID（小爱 10175→乘趣 10283）向 exported=false
         * 组件发送显式广播，在 Android 8+ 会被系统静默丢弃，这是"命令发不出去"的真正根因。
         * 因此现在发送端改为发给模块自建动态接收器（RECEIVER_EXPORTED）的隐式广播，静态兜底通道已停用。
         * STATIC_EXTRA_COMMAND 保留仅供 onReceive 探针在静态链路被外部（Tasker 等）触发时打日志确认。
         */
        const val STATIC_EXTRA_COMMAND = "KeyCommandAction"

        /** 命令码（已逆向确认，与乘趣 k.a(int, h) 车控链路的入参语义一致） */
        const val CMD_UNLOCK = 0x0b        // 11 解锁
        const val CMD_LOCK = 0x0c          // 12 锁车
        const val CMD_ENGINE_ON = 0x29     // 41 引擎启动
        const val CMD_ENGINE_OFF = 0x2a    // 42 引擎关闭
        const val CMD_WINDOW_OPEN = 0x33   // 51 车窗开
        const val CMD_WINDOW_CLOSE = 0x34  // 52 车窗关
        const val CMD_TRUNK_OPEN = 0x1f    // 31 开后备箱（仅 supportTrunk 车型支持）
        const val CMD_SEARCH = 0x3d        // 61 寻车/声光寻车（仅 supportSearch 车型支持）

        /** 触发命令（需在 com.ingeek.nokey 进程内、且持有目标 classLoader 时调用） */
        fun call(classLoader: ClassLoader?, commandCode: Int): Boolean {
            return try {
                val kClazz = XposedHelpers.findClass(
                    "com.ingeek.nokey.component.launcherWidget.k",
                    classLoader
                )
                val hClazz = XposedHelpers.findClass(
                    "com.ingeek.nokey.component.launcherWidget.h",
                    classLoader
                )
                val kInstance = XposedHelpers.newInstance(kClazz)

                val hCallback = java.lang.reflect.Proxy.newProxyInstance(
                    hClazz.classLoader,
                    arrayOf(hClazz)
                ) { _, method, _ ->
                    when (method.name) {
                        "begin" -> XposedBridge.log("[NokeyControl] 回调 begin cmd=$commandCode")
                        "a", "b" -> XposedBridge.log("[NokeyControl] 回调 ${method.name}() cmd=$commandCode")
                        else -> {}
                    }
                    null
                }

                XposedHelpers.callMethod(kInstance, "a", commandCode, hCallback)
                XposedBridge.log("[NokeyControl] 已触发命令 commandCode=$commandCode")
                true
            } catch (t: Throwable) {
                XposedBridge.log("[NokeyControl] 触发命令失败 cmd=$commandCode: ${t.message}")
                false
            }
        }

        /**
         * ACK 回发：命令执行成功后向发送端（小爱进程）投递定向确认广播。
         *
         * 跨 UID 显式广播（乘趣 10283 → 小爱）要求接收方 receiver 以 RECEIVER_EXPORTED 注册
         * （VoiceAssistHook 侧已按此范式注册，见其 registerReceiver）。
         */
        fun ackToVoiceAssist(context: Context, commandCode: Int) {
            try {
                val ack = Intent(ACTION_NOKEY_ACK)
                    .setPackage(PACKAGE_VOICE_ASSIST)
                    .putExtra(EXTRA_COMMAND, commandCode)
                context.applicationContext.sendBroadcast(ack)
                XposedBridge.log("[NokeyControl] 已回发 ACK commandCode=$commandCode")
            } catch (t: Throwable) {
                XposedBridge.log("[NokeyControl] 回发 ACK 失败 commandCode=$commandCode: ${t.message}")
            }
        }
    }
}