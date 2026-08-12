package io.github.srysfu.nokey.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 乘趣「解锁/绕过」附加 hook（并入自冻结基线 GH v1.0 的独立 Java 模块逻辑转写为 Kotlin）。
 *
 * 原本是一份独立的、与 keep-alive / 小爱联动无关的 Java Xposed 模块
 * （包 com.example.nokeybypass），其功能属于「买断式破解」：
 *   - 绕过启动签名校验（AppConstants.compareNokeySignaturesSHA1）
 *   - 绕过安全环境检测（Root / Hook / 调试 / 模拟器等，BaseInspector.O00000o0）
 *   - 解锁所有皮肤（SkinItem 六个判权方法）
 *
 * 合并说明：
 *   - 目标类与方法名均为乘趣 R8 混淆产物（nokeeu / O00000Oo / O00000o0 等），
 *     已在本工作区逆向产物（乘趣_4.7.0.apk → _jadx_nokey2/sources/...）中逐一命中验证。
 *   - 乘趣升级并重新混淆后这些类名/方法名可能全部失效，届时本类全部 hook 会静默不生效，
 *     需按新混淆重新定位（属于预期内的破解模块维护成本，与 keep-alive 基线互不影响）。
 *   - 本类无状态，全部 hook 均在乘趣进程（com.ingeek.nokey）内、持有 classLoader 时调用；
 *     每个 hook 各自 try-catch，单个失败不影响其它 hook 与基线功能。
 */
object NokeyBypassHook {

    private const val TAG = "NokeyBypass"

    /** 目标进程（须与 MainHook.PACKAGE_TARGET 一致） */
    private const val TARGET_PKG = "com.ingeek.nokey"

    /**
     * 在乘趣进程中挂载全部破解/解锁 hook。
     *
     * @param classLoader 目标进程的 classLoader（LSPosed lpparam.classLoader）
     * @param skinUnlock  是否启用皮肤解锁模块（由模块配置开关控制）
     * @param bypassCheck 是否启用签名校验 + 安全环境检测绕过（通常与皮肤解锁一同开启，
     *                    单独拆分便于未来按需关闭某一类）
     */
    fun hook(classLoader: ClassLoader?, skinUnlock: Boolean, bypassCheck: Boolean) {
        if (bypassCheck) {
            hookSignatureCheck(classLoader)
            hookSecurityCheck(classLoader)
        }
        if (skinUnlock) {
            hookSkinUnlock(classLoader)
        }
    }

    // ========================================================
    // 模块 1：绕过启动签名校验
    // 目标：com.ingeek.nokeeu.key.util.AppConstants.compareNokeySignaturesSHA1(String)
    // 命中：_jadx_nokey2/sources/com/ingeek/nokeeu/key/util/AppConstants.java L123（返回 Boolean）
    // ========================================================
    private fun hookSignatureCheck(classLoader: ClassLoader?) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.ingeek.nokeeu.key.util.AppConstants",
                classLoader,
                "compareNokeySignaturesSHA1",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.setResult(java.lang.Boolean.TRUE)
                        XposedBridge.log("[$TAG] 已绕过签名校验")
                    }
                }
            )
            XposedBridge.log("[$TAG] 签名校验 Hook 挂载成功")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 签名校验 Hook 失败: ${t.message}")
        }
    }

    // ========================================================
    // 模块 2：绕过安全环境检测
    // 目标：com.ingeek.nokeeu.key.security.c.e.O00000Oo.O00000o0(int)
    //   命中：_jadx_nokey2/sources/com/ingeek/nokeeu/key/security/c/e/O00000Oo.java L34
    //   （public final void O00000o0(int i10)，内部 if (i10 == 1) 走通过分支）
    // 策略：beforeHook 把非 1 的参数强制改写为 1，令 6 种检测（Root/Hook/调试/模拟器等）全部放行
    // ========================================================
    private fun hookSecurityCheck(classLoader: ClassLoader?) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.ingeek.nokeeu.key.security.c.e.O00000Oo",
                classLoader,
                "O00000o0",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val result = param.args[0] as Int
                        if (result != 1) {
                            XposedBridge.log("[$TAG] 安全检测拦截: $result → 通过")
                            param.args[0] = 1
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] 安全环境检测 Hook 挂载成功")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 安全检测 Hook 失败: ${t.message}")
        }
    }

    // ========================================================
    // 模块 3：解锁所有皮肤
    // 目标：com.ingeek.nokey.network.entity.SkinItem 六个判权方法
    //   命中：_jadx_nokey2/sources/com/ingeek/nokey/network/entity/SkinItem.java
    //   canUse()→public final boolean          L26
    //   getKeepStatus()→public final Integer   L33
    //   getUsingFlag()→public final Integer    L40
    //   isNeedShowPrice()→public final boolean L47
    //   isSupportTry()→public final boolean    L52
    //   isTrialExpired()→public final boolean  L57
    //
    // 修改效果          目标方法            返回值
    //  可用             canUse()            → true
    //  支持试用          isSupportTry()      → true
    //  永不过期          isTrialExpired()    → false
    //  不显示价格        isNeedShowPrice()   → false
    //  已购买            getKeepStatus()     → Integer.valueOf(3)
    //  使用中            getUsingFlag()      → Integer.valueOf(1)
    // ========================================================
    private fun hookSkinUnlock(classLoader: ClassLoader?) {
        try {
            val skinItem = XposedHelpers.findClass(
                "com.ingeek.nokey.network.entity.SkinItem",
                classLoader
            )

            // ① 所有皮肤可用
            XposedHelpers.findAndHookMethod(
                skinItem, "canUse",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.setResult(true)
                    }
                }
            )

            // ② 所有皮肤支持试用
            XposedHelpers.findAndHookMethod(
                skinItem, "isSupportTry",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.setResult(true)
                    }
                }
            )

            // ③ 试用永不过期
            XposedHelpers.findAndHookMethod(
                skinItem, "isTrialExpired",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.setResult(false)
                    }
                }
            )

            // ④ 不显示价格
            XposedHelpers.findAndHookMethod(
                skinItem, "isNeedShowPrice",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.setResult(false)
                    }
                }
            )

            // ⑤ 拥有状态 → 已购买（返回 boxed Integer）
            XposedHelpers.findAndHookMethod(
                skinItem, "getKeepStatus",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.setResult(Integer.valueOf(3))
                    }
                }
            )

            // ⑥ 使用状态 → 使用中（返回 boxed Integer）
            XposedHelpers.findAndHookMethod(
                skinItem, "getUsingFlag",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.setResult(Integer.valueOf(1))
                    }
                }
            )

            XposedBridge.log("[$TAG] 皮肤全部解锁完成")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 皮肤解锁 Hook 失败: ${t.message}")
        }
    }
}
