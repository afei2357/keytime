# 按键报时 App 开发实录：用一个真实案例学会安卓开发

> 读者画像：你**完全不会写安卓程序**，但想通过"我给自己写了一个睡觉时按音量键报时的 App"这个完整案例，
> 把安卓开发的方法摸一遍。本文不追求把安卓所有知识都讲完，而是**沿着这个项目真实走过的每一步**，
> 把用到的概念、踩过的坑、怎么解决，全部拆开讲清楚。读完后你应该能：
> - 看懂一个安卓工程由哪些文件组成、各自干什么；
> - 理解"前台服务 / 广播 / 通知 / MediaSession / 文字转语音"这些组件是什么；
> - 掌握"在真机上构建—安装—调试"的完整流水线；
> - 最重要的是，学会**面对一个安卓问题时，怎么拆解、怎么验证、怎么绕过系统限制**的方法论。

---

## 目录

1. [需求与产品定义](#1-需求与产品定义)
2. [安卓开发最小知识地图（给零基础）](#2-安卓开发最小知识地图给零基础)
3. [工程结构总览](#3-工程结构总览)
4. [开发环境搭建](#4-开发环境搭建)
5. [版本迭代全记录：每个坑是怎么踩、怎么填的](#5-版本迭代全记录每个坑是怎么踩怎么填的)
   - 5.1 [v1.0：无障碍服务拦截按键（初版思路）](#51-v10无障碍服务拦截按键初版思路)
   - 5.2 [坑 1 & 2：TTS 异步竞态 + 包可见性](#52-坑-1--2tts-异步竞态--包可见性)
   - 5.3 [坑 3 & 4：HyperOS 把无障碍服务"系统级过滤"掉](#53-坑-3--4hyperos-把无障碍服务系统级过滤掉)
   - 5.4 [v2.0：改用前台服务 + 音量监听](#54-v20改用前台服务--音量监听)
   - 5.5 [坑 5~8：看不见日志、注入不了按键、设置值过期、音量值根本不变](#55-坑-58看不见日志注入不了按键设置值过期音量值根本不变)
   - 5.6 [v3.0：MediaSession 远程音量接管（最终可用）](#56-v30mediasession-远程音量接管最终可用)
6. [关键代码走读](#6-关键代码走读)
7. [构建 / 签名 / 安装 / 调试流水线](#7-构建签名安装调试流水线)
8. [方法论总结：从这个案例能学到的"心法"](#8-方法论总结从这个案例能学到的心法)
9. [知识延伸（本案例没覆盖、但你应该知道的）](#9-知识延伸本案例没覆盖但你应该知道的)

---

## 1. 需求与产品定义

**痛点**：睡觉时不想睁眼看手机时间。希望**不亮屏、只按一下手机按键**，就听到语音报时，例如现在是 13:51，就播报"13点51分"。

**功能规格**：
- 息屏/锁屏状态下，按音量键（默认下键）→ 语音播报当前时间（24 小时制，"13点51分"）。
- 亮屏时按键保持正常调音量（不要乱报时打扰用户）。
- 可配置：报时按键（下键/上键/双键）、播放声道（闹钟声道/媒体声道）、暂停开关。

**硬约束（决定了整个技术路线）**：
- **不联网**（不申请 `INTERNET` 权限）——隐私、也避免被系统当作后台耗电应用。
- **不读取屏幕内容**。
- **不申请无障碍（Accessibility）权限**——后来发现这不仅是"不申请"的选择，而是 HyperOS 根本不允许个人侧载应用开启它（见 5.3）。

> **学到的第 1 课**：需求里的"约束"往往比"功能"更影响架构。本项目就是因为"不能用无障碍"，
> 才被迫走了后面那条很长的路。写安卓 App 前，先想清楚**系统允许你做什么**，而不是"我想要做什么"。

---

## 2. 安卓开发最小知识地图（给零基础）

下面这些概念本项目全用到了。先建立直觉，后面代码就好懂了。

### 2.1 四大组件（App 的"零件"）

安卓 App 不是像写 Python 脚本那样从头到尾一行行执行，而是由系统托管的几种**组件**拼起来的：

| 组件 | 一句话理解 | 本项目用在哪 |
|---|---|---|
| **Activity** | 一个"屏幕界面"，用户看得见、能交互 | `MainActivity`：设置页（启动按钮、按键选择） |
| **Service** | 后台干活的组件，没有界面 | `TimeAnnouncerService`：常驻后台，等按键来就报时 |
| **BroadcastReceiver** | 监听"系统广播事件"的接收器（如屏幕亮灭、通知按钮点击） | `NotificationActionReceiver`（通知暂停键）、`DebugReceiver`（调试） |
| **ContentProvider** | 跨应用共享数据（本项目没直接用，但系统设置就是它） | — |

**关键直觉**：组件不是你 `new` 出来调用的，而是**通过 `Intent`（意图）向系统"申请"启动**，由系统去创建和管理生命周期。比如"启动报时服务"是 `ContextCompat.startForegroundService(this, Intent(this, TimeAnnouncerService::class.java))`。

### 2.2 前台服务（Foreground Service）

普通 Service 在后台会被系统杀得很狠（尤其国产 ROM 省电策略）。**前台服务**是"用户能感知到在运行"的服务，必须在状态栏显示一条**常驻通知**，系统就不太敢杀它。

本项目必须常驻（睡觉时要一直在），所以用前台服务 + 一条"按键报时运行中"的通知。Android 8（API 26）起，通知**必须先建一个 NotificationChannel（通知渠道）**才能发，否则崩溃——这是一个经典的必踩点（见 7 节）。

> 安卓 14（API 34）起，前台服务还要在 Manifest 里声明 `foregroundServiceType`，且绝大多数类型需要对应的**特殊权限**。本项目用 `specialUse`（特殊用途）类型，并配一个 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明字符串，这是官方给"不属于标准类别"的服务留的口子。

### 2.3 权限模型

- **普通权限**（如 `FOREGROUND_SERVICE`）：安装即授予，不用弹窗。
- **危险权限**（如 `POST_NOTIFICATIONS` 在 Android 13+）：要弹窗让用户同意。
- **特殊权限 / 签名权限**：比危险权限还严。例如"无障碍设置深链"是**签名级**权限，普通应用连跳转都跳不了（见坑 4）。

本项目刻意只申请三类普通/危险权限（前台服务、特殊用途前台服务、通知、WakeLock），**不碰**无障碍与网络。

### 2.4 Manifest（清单文件）

`AndroidManifest.xml` 是 App 的"户口本"：声明有哪些组件、要哪些权限、面向哪个 SDK、有什么特殊属性。**不在这里声明，系统就不知道这个组件存在**（例如不声明 `<service>` 就 `startService` 会报错）。

### 2.5 资源与代码分离

界面文字、布局、图标放在 `res/` 目录（如 `res/values/strings.xml` 存所有文案，`res/layout/activity_main.xml` 存界面布局），代码放在 `java/`。这样做便于多语言、多屏幕适配。本项目所有中文文案都集中在 `strings.xml`，改界面文字不用动代码。

### 2.6 Gradle 构建系统

安卓不靠你手动 `javac`，而是用 **Gradle** 这个构建工具：它读 `build.gradle.kts` 里的配置（SDK 版本、依赖库、签名），自动编译、打包成 `.apk`。命令行入口是 `gradlew`（gradle wrapper，自动下载匹配版本的 Gradle）。

### 2.7 Kotlin 极简说明

本项目用 **Kotlin**（安卓官方首选语言）。对零基础只需知道：
- 用 `val`（不可变）/`var`（可变）声明变量；
- 类、函数、`override` 重写父类方法；
- 很多"回调"写成**匿名对象**或 **lambda**（`{ ... }`）；
- 空安全：`Type?` 表示"可能为空"，`x?.let { }` 表示"如果 x 非空就执行"。

看代码时当成"带类型的现代脚本语言"即可，不影响理解逻辑。

---

## 3. 工程结构总览

```
项目根目录/
├── app/
│   ├── build.gradle.kts          # 模块级构建配置（SDK 版本、依赖、签名）
│   ├── proguard-rules.pro        # 代码混淆规则（本项目未开启混淆）
│   └── src/main/
│       ├── AndroidManifest.xml   # 清单：组件声明、权限、queries、FGS 类型
│       ├── java/com/example/keytime/
│       │   ├── MainActivity.kt               # 设置页（Activity）
│       │   ├── TimeAnnouncerService.kt        # 前台服务：激活拦截器+兜底监听+报时
│       │   ├── VolumeKeyInterceptor.kt        # v3.0 主机制：MediaSession 接管音量键
│       │   ├── TimeSpeaker.kt                 # 文字转语音封装（TTS）
│       │   ├── Notifier.kt                    # 常驻通知 + 通知渠道
│       │   ├── NotificationActionReceiver.kt  # 通知栏"暂停/恢复"按钮
│       │   ├── DebugLog.kt                    # 内部日志文件（绕开 logcat 屏蔽）
│       │   ├── DebugReceiver.kt                # 调试广播接收器
│       │   └── Prefs.kt                        # 配置（SharedPreferences）
│       ├── res/
│       │   ├── layout/activity_main.xml        # 设置页界面
│       │   ├── values/strings.xml             # 所有文案
│       │   ├── values/themes.xml, mipmap-*      # 主题与图标
│       │   └── drawable/ic_stat_time.xml       # 通知小图标
│       └── assets/  (无)
├── build.gradle.kts              # 项目级构建配置（插件版本）
├── gradle/wrapper/gradle-wrapper.properties  # 指定 Gradle 8.9
├── local.properties              # 本地 SDK 路径（通常自动生成，不进版本库）
├── README.md                     # 用户向使用说明（v3.0）
├── DEVELOPMENT_GUIDE.md          # 本文件
└── HANDOVER.md                   # 交接文档
```

**包名** `com.example.keytime` 是 App 的全局唯一 ID，安装、升级、权限都靠它区分。

---

## 4. 开发环境搭建

本项目在 **Windows** 上用命令行构建，不依赖 Android Studio（但用 Studio 打开工程也能编译）。

需要的工具（请自行安装，通过环境变量指向本机路径）：

| 工具 | 作用 | 配置方式 |
|---|---|---|
| **JDK 17** | 编译 Kotlin/Java（AGP 8 要求 JDK 17） | 设置 `JAVA_HOME` |
| **Android SDK** | 提供安卓平台与构建工具 | 设置 `ANDROID_HOME`（含 platform-35、build-tools 35.0.0） |
| **Gradle 8.9** | 构建引擎（由 gradlew 自动调用） | 工程内 wrapper 已指定版本 |
| **签名 keystore** | 给 APK 签名（可选，见下） | 设置 `KEYTIME_KEYSTORE` 等四个环境变量 |
| **adb** | 安卓调试桥，用来装 App、读日志 | `$ANDROID_HOME/platform-tools/adb` |

> **为什么需要"签名"？** 安卓要求每个安装包必须有数字签名，作用相当于"作者印章"。同一包名的 App，只有**签名相同**才能覆盖升级；换签名系统会拒绝安装（防篡改）。keystore 文件不随源码分发，请自行生成并妥善保管；丢失后就再也无法原地升级这个包名的 App 了。

构建命令（在项目根目录执行）：

```bash
# 设置环境变量指向本机已安装的 JDK / Android SDK
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

# 调试版（可 run-as 读内部日志）
./gradlew assembleDebug

# 正式版（未配置签名环境变量时使用默认调试签名）
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease

# 产物位置
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/release/app-release.apk
```

> **学到的第 2 课**：构建环境本身就是一道门槛。把工具路径、Gradle 版本、SDK 版本、签名配置固定下来并写进文档，是"可复现交付"的第一步。

---

## 5. 版本迭代全记录：每个坑是怎么踩、怎么填的

这是本文核心。一个 App 不是一次写对的，而是**在不断被系统"打脸"中迭代出来的**。下面按时间线讲。

### 5.1 v1.0：无障碍服务拦截按键（初版思路）

**最初想法**：安卓的"无障碍服务（AccessibilityService）"可以监听并截获按键事件（`FLAG_REQUEST_FILTER_KEY_EVENTS`），在音量键被系统处理前把它拦下来，直接报时，体验最好——音量完全不变。

概念上：写一个继承 `AccessibilityService` 的类，在 `onKeyEvent` 里判断是不是音量键，是就 `speak()` 并 `return true`（消费掉，不让系统调音量）。

**为什么这条路子最终被放弃**：在小米 HyperOS 上，个人从电脑 `adb install` 装的应用，其无障碍服务会被**系统级过滤**——详见 5.3。但在放弃之前，初版还先撞上了两个**所有 TTS 应用都会遇到的通用坑**：

### 5.2 坑 1 & 2：TTS 异步竞态 + 包可见性

#### 坑 1：装好后点"测试"没声音

**现象**：App 装好，点"测试报时"按钮，毫无声音。

**根因机制**：`TextToSpeech`（TTS，文字转语音）的初始化是**异步**的。`new TextToSpeech(context, listener)` 只是发起"去绑定语音引擎"，真正的就绪发生在之后的回调 `onInit(status)` 里。初版代码用 `tts != null` 判断能不能播，但 `tts` 非空的瞬间引擎其实还没就绪，于是 `speak()` 被**静默丢弃**——你第一次点按钮时，引擎还没好，话白说了。

**解决**（见 `TimeSpeaker.kt`）：
- 用 `initialized` 布尔标志表示"真正就绪"（在 `onInit` 返回 SUCCESS 时才置真）；
- 未就绪时，把要播的文字存进 `pendingText`，等 `onInit` 成功后**自动补播**；
- 对外暴露 `speakCurrentTime(force)`：测试按钮传 `force=true` 跳过防抖窗口立即播。

```kotlin
// 简化示意
fun speakCurrentTime(force: Boolean = false): Boolean {
    val text = currentTimeText()
    if (!initialized) {
        pendingText = text      // 引擎没好，先挂起
        init()                  // 触发初始化，onInit 后会补播
        return true
    }
    speakInternal(text)
    return true
}
```

> **学到的第 3 课**：安卓里大量 API 是异步的（TTS、网络、数据库、权限请求）。判断"能不能用"要靠**状态标志/回调**，不能靠"对象存不存在"。这是从写同步脚本切换到写安卓最易踩的思维坑。

#### 坑 2：换思路后 TTS 初始化直接失败（status=-1）

**现象**：修复竞态后，在某些机器/系统上 TTS 初始化返回 `status = -1`，`onInit` 永远不 SUCCESS，彻底没声音。logcat 里能看到 `AppsFilter ... BLOCKED`。

**根因机制**：Android 11（API 30）引入了**包可见性（Package Visibility）过滤**。一个 App 默认"看不见"其他大多数 App，包括系统里那个提供 TTS 的语音引擎服务。你的 App 去绑定 TTS 服务时，被系统的"应用过滤器"拦了，于是绑定失败。

**解决**（见 `AndroidManifest.xml` 的 `<queries>`）：
```xml
<queries>
    <intent>
        <action android:name="android.intent.action.TTS_SERVICE" />
    </intent>
</queries>
```
声明"我需要发现能提供 TTS 服务的应用"，系统就放行绑定。

> **学到的第 4 课**：Android 11+ 的包可见性是"隐式依赖会失败"的典型。凡是 App 要调用另一个 App 的服务（TTS、浏览器、相机、分享面板），都要么靠 `<queries>` 声明，要么靠显式 Intent。新手常在这里卡很久，因为报错信息很隐晦。

### 5.3 坑 3 & 4：HyperOS 把无障碍服务"系统级过滤"掉

**现象**：即便无障碍服务代码写对、在 Manifest 里声明了、甚至把开关写进系统设置，**服务也不会生效**，而且：
- 写入 `Settings.Secure.enabled_accessibility_services` 后约 70ms 内被**自动剥离**回原值（logcat 可见小米安全中心的 `VirusScanJobService` 在实时监听）；
- 服务**不出现在** 设置 → 无障碍 → 已下载的应用 列表里，用户手动也开不了；
- 试图用深链 `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` 跳到开关页，`adb shell` 直接报 `SecurityException: requires OPEN_ACCESSIBILITY_DETAILS_SETTINGS`（**签名级权限**，普通应用连跳都不让跳）。

**我们逐一排除了这些"可能的原因"（全部实测无效）**：
- 安装途径（adb / 系统包安装程序重装）；
- `installer` 字符串（`com.xiaomi.market` / `com.android.vending` / `com.miui.packageinstaller`）；
- 无障碍 flag 有无、debug/release 签名、targetSdk、应用是否启动过。

**对照组**：用户手机里另一个 App（小狗 Git，release 签名、4 个月前经官方商店安装）手动写入后**正常存活**。推测剩下的判定依据是"MIUI 云信任库 / 安装年龄"——**个人侧载应用无法突破**。

**结论（决定路线转向）**：在 HyperOS 上，对个人开发应用，无障碍方案**架构上不可用**。

> **学到的第 5 课（最重要的一条）**：当系统不让你做某件事，**不要硬刚，换架构**。我们没再纠结"怎么把无障碍服务骗开"，而是彻底放弃它，改用不依赖无障碍的"音量监听"方案。这是整个项目最重要的决策。

### 5.4 v2.0：改用前台服务 + 音量监听

**新思路**：既然不能"拦按键"，那就"**听音量变化**"——用户按音量键，系统音量会变，App 监听这个变化来感知按键，然后报时、再把音量恢复回去。

为此需要一个**常驻后台**的组件来持续监听 → 用**前台服务**（见 2.2）。监听"音量变化"用了三道防线：

1. **`VOLUME_CHANGED_ACTION` 系统广播**（主路径）：系统每次改音量都会发这个隐藏广播，extra 里带 stream 类型、新值、旧值。
2. **`Settings.System` 音量值的 `ContentObserver`**（兜底）：监听设置数据库里音量字段的变化。
3. **轮询兜底**：用一个 `Handler` 定时器，每 800ms（息屏）/3000ms（亮屏）读一次各路音量，自己比对变化。

> 为什么需要轮询？因为担心某些 ROM 会拦截上面的广播。轮询的原理很巧妙：`Handler.postDelayed` 的定时器**不会阻止 CPU 休眠**——息屏时它其实不跑；等到用户按音量键唤醒 CPU，过期的定时器立刻执行，毫秒级就能检测到变化，而息屏期间零耗电。

**v2.0 还顺带解决了真机调试的两大障碍（坑 5、坑 6）**，但随后撞上两个更隐蔽的坑（坑 7、坑 8），最终发现这条路在本机也走不通。

### 5.5 坑 5~8：看不见日志、注入不了按键、设置值过期、音量值根本不变

#### 坑 5：HyperOS 屏蔽了本应用的 logcat → 完全没法观测

**现象**：`adb logcat` 里**一条本 App 的日志都没有**，等于"盲人调程序"。

**解决**：不依赖系统的 logcat，自己写日志文件。新增 `DebugLog.kt`，把关键事件写进 App 私有目录 `files/debug.log`。读取时用：
```bash
adb shell run-as com.example.keytime cat files/debug.log
```
`run-as <包名>` 能以"应用自身身份"读它自己的私有文件，从而绕开 logcat 屏蔽。

> **学到的第 6 课**：真机调试的第一原则是**先保证"看得见"**。当标准通道（logcat）被厂商掐了，就自己造一个（写文件 + run-as 读）。可观测性先于一切优化。

#### 坑 6：adb 模拟按键注入无效 → 没法自动复现

**现象**：想用电脑 `adb shell input keyevent VOLUME_DOWN` 来模拟用户按键做测试，但**完全没反应**。进一步验证发现，注入的音量键**根本不改变任何音量值**（用 `cmd media_session volume --stream N --get` 读真实值，毫无变化）。

**机制**：小米的"USB 调试（安全设置）"禁止了输入注入，且 `input` 注入的按键在更底层就被丢弃。

**解决**：既然外部注入不了，就让 App **自己改自己的音量**来触发整条链路。新增 `DebugReceiver`，收 `op=vol` 广播时由 App 调用 `AudioManager.setStreamVolume` 真实改一次音量，从而验证"音量变化→检测→报时→恢复"全链路通不通。同理 `op=fake` 直接模拟一次按键事件。

> **学到的第 7 课**：测试要"从被测试对象内部发起"，而不是依赖外部注入。当平台禁止你模拟输入，就把"触发动作"做成应用内可调用的入口（广播/按钮），自己测自己。

#### 坑 7：`Settings.System` 的音量值是"过期"的

**现象**：想读当前铃声音量，`settings get system volume_ring` 读到 4，但 `cmd media_session volume --stream 2 --get` 读到 6——两个值对不上。

**机制**：Android 把音量存储从老的 `Settings.System` 迁移到了 `AudioService` 内部，`Settings.System` 里的值可能是陈旧缓存。所以 v2.0 那个 `ContentObserver` 兜底其实是**盯着过期数据**，不可靠。

> **学到的第 8 课**：别迷信"经典 API"。安卓版本演进中很多老接口被废弃或变成镜像，读真实状态要用当前权威来源（这里是 `AudioManager` / `media_session` 命令）。

#### 坑 8（致命）：物理音量键在 HyperOS 上**不改变任何音量流的真实数值**

这是压垮 v2.0 的最后一击。

**现象**：我们用 800ms 轮询扫描全部 8 条音量流（RING/MUSIC/ALARM/NOTIFICATION/SYSTEM/VOICE_CALL/DTMF/ACCESSIBILITY），在**真实物理按下音量键**的瞬间，**8 条流的值全程纹丝不动**。

**结论**：HyperOS（至少在息屏 + 本机状态下）对第三方前台服务的"音量变化"监听**彻底失效**——不是广播被拦，而是**按键本身没有导致任何可观测的音量数值变化**。于是 v2.0 整套"听音量变化"的方案，在没有真实音量变化的前提下，**永远收不到触发事件**。

> **学到的第 9 课**：一个假设必须用**测量**来证实或证伪。我们没拍脑袋说"音量监听应该可行"，而是写了轮询去实测，拿到了"8 流全不变"这个铁证，才果断放弃。没有这条证据，很容易在错误的方向上空耗。

### 5.6 v3.0：MediaSession 远程音量接管（最终可用）

**新思路（关键的灵光一现）**：前面所有的失败，本质都是"依赖音量*数值*变化"。但如果**不关心音量变成多少，只关心"用户按了键"这个事件本身**呢？

安卓有个机制：**MediaSession（媒体会话）**。当存在一个"声明自己使用远程音量"且处于活跃状态的 MediaSession 时，系统会把音量键**直接派发给这个会话的 `VolumeProvider.onAdjustVolume(direction)` 回调**，而**不再去调整系统真实音量**。我们在这个回调里直接报时即可——拿到的就是"按键事件 + 方向"，与音量数值无关。

**这就是绕开坑 8 的钥匙**：v2.0 死在"音量值不变"，而 v3.0 根本不看音量值，只看"按键事件"。

代码要点（见 `VolumeKeyInterceptor.kt`）：
```kotlin
val s = MediaSession(context, "TimeTalkVolume").apply {
    setFlags(FLAG_HANDLES_MEDIA_BUTTONS or FLAG_HANDLES_TRANSPORT_CONTROLS)
    setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
        override fun onAdjustVolume(direction: Int) {
            onPress(direction)   // ← 这里直接报时，不看音量值
        }
    })
    setActive(true)              // 让会话接管音量键
}
```

**实测结果（用户确认）**："**音量条变小，且准确报时**"。

**解释"音量条变小但声音大小不变"**：因为会话声明了远程音量，系统把按键当成"调这个会话的音量"，于是**显示音量面板并随按变小**；但 App 并不真正调节任何系统声道，所以**你的真实铃声音量恒定不变**。音量条变小只是"按键被会话接管"的视觉演示，**预期且无害**。

> **学到的第 10 课**：当 A 路径因平台特性走不通，去找"获取同一目标的不同机制"。我们不要"音量变化"，要的是"按键事件"——MediaSession 恰好提供了后者，且不受音量值不变的影响。换一个抽象层级，问题就消失了。

---

## 6. 关键代码走读

下面挑几个最能代表"安卓开发套路"的文件，逐段讲。建议配合 `app/src/main/java/...` 源码一起看。

### 6.1 `TimeSpeaker.kt` —— TTS 封装与异步竞态解决（重点）

职责：把"当前时间"转成语音播报。

要点：
- **异步就绪**：`initialized` 标志 + `pendingText` 挂起补播（对应坑 1）。
- **声道选择**：`useAlarmStream` 决定走 `USAGE_ALARM`（闹钟声道，夜间更不易被勿扰静音）还是 `USAGE_MEDIA`。通过 `AudioAttributes` 设置。
- **WakeLock**：息屏播报时，先 `acquire` 一个 `PARTIAL_WAKE_LOCK`（短时 20s 超时），防止 CPU 休眠把语音截断；`onDone` 时释放。这是"后台发声"的常见保活手段。
- **去重窗口**：`DEDUP_WINDOW_MS = 1500ms`，避免一次误触/多路触发重复播报。
- **报时文本**：`"%d点%02d分".format(Locale.US, hour, minute)`——用 `Locale.US` 保证 `%02d` 永远输出 ASCII 数字（避免某些 locale 把数字变成全角/印度数字）。

### 6.2 `VolumeKeyInterceptor.kt` —— v3.0 主机制

职责：建立并激活 MediaSession，把音量键事件转成报时回调。

要点（已在 5.6 给出代码骨架）：
- `setPlaybackToRemote(VolumeProvider(...))` 声明远程音量；
- 重写 `onAdjustVolume(direction)` 调用外部传入的 `onPress` 回调（即报时）；
- `setActive(true)` 让系统把音量键派发过来；
- `deactivate()` 里 `setActive(false)` + `release()`，让音量键恢复正常调音量的行为（App 关闭时务必释放，否则系统音量键会一直被"吞"）。

> **易错点**：`MediaSession` 的包名是 `android.media.session.MediaSession`（注意有 `.session`），不是 `android.media.MediaSession`；`isActive` 只有 getter，要用 `setActive(true)` 激活。这些导入/API 细节是导致初版编译报错的地方。

### 6.3 `TimeAnnouncerService.kt` —— 前台服务编排

职责：App 的"常驻大脑"。

生命周期：
- `onCreate()`：初始化配置、TTS、建通知渠道、创建 `VolumeKeyInterceptor`。
- `onStartCommand()`：`startForegroundCompat()` 把自己提为前台服务（必须立即调，否则系统杀服务）；`registerListeners()` 注册 v2.0 的兜底监听（广播/观察者/轮询）；`interceptor.activate()` 激活 v3.0 主机制；返回 `START_STICKY`（被杀后系统会尝试重启）。
- `onDestroy()`：释放拦截器、注销监听、取消通知、释放 TTS。

触发逻辑（v3.0 主路径 `handleVolumeKey`）：
1. 节流：`PRESS_THROTTLE_MS = 1200ms`，防止一次连按播多次；
2. 暂停判断：`prefs.paused` 时忽略；
3. 息屏判断：`PowerManager.isInteractive` 为真（亮屏）且非调试模式时忽略——亮屏用户在正常调音量，不打扰；
4. 调 `speaker.speakCurrentTime()` 报时，并把状态写进通知。

> v2.0 的 `onVolumeChanged` 路径仍保留作兜底，但在本机因"音量值不变"而处于静默，不会与 v3.0 冲突或重复播报。

### 6.4 `MainActivity.kt` —— 设置页（Activity）

职责：UI 与用户交互入口。

要点：
- **视图绑定（ViewBinding）**：`ActivityMainBinding` 由 Gradle 自动生成，直接用 `binding.btnEnableService` 引用界面元素，免去找 ID（`findViewById`）。
- **启动/停止服务**：`ContextCompat.startForegroundService(...)` 启动；`stopService(...)` 停止。
- **权限请求**：Android 13+ 用 `ActivityResultContracts.RequestPermission()` 动态申请 `POST_NOTIFICATIONS`，拒绝也能用（只是没状态通知）。
- **测试按钮**：`ensureSpeaker().speakCurrentTime(force = true)` 立即试播。
- **运行状态检测**：`isServiceRunning()` 先读静态标志 `TimeAnnouncerService.isRunning`，再 `ActivityManager.getRunningServices` 兜底（START_STICKY 重启后标志可能滞后）。

### 6.5 `Notifier.kt` / `NotificationActionReceiver.kt` —— 通知

- `ensureChannel()`：Android 8+ 必须先建 `NotificationChannel`（重要性 `IMPORTANCE_LOW`，不打扰用户）。
- `buildNotification()`：构造常驻通知，`setOngoing(true)` 表示不可滑动清除；带一个"暂停/恢复"动作按钮，点击通过 `PendingIntent` 发广播给 `NotificationActionReceiver`，后者翻转 `prefs.paused` 并刷新通知。
- `refresh(detail)`：把运行状态/最近一次报时写进通知文本——因为 logcat 被屏蔽（坑 5），通知文本成了**第二条可读观测通道**（用 `adb shell dumpsys notification` 看）。

### 6.6 `Prefs.kt` —— 配置

用 `SharedPreferences`（App 私有、轻量键值存储，不联网）保存：报时按键（下/上/双）、声道、暂停、调试开关等。每项都是 `get/set` 属性，存盘用 `.apply()`（异步落盘）。

### 6.7 `DebugLog.kt` / `DebugReceiver.kt` —— 可观测性设计

- `DebugLog.w(context, tag, msg)`：带时间戳追加写 `files/debug.log`，`run-as` 可读。
- `DebugReceiver`：导出（exported）的广播接收器，接收 `op` 参数做受控调试操作：
  `start/stop/status/speak/clear/vol/fake/always/notify`。这是"从内部驱动测试"（坑 6）的具体实现。

### 6.8 `AndroidManifest.xml` —— 清单

关键声明：
- `<queries>` 里声明 TTS 服务（坑 2）；
- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`WAKE_LOCK`；**无 INTERNET、无无障碍**；
- `<service>` 带 `foregroundServiceType="specialUse"` 及 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明；
- `DebugReceiver` 标注 `android:exported="true"`（允许 `am broadcast` 调试）。

---

## 7. 构建 / 签名 / 安装 / 调试流水线

完整一条龙（在项目根目录执行）：

```bash
# 1) 构建
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease

# 2) 安装（-r = reinstall；签名一致时才可覆盖）
adb install -r app/build/outputs/apk/release/app-release.apk

# 3) 设备上确认版本
adb shell dumpsys package com.example.keytime | grep versionName
```

**调试技巧（本项目的三条命门）**：
```bash
# 读内部日志（绕开 logcat 屏蔽）
adb shell run-as com.example.keytime cat files/debug.log

# 看通知文本（第二条观测通道）
adb shell dumpsys notification | grep -A3 "按键报时"

# 发调试广播（例如看当前状态）
adb shell am broadcast -n com.example.keytime/.DebugReceiver -e op status
```

**签名细节**：`app/build.gradle.kts` 中 `signingConfigs.release` 的密钥信息全部来自环境变量
（`KEYTIME_KEYSTORE` / `KEYTIME_STORE_PASSWORD` / `KEYTIME_KEY_ALIAS` / `KEYTIME_KEY_PASSWORD`），
源码中不含任何 keystore 路径或密码。配置后 debug 变体也复用该签名，便于 `adb install -r` 覆盖安装；
未配置时两个变体使用默认调试签名。若更换签名或包名，旧包必须先卸载。

---

## 8. 方法论总结：从这个案例能学到的"心法"

把上面 10 课串成一套可迁移的方法论：

1. **系统限制优先于代码技巧**：先搞清楚平台允许什么（尤其国产 ROM），再决定怎么做。本项目最大的转折就是"发现无障碍被系统级过滤，立刻换路线"。
2. **可观测性先行**：调真机前，先保证"看得见"（自建日志 + run-as、通知文本、调试广播）。看不见就别谈调。
3. **假设必须被测量验证**：轮询 8 路音量证明"按键不改变音量值"，才让放弃 v2.0 有底气。
4. **异步是安卓的第一性**：TTS、权限、网络、数据库全是异步的；用状态标志/回调判断"就绪"，别用"对象存不存在"。
5. **换抽象层级绕开平台坑**：不要"音量变化"要"按键事件"——MediaSession 提供了后者，平台差异就消失了。
6. **从内部驱动测试**：外部注入被禁时，把触发动作做成应用内入口（广播/按钮），自己测自己。
7. **最小权限原则**：不联网、不开无障碍、只申请必要前台/通知权限，既保护隐私也降低被系统杀死的概率。
8. **文档即交付**：工具链路径、签名、构建命令、每个坑的结论，全部写进文档，才能复现和交接。

---

## 9. 知识延伸（本案例没覆盖、但你应该知道的）

本 App 只触及安卓的冰山一角。想继续深入，这些方向值得学：

- **界面现代化**：本项目用传统 View + XML 布局。现在官方主推 **Jetpack Compose**（声明式 UI，Kotlin 直接写界面），更简洁。
- **持久化**：`SharedPreferences` 只适合简单配置；结构化数据应学 **Room**（SQLite 封装）。
- **架构**：本项目逻辑集中在 Service/Activity，适合小工具。大型 App 应学 **MVVM + ViewModel + 协程（Coroutines）/ Flow**（响应式、生命周期安全）。
- **网络通信**：本项目刻意不联网。要联网得学 **Retrofit/OkHttp**、权限与明文流量限制、后台限制。
- **后台与电量**：前台服务、WorkManager、Doze 模式、厂商省电白名单——本案例只碰了皮毛。
- **测试**：单元测试（JUnit）、UI 测试（Espresso）、以及本项目用到的"在真机上用 run-as + 广播"的手动验证法。
- **发布**：签名方案、Google Play / 国内应用商店上架、版本号（versionCode/versionName）策略、增量更新。

---

> 至此，你已顺着"按键报时"这一个真实案例，走完了**需求 → 架构决策 → 环境搭建 → 多轮试错 → 真机调试 → 最终交付**的完整闭环。
> 把它和 `HANDOVER.md`（交接速查）一起看，你就既"学会了方法"，也"接住了这个项目"。
