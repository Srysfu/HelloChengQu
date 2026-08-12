# 冻结基线（FROZEN BASELINE）— based_on_gh_v1.0

## 状态（2026-08-11 冻结 + 破解已合并）
本目录为 **后续所有修改的唯一主工作副本**，完整复刻 GitHub 仓库 `Srysfu/nokey-voice-control` 远端 **v1.0** 源码。
后续任何功能调整、修正都**在这个副本上直接改**，不再引入根工程 `/app/` 或 `NewRepo/` 中领先的 v1.1 增量。
**⚠️ 破解功能（皮肤解锁 + 签名绕过 + 安全检测绕过）已源码化并合并进本基线**（详见下方「破解功能」章节）。

---

## 破解功能（已源码化 —— 未来改功能不会丢）
破解功能**不是 dex 后合并的临时补丁，而是以源码形式驻留在本基线源码树里**，正常 `assembleRelease` 编译自动带进包。三层全部就位：

| 层 | 文件 | 说明 |
|---|---|---|
| 独立破解类 | `app/src/main/java/io/github/srysfu/nokey/hook/NokeyBypassHook.kt`（8.6KB） | 皮肤解锁（SkinItem 六判权强制放行）+ 签名校验绕过（AppConstants.compareNokeySignaturesSHA1）+ 安全环境检测绕过（BaseInspector.O00000o0），三个 hook 各自 try-catch |
| 挂载调用段 | `MainHook.kt` L85-94 | `NokeyBypassHook.hook(classLoader, NokeyConfig.loadSkinUnlock(force=true), NokeyConfig.loadBypassCheck(force=true))`，位于基线 hook 内部 |
| 配置开关 | `NokeyConfig.kt` | `KEY_SKIN_UNLOCK(skinUnlock)` / `KEY_BYPASS_CHECK(bypassCheck)` 两字段 + `loadSkinUnlock`/`loadBypassCheck` 读取 + 落盘/解析完整 |

- 破解目标进程：**com.ingeek.nokey**（破解版乘趣，系统已装且运行）
- 开关为运行期读取（`/data/local/tmp/nokey_cfg.json`），未开启时回退 false 不启用破解
- 调用段开关控制 + 各自 try-catch，相互解耦，**改别处功能不会误伤破解**
- **结论：以后修改模块功能，正常编译必然把破解代码带进包，不会影响破解功能。**

---

## 未来修改功能的【铁律】构建流程（用户确认必须遵守）
真正的坑不是破解丢失（已源码化），而是 **Proot 环境 AGP 打包缺陷**：`assembleRelease` 产出的 APK **缺 res/ 资源与 manifest（res/=0）**，曾致 UI 全崩（日志 `FileNotFoundException: res/drawable/ic_beh_recents.xml`）。因此每次改功能都必须走「底座 + 三 dex 替换」重打包：

1. **改源码**：只在 `based_on_gh_v1.0/` 里改对应 .kt（破解相关 `NokeyBypassHook.kt` 除非必要否则勿动）
2. **构建**：`gradle :app:assembleRelease`（拿到新编译的 `classes*.dex`；破解 NokeyBypass 编进 classes2.dex）
3. **取底座**：`release/nokey-voice-control-v1.0.apk`（已验证 res/=61 + 含三 dex，是"永不缺壳"的完整基座）
4. **合并三 dex**：用本次构建的 `classes*.dex` 覆盖底座同名文件（md5 校验确认替换成功）
5. **清理 META-INF 全部签名残留**：`MANIFEST.MF`、`*.SF`、`*.RSA`、`*.DSA` 全删（不能只删 ANDROIDD.SF/RSA）
6. **三步重打包**：①`zip -r -X -q apk.zip . -x 'lib/*' -x 'resources.arsc'`（其余 Deflate）→ ②`zip -r -X -q -0 apk.zip resources.arsc`（arsc Store）→ ③`zip -r -X -q -0 apk.zip lib`（so Store）
7. **zipalign**：`zipalign -f -p 4`（-p 使 .so 页对齐）+ verify（输出 `resources.arsc (OK)` / `Verification successful`）
8. **apksigner 重签**：`--ks /root/.android/debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android`（v2/v3 true）
9. **桥接安装**：sdcard SELinux 禁读，`cp` 到 `/data/local/tmp/` 后 `pm install -r -d` 覆盖安装

