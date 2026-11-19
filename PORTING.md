# OpenAlive · LineageOS 专用适配说明

本 fork 只面向 **LineageOS / AOSP**。上游 `Maga-King/OpenAlive` 面向 ColorOS 17；这里移除了 ColorOS 运行路径、OEM 作用域、OEM 权限 shim 和所有 `su` 调用的息屏配置，只保留一条 AOSP 路径。渲染器、Rust、素材与构建脚本沿用上游。

---

## 一、运行时路径（唯一）

`app/assets/xposed_init` → `org.aliveclean.CleanHooks`

```
com.android.systemui
  ├─ 平台保护：检测到 SmoothTransitionController 直接拒绝加载（ColorOS 请用上游）
  ├─ ModuleActivationHook.install()     首页「LSP 已激活」回执
  └─ AospBridge.install(classLoader)   场景状态机

android (system_server)
  ├─ AospBridge.installFramework()     AMBIENT_WALLPAPER 权限适配
  └─ WallpaperInfo.supportsAmbientMode → 对本包返回 true
```

`AospSceneLink` 持有全部协议常量（`ACTION` = `org.aliveclean.SCENE`、`ACTION_LAYOUT` = `org.aliveclean.AOD_LAYOUT`、`SENDER_PERMISSION` = `android.permission.STATUS_BAR`）；`CleanWallpaper` 直接引用它们，运行时不再加载任何 ColorOS 类。壁纸暗色判定改用 AOSP `uiMode` 夜间标志，OEM 的 `oplus_customize_settings_dark_wallpaper` Secure 设置与其 ContentObserver 已移除。

作用域：`android` + `com.android.systemui`（`@array/xposed_scope`）。`com.oplus.wallpapers` 已从作用域和 `<queries>` 中移除。

---

## 二、真机 SystemUI 反编译核对（系统界面_17.apk，44 MB / 3 个 dex）

解析 string_ids / type_ids / method_ids / proto_ids，共 140803 条唯一字符串。`Lcom/oplus/...` 类计数 **0**，`SmoothTransitionController`、`AodData`、`OplusKeyguardViewMediatorExImpl`、`com.android.systemui.DependencyEx` 全部 MISSING → 确认纯 AOSP。

| 用途 | 类 |
|---|---|
| SystemUI 入口 | `com.android.systemui.application.impl.SystemUIApplicationImpl` |
| 息屏/唤醒 | `com.android.systemui.keyguard.KeyguardService$2` |
| 锁屏状态 | `com.android.systemui.keyguard.KeyguardViewMediator` |
| 解锁 | `com.android.keyguard.KeyguardUpdateMonitor` |
| 生物解锁 | `com.android.systemui.statusbar.phone.BiometricUnlockController` |
| 解锁动画 | `com.android.systemui.keyguard.KeyguardUnlockAnimationController` |

另两个候选 `application.SystemUIApplication`、`SystemUIApplication`（旧包）、`KeyguardViewMediatorImpl` 均 MISSING。

方法签名（method_ids + proto_ids 精确解析）：

```
SystemUIApplicationImpl.onCreate()                                     -> V
KeyguardService$2.onStartedGoingToSleep(I)                             -> V
KeyguardService$2.onStartedWakingUp(I, Z)                              -> V
KeyguardUpdateMonitor.setKeyguardGoingAway(Z)                          -> V
BiometricUnlockController.startWakeAndUnlock(I, BiometricUnlockSource) -> V
BiometricUnlockController.startWakeAndUnlock(BiometricSourceType, Z)   -> V
KeyguardUnlockAnimationController.notifyStartSurfaceBehindRemoteAnimation(
        [AnimatedSurface, [AnimatedSurface, [AnimatedSurface, J, Z)     -> V
KeyguardViewMediator.keyguardGone() 实际在 KeyguardViewMediator$5
```

`KeyguardViewMediator` 上**不存在** `onStartedGoingToSleep` / `onStartedWakingUp` / `keyguardGone`，早期版本把三者挂在它上面会静默失效。现优先 hook `KeyguardService$2`，用 `hookAllMethods` 兼容 `(I)` 与 `(I,Z)`；`keyguardGone` 挂 `$5`；保留旧候选做跨分支兼容。`AospSceneLink.dispatch` 有 250 ms 同 mode 去重，避免 binder stub 与广播重复触发。

`KeyguardViewMediator` 上仍可用：`setShowingLocked(Z, String, Z)`、`handleHide()`、`handleKeyguardDone()`、`cancelKeyguardExitAnimation()`、`finishSurfaceBehindRemoteAnimation(Z)`、`startKeyguardExitAnimation(...)` —— 阶段 2 解锁动画对齐用。

---

## 三、已修的坑

| 现象 | 根因 | 处理 |
|---|---|---|
| 应用壁纸弹「息屏配置未完成」 | `RootBridge.prepareAod` 硬查 `com.oplus.aod.AodMachineHelperProvider` | 改为纯 AOSP 分支，不起 `su` |
| 首页一直 `moduleActive=false` | 回执是进程内存态；覆盖安装清 App 记录，SystemUI 不自动重发；且旧代码单次直读不发 QUERY | `ModuleActivation.request()` + 最多 800ms 轮询；首页指示灯为准 |
| 抓不到系统进程日志 | Logcat Reader 未授 `READ_LOGS` 时只能看自身 UID；`main` 缓冲区被 `OPLUS_CHG` 冲爆 | 用 root 终端 `logcat -v threadtime AliveClean:I *:S` |
| `platform=AOSP` 不出现 | SystemUI 未重启，hook 只在进程启动时注入 | 整机重启 |

