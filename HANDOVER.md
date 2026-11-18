# 按键报时 App · 交接文档

> 用途：给**接手/维护**本项目的人一份速查。开发过程与踩坑细节见 `DEVELOPMENT_GUIDE.md`，
> 本文件只列"事实、命令、模块职责、已知坑、怎么改"。

---

## 1. 项目概况

| 项 | 值 |
|---|---|
| 名称 | 按键报时（KeyTime） |
| 包名 | `com.example.keytime`（全局唯一 ID，升级/权限都靠它） |
| 当前版本 | **3.7.0**（versionCode 24） |
| 语言 / 构建 | Kotlin + AGP 8.7.3 + Gradle 8.9 |
| 最低/目标 SDK | minSdk 26（Android 8）/ targetSdk 35（Android 15） |
| 目标机型 | 红米 Turbo 4 Pro · Xiaomi HyperOS 3（其他安卓机也可） |
| 功能 | 息屏按音量键 → 语音报时"13点51分" |
| 核心机制 | **MediaSession 远程音量接管**（v3.0）；v2.0 的音量监听作静默兜底 |
| 权限 | `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS` / `WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` |
| **不申请** | `INTERNET`、无障碍（Accessibility） |

> ⚠️ **HyperOS 硬性结论**：对个人侧载应用，系统会**系统级过滤**无障碍服务（写入即剥离、设置里不出现、深链签名权限拒绝）。**任何基于此机的无障碍方案都不可用**，只能走 MediaSession / 音量监听这类免授权路线。

---

## 2. 工具链与环境

| 工具 | 配置方式 |
|---|---|
| JDK 17 | 环境变量 `JAVA_HOME` 指向本机 JDK 17 |
| Android SDK | 环境变量 `ANDROID_HOME`（platform-35 / build-tools 35.0.0） |
| Gradle | 工程内 wrapper 已指定 8.9 |
| 签名 keystore | 可选，由环境变量 `KEYTIME_KEYSTORE` / `KEYTIME_STORE_PASSWORD` / `KEYTIME_KEY_ALIAS` / `KEYTIME_KEY_PASSWORD` 提供 |
| adb | `$ANDROID_HOME/platform-tools/adb` |

> 🔑 **keystore 是命根子**：源码中不含 keystore 路径与密码。密钥请自行生成并离线备份，
> 绝不提交到版本库；丢失后同包名应用将无法覆盖升级。

---

## 3. 构建 / 安装 / 验证 命令

```bash
# 在项目根目录执行
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease

# 安装（-r = reinstall；签名一致时才可覆盖）
adb install -r app/build/outputs/apk/release/app-release.apk

# 验证在机版本
adb shell dumpsys package com.example.keytime | grep versionName
```

产物：`app/build/outputs/apk/release/app-release.apk`（调试版在 `.../debug/app-debug.apk`）。

> 包名已从原私有命名空间改为 `com.example.keytime`。若设备上装有旧包名版本，需先卸载，
> 不能覆盖升级；两个版本会并存为两个应用。若日后要上架应用市场，请再把包名改成自己拥有的域名反写形式。

---

## 4. 架构与模块职责

```
MainActivity.kt               设置页（**打开 App 即自动启动服务**、可手动停止、按键/声道选择、测试报时、权限请求）
TimeAnnouncerService.kt       前台服务：激活 MediaSession 拦截器 + 兜底监听 + 息屏判断 + 报时
  └─ 持有 VolumeKeyInterceptor（v3.0 主机制）
VolumeKeyInterceptor.kt       MediaSession + 远程 VolumeProvider，onAdjustVolume → 报时回调
TimeSpeaker.kt                TTS 封装：异步就绪标志、声道、WakeLock、去重、报时文本
Notifier.kt                   通知渠道 + 常驻通知（状态/暂停按钮）
NotificationActionReceiver.kt 通知栏"暂停/恢复"按钮
Prefs.kt                      SharedPreferences 配置（含 bootStartEnabled 开关）
BootCompletedReceiver.kt      开机自启：BOOT_COMPLETED/LOCKED_BOOT_COMPLETED → 启动前台服务
WatchdogReceiver.kt           v3.4.3 看门狗：AlarmManager 每分钟检查服务真实存活，死了自动拉起（+ Watchdog object 管注册/取消）
AutoSchedule.kt               v3.7.0 定时自动控制：nextEvent 纯函数算下一次触发 + AlarmManager 排程；AutoScheduleReceiver 到点切换 paused 并续排
DebugLog.kt                   内部日志文件 files/debug.log（run-as 可读）
DebugReceiver.kt              调试广播（op=start|stop|status|speak|clear|vol|fake|always|notify|volset|volmax|volstatus|autoset|autostatus|autofire|manualtoggle）
AndroidManifest.xml           组件/权限/<queries>/specialUse FGS 声明
```