> ⚠️ 每一步后都必须 `unzip -l apk | grep -cE ' res/'` 校验 res/ 完整性应 **>=61**，不能只依赖 arsc 索引。
> 一键化：`tools/rebuild.sh <新dex目录> <输出apk>` 可自动走完 3-9 步。

## 冻结原因（用户预期）
用户确认 GitHub 远端 v1.0 的实机行为就是**预期目标**，以此为基线继续迭代。

## 冻结（不采用）的 v1.1 增量
以下 v1.1 增强部分全部冻结，不再叠回本次基线：
1. **SelfAsrRecognition.kt**（锁屏唤醒修复）— 基线 9 个 .kt，无此文件
2. **VoiceAssistHook.kt 冷启动循环重发**（`HOST_COLD_START_RETRY_MAX=5` 有界循环、合计 ~12.5s）— 基线为 v1.0 单次固定 2500ms 补偿
3. **MainActivity.kt 折叠式「反馈设置」新版界面** — 基线为平铺大标题 + 开关条布局

## 主源码 .kt 哈希基线（⚠️ 合并前历史，仅供参考）
> 以下 md5 是**破解功能合并前**的 v1.0 原始哈希。破解合并已改变 `MainHook.kt`（现含调用段）与 `NokeyConfig.kt`（现含双开关），故下表 2 个文件哈希已过期，不再作为校验基准。
| 文件 | md5（合并前） |
|---|---|
| CommandMatcher.kt | 896e6fce7deaf318517077ec681ffeac |
| MainActivity.kt | 850ddde3bf697933c90de82e9d027e1e |
| MainHook.kt | 36c6e9888d7d8a16f7a24f33a7993170（⚠️ 已变） |
| NokeyConfig.kt | 7a19f4a0a9f235c405907362c4204358（⚠️ 已变） |
| SulistHelper.kt | d79bf76e16d8373651f7d26aefa50487 |
| VoiceAssistHook.kt | 15fc8cab257e9425f2545dd1f0b61caa |
| ui/theme/Color.kt | 456443509048ed714381f5510ee312c5 |
| ui/theme/Theme.kt | 51cc4a3e9fec5c371af2c4731a20749d |
| ui/theme/Type.kt | 5ad312637af08305fe1789aa839ac96e |

> 新增破解独立类：`NokeyBypassHook.kt`（8.6KB，合并后新增，无合并前哈希）。

## 适配版本
小爱 7.13.32.0016 / 乘趣 4.7.0 / 模块版本 v1.0 (versionCode=1)

## 真机安装态
versionCode=1 / versionName=1.0，签名 CN=Android Debug (SHA-1=46:E1:DE:67:...=29fbae91)，codePath=`~~havVBCi4ZlG4X2x1TURU-A==`。
安装方式：`pm install -r -d /data/local/tmp/XXX.apk`（需从 sdcard 桥接复制到 /data/local/tmp）。

## 后续修改流程（已由上方「铁律构建流程」取代）
见上文「未来修改功能的【铁律】构建流程」。核心要点速查：
1. 只改 `based_on_gh_v1.0/` 里的 .kt（破解 NokeyBypassHook.kt 除非必要否则勿动）
2. `gradle :app:assembleRelease` 构建
3. 走「底座 + 三 dex 替换」重打包（AGP 产物缺 res%/manifest，必须用 `release/nokey-voice-control-v1.0.apk` 作基座）
4. 桥接 `/data/local/tmp` 后 `pm install -r -d` 覆盖安装
> 一键化：`tools/rebuild.sh <新dex目录> <输出apk>`。
