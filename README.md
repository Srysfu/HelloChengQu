# ⛩️ HelloChengQu

> *Speak to your car like you'd speak to a friend.*

[![Android](https://img.shields.io/badge/Android-14%2B-34A853?style=flat&logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![LSPosed](https://img.shields.io/badge/LSPosed-1.9%2B-FF6B00?style=flat)](https://github.com/LSPosed/LSPosed)
[![Release](https://img.shields.io/badge/release-v1.0.1-0366D6?style=flat)](https://github.com/Srysfu/HelloChengQu/releases/tag/v1.0.1)

**HelloChengQu** is a resilient keep-alive bridge between **XiaoAi Voice Assistant** and **ChengQu vehicle control** — an [LSPosed](https://github.com/LSPosed/LSPosed) / Xposed module that intercepts voice intents at the system level, translates them into vehicle commands, and delivers them with **ACK-confirmed broadcast retry** and **cgroup-aware process revival** for locked-screen reliability on Android 14+.

Voice → Intent → Broadcast → Vehicle. No pop-ups. No interruption. Just the car, listening.

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