**数据流（v3.0 主路径）**：
`息屏按音量键` → 系统交给活跃 MediaSession → `VolumeProvider.onAdjustVolume` → 服务 `handleVolumeKey` → 息屏且未暂停 → `TimeSpeaker.speakCurrentTime()` 报时。

---

## 5. 配置项（Prefs）

| 字段 | 默认 | 含义 |
|---|---|---|
| `triggerDown` | true | 音量下键作为报时键 |
| `triggerUp` | false | 音量上键作为报时键 |
| `paused` | false | 暂停报时（服务仍运行，仅不播报） |
| `useAlarmStream` | true | 走闹钟声道（夜间更可靠）；false 走媒体声道 |
| `listenVolume` | true | 是否注册 v2.0 兜底监听（广播/观察者/轮询） |
| `debugAlways` | false | 调试：忽略"仅息屏响应"判断 |
| `bootStartEnabled` | true | 开机后自动启动报时服务（App 内可关） |
| `announceVolumePct` | 80 | 报时音量 0–100%（TTS KEY_PARAM_VOLUME 增益，不改系统音量） |
| `announceVolumeMax` | false | 用最大音量播报（忽略百分比）；`announceVolumeFraction` 是实时计算值 |
| `autoEnabled` | false | 定时自动控制总开关 |
| `autoOnMinute` | 1320 (22:00) | 自动开启时刻（当天分钟数 0..1439） |
| `autoOffMinute` | 420 (07:00) | 自动暂停时刻 |
| `autoRepeatMask` | 127（每天） | 重复日 bitmask：bit0=周日…bit6=周六 |
| `manualOverrideAtMs` | 0 | 手动覆盖时间戳；自动事件到点时清零 |
| `lastAutoFireMs` / `lastAutoAction` | 0 / "" | 最近一次自动触发时间与动作，供展示/防重复 |

---

## 6. 已知坑 · 速查表

| # | 现象 | 根因 | 解决 |
|---|---|---|---|
| 1 | 点测试无声音 | TTS 异步未就绪，`speak()` 被丢弃 | `initialized` 标志 + `pendingText` 挂起补播 |
| 2 | TTS 初始化 status=-1 / AppsFilter BLOCKED | Android 11+ 包可见性过滤 | Manifest `<queries>` 声明 `TTS_SERVICE` |
| 3 | 无障碍服务写不进系统（~70ms 被剥离） | HyperOS 系统级过滤个人应用无障碍 | **放弃无障碍**，改 MediaSession/音量监听 |
| 4 | 无障碍深链跳不过去 | `OPEN_ACCESSIBILITY_DETAILS_SETTINGS` 是签名级权限 | 同 3，绕开 |
| 5 | logcat 无本 App 日志 | HyperOS 屏蔽本应用 logcat | `DebugLog` 写文件 + `run-as` 读 |
| 6 | `adb input keyevent` 无效 | USB 调试安全设置禁输入注入 | 应用内广播 `op=vol/fake` 自测 |
| 7 | `settings get system volume_*` 与真实值不符 | `Settings.System` 音量已废弃/过期 | 用 `AudioManager` / `cmd media_session volume --get` |
| 8 | 物理音量键不改变任何音量流数值 | HyperOS 息屏下不产出真实音量变化 | **改用 MediaSession 接管按键事件**（v3.0） |
| 9 | 通知不显示 | Android 8+ 未建 NotificationChannel | `Notifier.ensureChannel()` |
| 10 | 自定义 action 的 `am broadcast -a X -p pkg` 不触发 manifest receiver | Android 8+ 限制**隐式广播**投递给 manifest 注册的 receiver（`BOOT_COMPLETED` 等系统广播豁免） | 验证接收器逻辑时用**显式广播** `am broadcast -n 包名/.Receiver -a X`；正式功能靠系统豁免的 `BOOT_COMPLETED` |
| 11 | 上划划掉 App 再打开，界面显示"运行中"但息屏按键无响应 | HyperOS `SwipeUpClean` 杀进程并把 App 列入清理名单；界面不可见即杀服务实例；`START_STICKY` 重启被推迟到无效时间（`restartDelay=-2d19h...`）；且服务实例被杀时 `onDestroy` 未必执行，静态 `isRunning` 停在 true 造成假状态 | **AlarmManager 看门狗**（`WatchdogReceiver`，每分钟查 `isRunning && 会话 active`，死了拉起）+ `MainActivity.onResume` 假状态校正 + 最近任务卡片**加锁**根治 |
| 12 | 用户点"停止"后服务被自动拉回 | 看门狗误判：`userStopped` 标志在 `onDestroy` 里被过早复位 | 标志保留到 `onStartCommand`（服务真正起来）才复位；`op=stop`/停止按钮先 `markUserStopped` 再 `stopService`，并 `Watchdog.cancel` |
| 13 | 服务在跑、按键检测到、`SPEAK -> true`，却听不到声音 | 小米 TTS 引擎 `com.xiaomi.mibrain.speech` 的绑定长期闲置后静默失效：`speak()` 返回 SUCCESS 但音频层不创建播放器；旧代码未检查返回值、`onStart/onError` 空实现，日志谎报成功 | `TimeSpeaker` 加**闲置 60s 重建绑定** + `UtteranceProgressListener` 健康监控（1.5s 无 onStart 即重建补播一次）+ 全链路日志 + `FLAG_AUDIBILITY_ENFORCED`。判据：`dumpsys audio` 的 player 事件里必须有 `new player ... usage=USAGE_ALARM event:started` |

