# OpenAlive → LineageOS / AOSP 移植（第一批）

目标：让 OpenAlive 在 LineageOS 上先跑起来，且不改渲染器、Rust、素材和构建脚本。

本批只动 `app/java/org/aliveclean/` 下的 4 个文件：

| 文件 | 动作 |
|---|---|
| `Platform.java` | 新增，按当前进程可见的类判断 ColorOS / AOSP |
| `AospSceneLink.java` | 新增，复刻与壁纸服务之间的 Binder 通道和场景消息 |
| `AospBridge.java` | 新增，用 AOSP keyguard 生命周期重建 mode 0/1/2 |
| `CleanHooks.java` | 覆盖，按平台分流两条桥 |

`app/assets/xposed_init` 不变，入口仍是 `org.aliveclean.CleanHooks`。

---

## 一、源码里真实的耦合点

入口与作用域

- `app/assets/xposed_init` → `org.aliveclean.CleanHooks`
- `@array/xposed_scope`：`android`、`com.android.systemui`、`com.oplus.wallpapers`
- `AndroidManifest.xml` 的 `<queries>`：`com.oplus.wallpapers`、`com.oplus.aod.AodMachineHelperProvider`、`com.coloros.gallery3d` / `com.oplus.gallery3d`、`com.heytap.themestore` / `com.oplus.themestore`

framework（system_server）

- `com.android.server.pm.IPackageManagerBase.checkPermission` — 强制授予 `AMBIENT_WALLPAPER`（OEM 私有类，AOSP 不存在）
- `WallpaperInfo.supportsAmbientMode()` — 对本包返回 true（AOSP 通用，保留）

SystemUI（ColorOS 专有，全部在 `ColorOsBridge` / `ColorOsAodClock` / `ColorOsUnlockHandoff` 里以反射硬编码）

- `com.oplus.systemui.aod.display.SmoothTransitionController` — 场景状态机：`shouldWindowBeTransparent`、`updateCurrentUiState`、`getInstance`、`getPowerOnUiState`
- `com.oplus.systemui.aod.aodclock.constant.AodData` — `getInstance`、`isPanoramicAod`、`mAodIsInShow`、`mAodOptionsMgr`
- `com.oplus.systemui.qs.observer.SuperPowerSaveSettingsObserver`
- `com.oplus.systemui.keyguard.OplusKeyguardViewMediatorExImpl` — `onStartedGoingToSleep` / `onStartedWakingUp` / `keyguardGone`
- `com.oplus.systemui.aod.display.AODDisplayUtil` — `requestScreenStateWhileDreamingStart`、`acquireWakeLockInWorkThread`、`mPerformAodType`
- `com.oplus.systemui.keyguard.anim.OplusKeyguardAppearAnimControllerImpl$PanoramicAodAppearAnimRunner*`
- `com.oplus.keyguard.OplusKeyguardUnlockAnimationControllerExImpl`、`OplusKeyguardUpdateMonitorExImpl`、`KeyguardRapidUnlockUtils`
- `com.oplusos.keyguard.utils.KeyguardThreadUtil`、`com.oplusos.systemui.common.sched.OplusSysUiSchedMgr`
- `com.oplus.systemui.keyguard.ui.interactor.OplusKgdMaskInteractor`、`OplusKgdAnimParams$PanelPanoramic`
- `com.oplus.systemui.keyguard.clockstyle.KeyguardStyleClockControllerImpl`、`com.oplus.systemui.aod.anim.OplusAODMaskAnimController`
- `com.oplus.systemui.keyguard.oneshot.OneShotAnimController` / `OneShotAnimState`
- 挂在 AOSP 命名空间下的 OEM 注入类：`com.android.systemui.DependencyEx`、`CentralSurfacesImpl`、`com.android.keyguard.OplusKeyguardDependencyEx`
- 私有通道：`WallpaperManager.sendWallpaperCommand("wallpaper.support.local.dark.mode")`、`Settings.Secure oplus_customize_settings_dark_wallpaper`

## 二、三分类

**直接复用（平台无关）**

- 187 个渲染 / UI 类：`CleanWallpaper`、`WallpaperRenderer`、各类 `*Renderer` / `*Motion`、`FrameCrop` / `PhotoCrop`、`Vivo*`、`Xiaomi*`、`Flyme*`
- 自绘时钟：`AodClockHost`、`AodClockView`、`AodClockFace`、`FlymeAliveAodFace`、`NativeClockFaces`
- 配置与 IPC：`SceneProvider`、`SceneChannel`、`SceneOptions`、`SceneState`、`SceneProvider.CONFIGURATION`
- `native/*.rs` → `libalive_clean.so`、`app/assets/` 全部素材、`vendor/flyme/editor-ui.dex`
- `WallpaperInfo.supportsAmbientMode` hook

**本批已重写**

