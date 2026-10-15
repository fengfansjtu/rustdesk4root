# RustDesk for Root (Android 64-bit)

> 专为已获取 **ROOT 权限** 的 Android 设备（64 位 `arm64-v8a`）打造的 RustDesk 被控端深度增强版本。  
> **只需在被控端手机安装本版本，控制端（Windows / macOS / Linux / iOS / Android）完全继续使用官方原版即可无缝远程控制。**

---

## 核心特性：与官方版本的区别

| 功能特性 | 官方原生版本 (Official) | Root 增强版本 (This Fork) |
| :--- | :--- | :--- |
| **输入控制依赖** | 强依赖系统的**“无障碍服务” (Accessibility)**。 | **完全不依赖无障碍服务**，通过底层 Root 常驻管道直接注入系统事件。 |
| **锁屏远程控制** | **无法操作锁屏**。Android 安全策略在锁屏界面（Keyguard）会直接拦截无障碍手势。 | **支持锁屏直接控制**。Root 注入级别高于窗口安全层，可在锁屏界面点击、滑动、输入 PIN 码。 |
| **息屏唤醒与拉起** | 仅依赖普通 WakeLock，无法自动点亮并拉起解锁界面。 | 收到连接或远程点击时，底层自动执行 `input keyevent 224` 与 `wm dismiss-keyguard` 唤醒并呈现解锁盘。 |
| **无障碍保活问题** | 国内各大定制 ROM（MIUI、OriginOS、ColorOS 等）频繁强杀无障碍服务导致失控。 | **彻底根治失控问题**。无需开启无障碍服务，Root 注入常驻内核管道，永不失效。 |
| **锁屏/解锁画面防死锁** | 息屏、锁屏或解锁返回后，原生 VirtualDisplay 缓冲区断开，**控制端画面永久卡死**。 | **全自动自愈恢复**。监听亮屏、息屏与解锁广播，自适应重建采集管道与 ImageReader，解锁后画面毫秒级恢复。 |
| **会话 CPU 防休眠** | 息屏后系统进入 Doze 深度休眠，网络心跳与远控连接中断。 | 会话建立后自动保持系统级 CPU WakeLock，息屏与锁屏期间保持高速网络连接不掉线。 |
| **界面权限状态** | 未开启无障碍时，主页状态亮黄/红色报警，阻断正常使用提示。 | 自动探测 Root 权限；获取 Root 授权后，输入权限**自动点亮绿灯**，无需跳转设置。 |
| **录屏授权弹窗** | 部分系统每次启动录屏均需人工手动点击“立即开始”。 | 授权连接后自动通过 `appops` 静默赋予 `PROJECT_MEDIA allow`，实现真正无人值守。 |

---

## 工作原理解析

### 1. 底层事件注入 (Root Input Injection)
官方版本通过 `AccessibilityService.dispatchGesture` 模拟手势，该接口在安全窗口（如输入法密码键盘、系统锁屏界面）会被 Android 系统强制拦截。  
本版本新增 [`RootInputManager.kt`](flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/RootInputManager.kt)，建立了一条高响应的持久化 `su` 输入管道：
- **触控/鼠标**：将 RustDesk 的触控与鼠标事件直接转译为 `input tap`、`input swipe`。
- **键盘事件**：直接解析按键映射为 `input keyevent <keycode>` 或 `input text`，即使锁屏状态下也能直接键入密码数字与回车。
- **系统按键**：精准支持返回键（Back）、主页键（Home）、最近任务等。

### 2. 息屏唤醒与解锁协作
当被控端处于锁屏或息屏状态时：
1. 收到远程连接授权，立即申请底层亮屏锁；
2. 执行底层唤醒命令 `input keyevent 224`（KEYCODE_WAKEUP）；
3. 联动 `wm dismiss-keyguard` 呼出锁屏密码输入界面（若手机无密码，则直接滑动进入桌面）。

---

## 被控端环境建议（解决锁屏防录屏黑屏）

Android 系统为了保护用户隐私，在锁屏界面（输入 PIN / 密码 / 图案）默认启用了系统的 **`FLAG_SECURE`** 属性，会导致包括官方 RustDesk 在内的所有屏幕录制工具在锁屏时呈现**黑屏**。

为获得完美的锁屏远程操控体验，被控手机推荐配合以下方式解除限制：
1. **Magisk / KernelSU / APatch 模块**：
   - 安装 **LSPosed** 框架，并在 LSPosed 仓库中激活 **Disable-FLAG_SECURE** 模块。
   - 或直接刷入系统级的全局 **Disable FLAG_SECURE** Magisk 模块。
2. 解除后，RustDesk 的画面采集即可无缝捕捉锁屏密码盘，控制端便能清晰看到锁屏界面并直接远程点击密码解锁。

---

## 客户端兼容性说明

- **控制端（电脑/手机）**：**无需使用此版本！** 请直接前往 RustDesk 官网下载官方正式版（Windows、Mac、Linux、iOS 等均可），通信协议 100% 兼容。
- **被控端（本工程目标）**：仅需安装由本工程编译出的 64 位（`arm64-v8a`）APK，并在手机弹出的 Root 授权窗口中点击**“永久允许”**。

---

## 利用 GitHub Actions 自动编译 64 位 APK

本项目已内置独立的 GitHub Actions 自动化编译流，无需在本地配置繁琐的 NDK、Rust、Flutter 及 vcpkg 交叉编译环境。

### 编译文件路径
- [`.github/workflows/build-android-arm64.yml`](.github/workflows/build-android-arm64.yml)

### 编译触发步骤
1. 将本仓库 Push 到你的 GitHub 个人仓库。
2. 打开 GitHub 仓库主页，点击顶部的 **Actions** 标签页。
3. 在左侧工作流列表中找到 **"Build Android 64-bit APK (arm64-v8a)"**。
4. 点击右侧 **"Run workflow"** 按钮，选择分支并点击确定。
5. 流程大约耗时 10~15 分钟（自动拉取依赖、配置 Flutter 3.24.5、NDK r28c、生成 bridge 并交叉编译 64 位底层 so 库）。
6. 构建完成后，在当前运行记录的最下方 **Artifacts** 区域直接下载 `rustdesk-arm64-v8a-root-release.zip`，解压即可得到已自动使用 Debug 签名的 64 位 APK，可直接安装至手机。

---

## 核心改动文件列表

- [`flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/RootInputManager.kt`](flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/RootInputManager.kt)  
  *Root 级底层输入注入、持久化 su 管道通信、锁屏唤醒与 AppOps 权限静默管理。*
- [`flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt`](flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainService.kt)  
  *接入 Root 输入分发拦截、连接建立时自动亮屏拉起密码盘、Root 状态自动同步。*
- [`flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainActivity.kt`](flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/MainActivity.kt)  
  *UI 权限状态联动：检测到 Root 权限时输入状态直接标绿，免去引导开启无障碍。*
- [`.github/workflows/build-android-arm64.yml`](.github/workflows/build-android-arm64.yml)  
  *精简且专一的 64 位 Android APK GitHub Actions 自动化编译流水线。*