`pm grant com.dp.logcatapp android.permission.READ_LOGS && am force-stop com.dp.logcatapp` 可让 Logcat Reader 看全量，但实时 tag 过滤更可靠。

---

## 四、验证步骤

1. 装最新 Actions artifact
2. LSPosed：模块启用，作用域勾 `android` + `com.android.systemui`；换签名后取消再重勾
3. **重启手机**
4. 首页看 `LSP 已激活` / `ROOT 已授权`（点那行立即重查）
5. `su -c 'logcat -v threadtime AliveClean:I *:S'`，应用壁纸，息屏 → 亮屏 → 解锁

期望日志：

```
LineageOS build: installing AOSP bridge in com.android.systemui
AOSP bridge loaded
AOSP wakefulness: binderStub=true sleepHooks=1 wakeHooks=1
AOSP scene mode=0/1/2 phase=... state=... selected=true
LineageOS AOD: no OEM provider; moduleActive=true
```

`moduleActive=false` 且首页显示「LSP 已激活」时以首页为准；两者都 false 才是 LSPosed 配置问题。

---

## 五、遗留代码说明

`ColorOs*.java`（16 个）、`NativeClock*` 编辑器、`Platform.java`、`PlatformApply.java` 保留在仓库里但**运行时零引用**：

- `ColorOs*` 之间互相引用，`CleanWallpaper` 曾引用其广播常量，多个 `tools/check_*.py` 会校验相关源码，硬删会断编译
- `NativeClock*` 与 `vendor/flyme/editor-ui.dex` 里仍有 `com.oplus` 资源字符串，属于壁纸编辑器 UI，删了会破坏「山脉 / 银河」等样式
- `Platform.java` 仅保留供 `PlatformApply` 与独立 provider 检查编译通过，已标注 `@deprecated`

`CleanHooks` 不再加载它们，`CleanWallpaper` 已改用 `AospSceneLink` 常量。若将来要彻底清库，需要先补一套不依赖 OEM 资源的编辑器，再连同 31 个 check 脚本一起重写。

---

## 六、阶段路线

- **已完成**：作用域 / 权限适配、AOSP 场景状态机、真机类名修正、AOD 弹窗与回执诊断、签名接入、LineageOS 专用化
- **阶段 1**：AOD 自绘时钟接管息屏显示（`AodClockHost` / `AodClockView` 已具备，需接 `AospSceneLink` 的 mode 0 时序）
- **阶段 2**：解锁动画对齐（`KeyguardViewMediator` 的 `startKeyguardExitAnimation` / `finishSurfaceBehindRemoteAnimation`）
- **阶段 3**：清理遗留 ColorOS 代码与编辑器资源依赖

---

## 七、构建

签名由 4 个仓库 Secrets 注入，缺失时回落本地开发密钥。`Publish release` 会用 `release/expected.json` 做逐字节校验；源码已改动，哈希必然不同，发布会被拦下，但 APK artifact 照常产出。要发 Release 需重算清单。详见 [BUILD.md](BUILD.md)。


---

## 八、`LSP 未激活` 的判定边界（05:55 复测）

现象：首页 `LSP 未激活`，`moduleActive=false`，熄屏无效果，但锁屏与桌面有壁纸效果。

**这个组合是自洽的，不是壁纸引擎坏了**：锁屏/桌面的显示来自 `CleanWallpaper` 自身的可见性回调（`onVisibilityChanged` / Display state），平台照常渲染动态壁纸；而息屏 AOD 需要 `AospBridge` 在 SystemUI 里发 `mode=0`，模块没注入就没有这个信号。所以「锁屏/桌面有、AOD 没有」恰好等价于「SystemUI 未被注入」。

代码侧已逐项排除：

- `assets/xposed_init` = `org.aliveclean.CleanHooks`，构建脚本用 `aapt2 -A app/assets` 打包且组装时不过滤；现已加断言，缺了直接让 CI 失败
- `AndroidManifest.xml` 有 `xposedmodule=true` / `xposedminversion=82` / `xposedscope=@array/xposed_scope`
- `CleanHooks` 在 `com.android.systemui` 分支**无条件**调用 `ModuleActivationHook.install()` 与 `AospBridge.install()`；平台保护只在检测到 ColorOS 时拒绝，真机反编译确认 oplus 类计数 0
- `ModuleActivation.request()` 会向 SystemUI 发 `QUERY_MODULE_ACTIVATION` 并轮询 800ms；两次实时日志都返回 false，说明**那边没有接收器**

因此问题只在 LSPosed 管理器层。按优先级排查：

1. **模块开关**：管理器 → 模块 → OpenAlive，开关必须打开。换正式签名后 LSPosed 会当新 APK，旧开关/作用域记录失效
2. **作用域必须勾两项**：`系统框架(android)` **和** `系统界面 / SystemUI(com.android.systemui)`。只勾前者时 `installFramework` 会跑但**不写激活回执**（回执只由 SystemUI 侧 `ModuleActivationHook` 写），首页照样显示未激活——这与当前现象完全吻合
3. **整机重启**：Zygisk 只在进程启动时注入，SystemUI 是常驻进程，不重启不会重新加载
4. **管理器日志页**：LSPosed 自带的「日志」会显示模块加载成功/失败（如 `ClassNotFoundException`），比 logcat 按 tag 过滤更直接

手机 root 终端里验证 APK 是否具备模块身份：

```sh
su -c 'unzip -l $(pm path org.aliveclean | cut -d: -f2) | grep -i xposed'
```

应输出 `assets/xposed_init`。若无此行，LSPosed 根本不会把它列为模块。