- 场景状态机 → `AospBridge` 用 `KeyguardViewMediator` / `KeyguardUpdateMonitor` / `BiometricUnlockController` / `KeyguardUnlockAnimationController` 重建；另有 `ACTION_SCREEN_OFF` / `SCREEN_ON` / `USER_PRESENT` 广播兜底，保证即使所有类名都不匹配，场景切换仍然送达
- Binder 通道 → `AospSceneLink` 自己走 `SceneProvider.call("connect")`，消息字段与 `ColorOsBridge.send(1, b)` 完全一致：`mode` / `animate` / `time` / `phase` / `clock_wake`
- 权限 → `AospBridge.installFramework` 尝试 AOSP 的 `PackageManagerService` 检查点；按 uid 检查的分支需要把 APK 作为 priv-app 安装

**LineageOS 上没有宿主，需另做或放弃**

- 注入 ColorOS 系统锁屏编辑器的魅族 / 澎湃 / Vivo 时钟面板
- 全景 AOD、息屏通知光效、指纹 AOD 时钟遮罩、OPlus 原生时钟字体与样式
- `SmoothTransitionController` 的窗口透明时序、`OplusSysUiSchedMgr` 的 CPU 保活窗口、`wallpaper.support.local.dark.mode` 调光命令

## 三、阶段路线

- **阶段 0（本批）**：动态壁纸 + 息屏/亮屏/解锁场景切换在 LineageOS 跑通
- **阶段 1**：AOD 自绘时钟，用 `AodClockHost` 挂到 AOSP keyguard 根 View，替掉 `ColorOsAodClock` 的 oplus 反射
- **阶段 2**：用 AOSP `KeyguardUnlockAnimationController` 的 remote animation 时长对齐「息屏→锁屏→桌面」衔接
- **阶段 3（可选）**：直接改 LineageOS SystemUI 源码内嵌，脱离 LSPosed

## 四、构建

仓库不是 Gradle 工程，入口是 `tools/build_release.py`（Python + Rust + aapt2 / javac / D8）。

```
Ubuntu 24.04 / JDK 17 / Python 3.12 / Rust 1.96（rust-toolchain.toml 固定）
Android SDK Platform 35 + Build Tools 35.0.0 + NDK 29.0.14206865
rustup target add aarch64-linux-android
python3 tools/build_release.py        # 产物在 dist/
```

推 `v*` 标签可由 GitHub Actions 完成同一套编译并产出 APK。`vendor/flyme/editor-ui.dex` 是预编译 DEX，不参与源码编译。

## 五、需要你做的

1. **设备信息**
   ```
   adb shell getprop ro.build.version.release
   adb shell getprop ro.lineage.version
   adb shell getprop ro.product.device
   ```
   再告诉我是否已 Root + LSPosed、以及刷的是哪个 LineageOS 版本（仓库分支 `lineage-24.0`）。

2. **SystemUI 真实类名**（用来把 `AospBridge` 里的候选类名改成确定值）
   ```
   adb shell pm path com.android.systemui
   adb pull <上一步输出的路径> SystemUI.apk
   ```
   把 `SystemUI.apk` 传给我或给个下载链接。同时抓一份日志：
   ```
   adb logcat -s AliveClean XposedBridge
   ```

3. **确认推送结果**
   本次改动已提交到 fork 的 `main`：`app/java/org/aliveclean/` 下新增 3 个类、`CleanHooks.java` 已覆盖，无需手动复制。如需独立分支，从本次 commit 切出即可。

4. **构建出包（已跑通，正式签名已配置）**
   fork 不继承上游 Actions Secrets，首次手动触发在 `Load signing key` 失败；`8abb71c` 让缺 Secret 时回落到本地开发密钥，run `44599949579` 全绿。

   现已把 JKS 密钥写入仓库 Secrets：`OPENALIVE_KEYSTORE_B64`、`OPENALIVE_STORE_PASSWORD`、`OPENALIVE_KEY_ALIAS`（`星觅海`）、`OPENALIVE_KEY_PASS`。workflow 把别名和私钥密码透传给编译步骤，`tools/build_release.py` 在检测到 `OPENALIVE_KEY_PASS` 时补 `--key-pass`。CI 产出的 APK 由这把密钥签名，证书 SHA-256：
   `C4BD2C4E5C4A1F924C060F3A233ACB5A7DA2C36D29A59ED4A6E2375C06ABC6E1`

   `Publish release` 仍会跑 `tools/verify_release.py` 与 `release/expected.json` 的逐字节校验；源码已改动，哈希必然不同，发布会被拦下，但 APK artifact 照常产出。要发 Release 需另开一轮更新清单。

5. **真机验证一次**
   LSPosed 作用域勾 `系统框架(android)` + `系统界面(com.android.systemui)`；`com.oplus.wallpapers` 在 LineageOS 上不存在，不用找。打开 App 选一款不依赖 AOD 的样式（山脉 / 银河），息屏 → 亮屏 → 解锁各一次，把 `adb logcat -s AliveClean` 贴回来。我按日志接阶段 1。