---

## 7. 二次开发指引

- **改报时格式**：改 `TimeSpeaker.currentTimeText()`（如加"现在时间是"前缀、改 12 小时制）。
- **换/加触发键**：当前只接音量键（系统不允许第三方监听电源键）。若要"双击音量键"之类，在 `handleVolumeKey` 里加计数/时序逻辑；若要其它手势，需换机制（如 `MediaSession` 的 `onMediaButton` 或 `Gesture`——受系统限制）。
- **换 TTS 引擎/语言**：`TimeSpeaker` 里 `setLanguage(Locale.CHINA)`；若要用离线/第三方引擎，在 `TextToSpeech` 构造时传 `enginePackageName`。
- **加新调试操作**：在 `DebugReceiver.onReceive` 的 `when(op)` 里加分支即可。
- **增加配置项**：在 `Prefs.kt` 加属性 + `strings.xml`/`activity_main.xml` 加 UI（如新增 Switch/Radio）。

---

## 8. 排查清单（用户报障时）

**完全没声音**
1. App 内点"启动报时服务"，通知栏出现"按键报时运行中"？没有 → 服务未起，检查是否误卸载。
2. 闹钟/媒体音量是否为 0？勿扰是否"完全静音"？（默认走闹钟声道，勿扰"仅闹钟"仍响）
3. 设置 → 无障碍 → 文字转语音输出，是否有中文语音且已下载语言包？
4. 真机查日志：`adb shell run-as com.example.keytime cat files/debug.log`，看是否有 `SPEAK` / `onAdjustVolume` 行。

**夜间不报时（服务被杀了）**
1. 省电策略是否仍是"无限制" + 自启动允许？（HyperOS 更新后可能重置）
2. 最近任务里本 App 卡片是否还"加锁"？
3. 服务 `START_STICKY` 应自启；若仍掉，看 `dumpsys package` 是否被强制停止。

**音量条变小但音量没变** → 这是 v3.0 的**预期行为**（见 DEVELOPMENT_GUIDE 5.6），不是 bug。

---

## 9. 风险与待办

- [x] **App 内说明文案已更新为 v3.0**：`res/values/strings.xml` 的 `guide_text` / `trigger_hint` 已在 2026-10-13 改为 v3.0 表述并重新构建安装。
- [ ] **开机自启依赖"自启动"授权**：HyperOS 下若用户未在系统设置允许本应用自启动，`BOOT_COMPLETED` 不会投递，重启后不会自动启动（手动打开 App 仍会自动启动）。已在 App「播放」卡片提供开关，并在 README 第 2、3 步强调。真机验证需用户**重启一次手机**确认。
- [ ] **调试通道保留与否**：`DebugReceiver`（exported=true）与 `DebugLog` 当前保留，便于 HyperOS 后续 OTA 后排障；若担心暴露面，可后续设为 `exported=false` 或移除。
- [ ] **v2.0 兜底监听冗余**：三道监听（广播/观察者/轮询）在本机为静默状态，占用极小；若追求极简可_gate_掉，但保留作其它 ROM 的兼容兜底。
- [ ] **未做混淆/加固**：`minifyEnabled=false`，反编译门槛低；个人工具可接受。
- [ ] **无自动测试**：仅真机手动验证；如需回归保障，可补 `adb broadcast` 驱动的冒烟脚本。

---

*维护提示：任何改动后务必 `assembleRelease` 重新构建并 `install -r` 覆盖验证；改签名前先确认旧包可卸载。*
