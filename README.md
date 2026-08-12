# ⛩️ HelloChengQu

> *Speak to your car like you'd speak to a friend.*
> 
> *像跟朋友聊天一样，跟你的车说话。*

[![Android](https://img.shields.io/badge/Android-14%2B-34A853?style=flat&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![LSPosed](https://img.shields.io/badge/LSPosed-1.9%2B-FF6B00?style=flat)](https://github.com/LSPosed/LSPosed)
[![Release](https://img.shields.io/badge/release-v1.0.1-0366D6?style=flat)](https://github.com/Srysfu/HelloChengQu/releases/tag/v1.0.1)

🌐 [English](#english) &nbsp;|&nbsp; [中文](#中文)

**HelloChengQu** is a resilient keep-alive bridge between **XiaoAi Voice Assistant** and **ChengQu vehicle control** — an [LSPosed](https://github.com/LSPosed/LSPosed) / Xposed module that intercepts voice intents at the system level, translates them into vehicle commands, and delivers them with **ACK-confirmed broadcast retry** and **cgroup-aware process revival** for locked-screen reliability on Android 14+.

**HelloChengQu** 是**小爱同学**与**乘趣车控**之间的韧性保活桥梁——基于 [LSPosed](https://github.com/LSPosed/LSPosed) / Xposed 的系统级 Hook 模块，拦截语音意图流、转译为车辆控制指令，配合 **ACK 确认重试** 与 **cgroup 感知进程唤醒**，在 Android 14+ 锁屏场景下确保广播可靠投递。

Voice → Intent → Broadcast → Vehicle. No pop-ups. No interruption. Just the car, listening.
语音 → 意图 → 广播 → 车辆。无弹窗，不打断，只有车在听你说话。

---

<h2 id="english">🇬🇧 English</h2>

---

## Architecture

```
┌─────────────────────────┐     NokeyBypassHook      ┌───────────────────────────┐
│  XiaoAi Voice Assistant │ ─ ─ ─ ─ [bypass] ─ ─ ─ ▶│     ChengQu Vehicle App   │
│  com.miui.voiceassist   │                          │     com.ingeek.nokey      │
│                          │                          │                           │
│  VoiceAssistHook.kt     │  ──── NOKEY_CMD ────────▶│  MainHook.kt              │
│  (transmitter)          │  ◀──── NOKEY_ACK ─────── │  (receiver)               │
│                          │                          │                           │
│  ┌─────────────────────┐│                          │  ┌───────────────────────┐ │
│  │ ACK Retry Engine    ││                          │  │ Cgroup Frost Monitor  │ │
│  │ 1.2s → 2.5s → 4.5s  ││                          │  │ root-assisted revive  │ │
│  └─────────────────────┘│                          │  └───────────────────────┘ │
└─────────────────────────┘                          └───────────────────────────┘
```

Two processes, one channel. The module hooks both sides of the conversation — the voice assistant that *speaks* and the vehicle app that *acts* — and guarantees delivery even when Android's power-saving cgroup freezes the receiver.

---

## ACK Retry Protocol

Locked-screen broadcast loss is a **cgroup freeze problem**, not a connectivity problem. When Android suspends `com.ingeek.nokey` into the frozen cgroup, `isAppProcessAlive()` still reports `true` — the process exists but cannot receive broadcasts. HelloChengQu detects this silent failure and recovers.

| Phase | Action | Timeout | On Failure |
|-------|--------|---------|------------|
| **Tx** | Send `NOKEY_CMD` broadcast | — | — |
| **Wait** | Await `NOKEY_ACK` from receiver | 1.2 s | → Retry 1 |
| **Retry 1** | Force-wake receiver via root shell, re-send | 2.5 s | → Retry 2 |
| **Retry 2** | Force-wake receiver via root shell, re-send | 4.5 s | → Retry 3 |
| **Retry 3** | Final attempt with full process revival | 1.0 s | → Give-up |
| **Give-up** | Log warning, release resources | — | — |

Total coverage: up to ~9.2 seconds across three escalating retries. Each retry escalates the wake-up strategy — from gentle broadcast retransmission to aggressive `am start` via root shell — maximizing delivery probability without wasting resources.

---

## Bypass Layer

`NokeyBypassHook.kt` operates as a transparent pass-through layer, neutralizing three categories of client-side restrictions in the vehicle control app:

- **Skin validation** — intercepts theme/UI integrity checks that would reject the module as an unauthorized client
- **Signature verification** — neutralizes APK signature checks that block modified or hooked environments
- **Security detection** — suppresses runtime tamper-detection guards (debugger checks, hook framework detection)

All three bypasses are implemented as pure Hook artifacts — no APK repackaging, no binary patching. The target app remains unmodified on disk.

---

## Verified

| Device | ROM | Android | Scenario | Result |
|--------|-----|---------|----------|--------|
| Redmi Note 10 Pro | HyperOS (MIUI) | 14 | Locked-screen voice command — window open/close | ✅ |
| Redmi Note 10 Pro | HyperOS (MIUI) | 14 | Active-screen voice command — engine start/stop | ✅ |
| Redmi Note 10 Pro | HyperOS (MIUI) | 14 | Doze-mode broadcast recovery via ACK retry | ✅ |

> Verification was performed on a production device under real-world conditions — screen-off, cgroup-frozen, with no USB debugging connection.

---

## Getting Started

### Prerequisites

- Android 14+ device with **root access** (Magisk / KernelSU)
- [LSPosed](https://github.com/LSPosed/LSPosed) 1.9+ installed and active
- The target vehicle control app (`com.ingeek.nokey`) must be installed

### Target App Versions

| Application | Package | Version |
|-------------|---------|---------|
| XiaoAi Voice Assistant | `com.miui.voiceassist` | `7.13.32.0016` |
| ChengQu (vehicle control) | `com.ingeek.nokey` | `4.7.0` |

> ⚡ *Class names are obfuscated per-version. Upgrading the target app may break hooks. Pin to the versions above.*

### Installation

1. Build the APK or download the [latest signed release](https://github.com/Srysfu/HelloChengQu/releases/latest).
2. Install the APK on your device.
3. In LSPosed Manager, enable the module and check both target apps:
   - `com.miui.voiceassist` (voice interception)
   - `com.ingeek.nokey` (command delivery)
4. Reboot the device, or force-stop both target apps to apply hooks.

### External Broadcast Trigger

Any automation tool (Tasker, MacroDroid, custom scripts) can issue vehicle commands directly:

```
Action:  io.github.srysfu.nokey.hook.NOKEY_CMD
Extra:   command_code = 41          # e.g. engine start
```

Command codes follow the same mapping as voice-triggered commands. No custom permissions required — the broadcast is scoped to the module's internal receiver.

---

## Project Structure

```
HelloChengQu/
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/io/github/srysfu/nokey/
│       ├── hook/
│       │   ├── MainHook.kt              # Receiver — NOKEY_CMD dispatch + ACK reply
│       │   ├── VoiceAssistHook.kt       # Transmitter — voice intent interception + ACK retry engine
│       │   └── NokeyBypassHook.kt       # Bypass layer — skin / signature / security
│       ├── utils/
│       │   ├── CommandDispatcher.kt     # NL → command resolution
│       │   └── SulistHelper.kt          # Root-shell utilities
│       └── BuildConfig.kt
├── FROZEN_BASELINE.md                   # Design rationale — the frozen-cgroup problem
└── README.md
```

---

## Design Rationale

See [`FROZEN_BASELINE.md`](./FROZEN_BASELINE.md) for a technical deep-dive into the cgroup freeze problem, the failure modes of conventional broadcast delivery, and the architectural decisions behind the ACK retry protocol.

---

## License & Disclaimer

This project is shared for **educational and interoperability research** purposes. Use it only with vehicles you own or are authorized to control, and in compliance with local laws and regulations.

The authors assume no liability for misuse. This is a tool — what you build with it is your own craft.

---

<h2 id="中文">🇨🇳 中文</h2>

---

## 架构

```
┌──────────────────────┐     NokeyBypassHook      ┌────────────────────────┐
│   小爱同学              │ ─ ─ ─ ─ [绕过] ─ ─ ─ ▶│   乘趣车控                │
│   com.miui.voiceassist │                          │   com.ingeek.nokey      │
│                          │                          │                         │
│   VoiceAssistHook.kt    │  ──── NOKEY_CMD ──────▶│   MainHook.kt            │
│   (发送端)                │  ◀──── NOKEY_ACK ─────│   (接收端)                │
│                          │                          │                         │
│   ┌───────────────────┐│                          │   ┌──────────────────┐  │
│   │ ACK 重试引擎         ││                          │   │ Cgroup 冻结监听    │  │
│   │ 1.2s→2.5s→4.5s     ││                          │   │ root 强制唤醒      │  │
│   └───────────────────┘│                          │   └──────────────────┘  │
└──────────────────────┘                          └────────────────────────┘
```

双进程，一条通道。模块同时 Hook 对话的双方——负责*听*的语音助手和负责*做*的车控应用——即使 Android 省电 cgroup 冻结了接收端，也能确保指令送达。

---

## ACK 确认重试协议

锁屏广播丢失本质上是 **cgroup 冻结问题**，而非连接问题。当 Android 将 `com.ingeek.nokey` 挂起至 frozen cgroup 时，`isAppProcessAlive()` 仍然返回 `true`——进程存在但无法接收广播。HelloChengQu 检测到这种静默失败并自动恢复。

| 阶段 | 动作 | 超时 | 失败策略 |
|------|------|------|----------|
| **Tx** | 发送 `NOKEY_CMD` 广播 | — | — |
| **等待** | 等待接收端回复 `NOKEY_ACK` | 1.2 s | → 重试 1 |
| **重试 1** | 通过 root shell 强制唤醒接收端，重新发送 | 2.5 s | → 重试 2 |
| **重试 2** | 通过 root shell 强制唤醒接收端，重新发送 | 4.5 s | → 重试 3 |
| **重试 3** | 最终尝试，完整进程复活 | 1.0 s | → 放弃 |
| **放弃** | 记录警告日志，释放资源 | — | — |

三次递增重试覆盖最长约 9.2 秒。每次重试逐级升级唤醒策略——从温和的广播重传到通过 root shell 执行 `am start` 的强制唤醒——最大化投递概率的同时避免资源浪费。

---

## 破解层

`NokeyBypassHook.kt` 作为透明穿透层运行，化解车控应用中三类客户端限制：

- **皮肤校验** — 拦截会将模块标记为未授权客户端的主题/UI 完整性检查
- **签名验证** — 中和会阻断修改或 Hook 环境的 APK 签名检查
- **安全检测** — 压制运行时篡改检测守卫（调试器检测、Hook 框架检测）

三道绕过均为纯 Hook 实现——无需重打包 APK，无需二进制补丁，目标应用在磁盘上保持不变。

---

## 真机验证

| 设备 | ROM | Android | 场景 | 结果 |
|------|-----|---------|------|------|
| Redmi Note 10 Pro | HyperOS (MIUI) | 14 | 锁屏语音控制 — 车窗开关 | ✅ |
| Redmi Note 10 Pro | HyperOS (MIUI) | 14 | 亮屏语音控制 — 引擎启停 | ✅ |
| Redmi Note 10 Pro | HyperOS (MIUI) | 14 | Doze 模式下 ACK 重试广播恢复 | ✅ |

> 验证在生产设备上以真实场景完成——熄屏、cgroup 冻结、无 USB 调试连接。

---

## 快速开始

### 前置条件

- Android 14+ 设备，已获取 **root 权限**（Magisk / KernelSU）
- 已安装并激活 [LSPosed](https://github.com/LSPosed/LSPosed) 1.9+
- 已安装目标车控应用（`com.ingeek.nokey`）

### 目标应用版本

| 应用 | 包名 | 版本号 |
|------|------|--------|
| 小爱同学 | `com.miui.voiceassist` | `7.13.32.0016` |
| 乘趣（车控） | `com.ingeek.nokey` | `4.7.0` |

> ⚡ *类名按版本混淆，目标应用升级后 Hook 可能失效。请锁定上述版本使用。*

### 安装步骤

1. 自行构建 APK 或下载[最新签名发行版](https://github.com/Srysfu/HelloChengQu/releases/latest)。
2. 在设备上安装 APK。
3. 在 LSPosed 管理器中启用模块，勾选两个目标应用：
   - `com.miui.voiceassist`（语音拦截）
   - `com.ingeek.nokey`（指令下发）
4. 重启设备，或强制停止两个目标应用使 Hook 生效。

### 外部广播触发

任何自动化工具（Tasker、MacroDroid、自定义脚本）均可直接发送车辆指令：

```
Action:  io.github.srysfu.nokey.hook.NOKEY_CMD
Extra:   command_code = 41          # 如引擎启动
```

指令码与语音触发命令的映射一致。无需自定义权限——广播限定在模块内部接收器范围内。

---

## 项目结构

```
HelloChengQu/
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/io/github/srysfu/nokey/
│       ├── hook/
│       │   ├── MainHook.kt              # 接收端 — NOKEY_CMD 分发 + ACK 回复
│       │   ├── VoiceAssistHook.kt       # 发送端 — 语音意图拦截 + ACK 重试引擎
│       │   └── NokeyBypassHook.kt       # 破解层 — 皮肤 / 签名 / 安全检测绕过
│       ├── utils/
│       │   ├── CommandDispatcher.kt     # 自然语言 → 指令解析
│       │   └── SulistHelper.kt          # Root shell 工具
│       └── BuildConfig.kt
├── FROZEN_BASELINE.md                   # 设计推导 — cgroup 冻结问题分析
└── README.md
```

---

## 设计推导

详见 [`FROZEN_BASELINE.md`](./FROZEN_BASELINE.md)，包含 cgroup 冻结问题的技术深潜、传统广播投递的失效模式，以及 ACK 重试协议的架构决策。

---

## 许可与声明

本项目仅供**学术研究与互操作性探索**。请仅在自有或已获授权的车辆上使用，并遵守当地法律法规。

作者不对滥用行为承担任何责任。这是一把工具——用它打造什么，取决于你自己。