# OpenAlive for LineageOS

把 Flyme Alive 的连贯动画带到 **LineageOS / AOSP**，让息屏、锁屏和桌面接起来。

> 这是 [`Maga-King/OpenAlive`](https://github.com/Maga-King/OpenAlive) 的 **LineageOS 专用 fork**。上游面向 ColorOS 17，靠 `com.oplus.*` 反射驱动息屏状态机；本 fork 移除了整条 ColorOS 运行路径，只保留 AOSP 桥接。刷了 ColorOS 请回到上游，本 fork 会明确拒绝加载。

## 包含什么

- 启航、留光、星月、轻启、山脉、银河，可自选照片；全屏比例裁切、双指缩放、拖动与复位
- 留光支持独立相框照片、取景和角度调整
- 魅族动态壁纸与通知光效（来通知短暂点亮息屏、系统光效、Flyme 圆圈与双侧光带）
- Vivo Alive 液态、趣味光栅、闪卡；小米 Alive 月球、雪山、几何、土星环、地球、火星（素材已内置，首次点击自动准备）
- 息屏→锁屏→桌面连贯过渡：壁纸自身用 `ACTION_SCREEN_OFF/ON`、`ACTION_USER_PRESENT` 和 Display doze 状态驱动 mode 0/1/2，**不依赖 LSPosed**；`AospBridge` 只是可选的精细信号增强

渲染器、Rust 库、素材和壁纸编辑器与上游一致，平台无关。

## 要求

- **LineageOS / AOSP 系 ROM**（已在 sm8750 / lineage-24.0 上核对 SystemUI 类名）
- **锁屏/桌面不需要 LSPosed**：壁纸自身的广播状态机驱动 mode 0/1/2，普通安装即可生效
- **息屏画面需要 `系统框架(android)` 这一项**：壁纸声明 `supportsAmbientMode=true`，AOSP 才会在 dozing 时保留壁纸窗口并回调 `onAmbientModeChanged`；随之而来的 `AMBIENT_WALLPAPER` 签名权限由 framework hook 精确放行。本机 LSPosed 稳定注入 system_server，这一项一直在工作
- `系统界面(com.android.systemui)` 现在**也是息屏画面的必要条件**：`AospDozeHooks` 在 dozing 时把 scrim 归零、给 NotificationShade 加 `FLAG_SHOW_WALLPAPER`；`AospBridge` 在 framework 侧自适应探测并 hook 壁纸窗口可见性方法。作用域保持两项不变
- 需要能注入 SystemUI 的框架组合：已验证 **Zygisk Next 1.5.3 + LSPosed v2.2.1** 可用；NyaZygisk v2.5-383 只注入 system_server，息屏会退回系统 AOD
- `com.oplus.wallpapers` 在 LineageOS 上不存在，不要找、不要勾
- 开机后直接 `su -c 'logcat -v threadtime AliveClean:I *:S'` 即可看全部诊断（framework 与 SystemUI 的 hook 命中数、真实方法名清单都双写进 `AliveClean` tag），不必再导 LSPosed 诊断包
- 改完作用域仍需**整机重启**，hook 只在进程启动时注入

## 安装与验证

1. 在 [Actions](https://github.com/xingmihai/OpenAlive/actions) 里取最新成功运行的 `OpenAlive-arm64-v8a` artifact，安装 APK
2. LSPosed 里启用模块，确认上述两项作用域；换签名后建议取消再重勾一次
3. 整机重启
4. 打开 App 选「山脉」或「银河」应用壁纸；桌面、锁屏立即生效，息屏→亮屏→解锁会切 mode 0/1/2
5. 选「山脉」或「银河」应用壁纸，然后息屏 → 亮屏 → 解锁

抓日志（推荐，绕过任何日志 App 的权限与过滤限制）。场景状态机现在无条件输出，不再需要打开 TRACE：

```sh
su -c 'logcat -v threadtime AliveClean:I *:S'
```

期望看到（息屏 → 亮屏 → 解锁）：

```
AliveClean: Wallpaper engine created preview=false
AliveClean: Screen event=android.intent.action.SCREEN_OFF -> mode 0 phase=1
AliveClean: Scene mode=0 changed=true ...
AliveClean: Screen event=android.intent.action.SCREEN_ON -> mode 1 phase=2
AliveClean: Scene mode=1 changed=true ...
AliveClean: Screen event=android.intent.action.USER_PRESENT -> mode 2 phase=3
AliveClean: Scene mode=2 changed=true ...
AliveClean: Visibility: wallpaperVisible=... displayState=... render=... mode=...
```

`org.aliveclean` 进程里的 `hiddenapi: BackgroundBlurDrawable`、`Invalid resource ID 0x00000000` 来自 `vendor/flyme/editor-ui.dex`（魅族官方预编译 UI），LineageOS 不放行这些隐藏 API，只影响背景模糊，不影响壁纸与场景切换。

## 已知限制

- **AOD 自绘时钟仍在阶段 1**：场景过渡已通，息屏时钟的自绘接管尚未接完
- 内核里的 `OPLUS_CHG` 是充电硬件驱动日志，与 SystemUI 无 `com.oplus.*` Java 类不矛盾
- 这台机器 `main` 缓冲区被充电/音频日志冲得极快，事后 `logcat -d` 常抓不到开机日志，用上面的实时过滤命令
- 上游遗留的 `ColorOs*.java`、`NativeClock*` 编辑器代码与 31 个 `tools/check_*.py` 深度耦合，**保留但运行时零引用**：`CleanHooks` 不再加载它们，`CleanWallpaper` 也已改为引用 `AospSceneLink` 的协议常量

## 编译

见 [BUILD.md](BUILD.md)。签名由仓库 Actions Secrets 注入（`OPENALIVE_KEYSTORE_B64` / `OPENALIVE_STORE_PASSWORD` / `OPENALIVE_KEY_ALIAS` / `OPENALIVE_KEY_PASS`），未配置时回落本地开发密钥。源码已改动，`Publish release` 的逐字节校验会拦下发布，但 APK artifact 照常产出。

## 移植记录

完整的类名/签名核对、修复历程与阶段路线见 [PORTING.md](PORTING.md)。

## 许可

自实现代码采用 [MIT](LICENSE)。**部分资产来源于魅族22 FlymeOS**，官方 UI、字体、壁纸及效果素材不纳入 MIT 授权，详见 [THIRD_PARTY.md](THIRD_PARTY.md)。上游作者与赞赏码见原仓库。
