# Scroff — 屏幕定时开关控制器

跨平台屏幕开关控屏系统，包含 **三端**：

| 端 | 形态 | 适用场景 |
|----|------|----------|
| `scroff-server` | Spring Boot 服务 + Web 后台 | **集中控屏**：多台叫号机/信息屏统一开关、定时、API 调用 |
| `android/` | Android App（Kotlin/Compose） | 单台 Android 设备本地定时开关屏 |
| `windows/` | WPF 应用（.NET 8 / .NET Framework 4.8） | 单台 Windows 设备本地定时开关屏 |

> Scroff = **Scr**een **Of**f —— 通过 ADB / Win32 API / Android 平台 API 控制屏幕电源。

---

## 目录

- [项目亮点](#项目亮点)
- [平台与技术栈](#平台与技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
  - [1. 集中控屏 Server（推荐）](#1-集中控屏-server推荐)
  - [2. Android 本地版](#2-android-本地版)
  - [3. Windows 本地版](#3-windows-本地版)
- [scroff-server 详解](#scroff-server-详解)
  - [架构](#架构)
  - [核心特性](#核心特性)
  - [配置管理](#配置管理)
  - [REST API](#rest-api)
  - [部署到 Ubuntu](#部署到-ubuntu)
- [Android 详解](#android-详解)
- [Windows 详解](#windows-详解)
- [屏幕控制原理](#屏幕控制原理)
- [开发约定与踩坑记录](#开发约定与踩坑记录)
- [许可证](#许可证)

---

## 项目亮点

- **三端覆盖**：Android、Windows 都有"本机定时"版，Windows/安卓叫号机还有"集中控屏"版，按规模自由选择。
- **集中控屏 Server**：通过 **ADB over TCP/IP** 同时管理成百上千台叫号机，一台服务器、一个 Web 后台搞定。
- **运行时配置切换**：ADB 可执行文件路径不再写死 —— Web `/settings` 页面可即时切换 Linux `adb` / Windows `adb.exe` / WSL 包装器等多个 profile，重启不丢。
- **REST API 对接**：外部系统（手机 App、第三方调度）通过 HTTP 直接触发开关屏、查询日志。
- **离线友好构建**：Gradle 缓存、阿里云/腾讯云镜像、PowerShell 构建脚本全部就绪，国内网络直连顺畅。
- **踩坑沉淀**：实体时间戳列 `ddl-auto: update` 缺 DEFAULT、Spring self-injection、flex 滚动条、cmd `if ()` 复合体 bug 等都已在源码中修过并加测试覆盖。

---

## 平台与技术栈

### scroff-server（集中控屏服务）

| 项 | 值 |
|----|----|
| 框架 | Spring Boot 3.3.5 |
| 语言 | Java 21 |
| 视图 | Thymeleaf（服务端渲染） |
| 持久化 | Spring Data JPA + MariaDB（`ddl-auto: update`） |
| 调度 | Spring `@Scheduled` + `TaskScheduler`（Cron 表达式） |
| 控屏通道 | ADB over TCP/IP（子进程短连接，专用线程池） |
| 构建 | Gradle 8.x Kotlin DSL（阿里云 + 腾讯云镜像） |
| 部署 | systemd unit + 一键 bash 脚本 |

### Android（本地定时版）

| 项 | 值 |
|----|----|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 持久化 | Room + DataStore Preferences |
| 调度 | AlarmManager + WorkManager + `SCHEDULE_EXACT_ALARM` |
| 控屏通道 | `AccessibilityService` 电源键 / 内核 `dispdbg` |
| 最低支持 | Android 8.0（API 26）/ targetSdk 35 |
| 构建 | AGP 8.x + Gradle Kotlin DSL（KSP 编译 Room） |

### Windows（本地定时版）

| 项 | 值 |
|----|----|
| 主项目 | .NET 8 + WPF（Win 10/11 64-bit） |
| 兼容项目 | .NET Framework 4.8 + WPF（Win 7/8/10 32-bit） |
| MVVM | `CommunityToolkit.Mvvm` 8.4 |
| 持久化 | JSON 文件（`%APPDATA%\Scroff\schedules.json`） |
| 调度 | `System.Timers.Timer` |
| 控屏通道 | Win32 `SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER)` + 模拟输入唤醒 |

---

## 项目结构

```
scroff/
├── README.md                       # 本文件
├── LICENSE                         # MIT
│
├── android/                        # Android 端 (Kotlin + Compose)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── java/com/scroff/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── ScroffApp.kt
│   │   │   │   ├── data/          # Room + Repository
│   │   │   │   ├── service/       # 控屏 + 调度
│   │   │   │   ├── receiver/      # BOOT_COMPLETED + 定时广播
│   │   │   │   ├── ui/            # Compose 屏幕 / 组件
│   │   │   │   └── debug/         # 调试日志
│   │   │   └── res/
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── windows/                        # Windows 端 (C# WPF)
│   ├── Scroff/                     # .NET 8 主项目（Win 10/11）
│   │   ├── Scroff.csproj
│   │   ├── App.xaml(.cs)
│   │   ├── Views/MainWindow.xaml(.cs)
│   │   ├── ViewModels/MainViewModel.cs
│   │   ├── Services/
│   │   │   ├── ScreenControlService.cs   # Win32 屏幕控制
│   │   │   ├── SchedulerService.cs       # 调度
│   │   │   ├── StorageService.cs         # JSON 持久化
│   │   │   └── AutoStartService.cs       # 开机自启
│   │   ├── Controls/                     # 自定义控件
│   │   └── Converters/                   # XAML Value Converter
│   │
│   └── win7/                       # .NET Framework 4.8 兼容项目（Win 7）
│       ├── Scroff.Win7.csproj
│       └── ...（同主项目结构）
│
├── scroff-server/                  # 集中控屏服务（Spring Boot）
│   ├── build.gradle.kts            # Gradle Kotlin DSL
│   ├── settings.gradle.kts         # 阿里云/腾讯云镜像
│   ├── gradle.properties
│   ├── gradlew(.bat)
│   ├── gradle/wrapper/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/scroff/server/
│   │   │   │   ├── ScroffServerApplication.java
│   │   │   │   ├── config/ScroffProperties.java
│   │   │   │   ├── controller/    # Dashboard / Device / Schedule / Log / Settings / ScreenApi / Help
│   │   │   │   ├── entity/        # Device / Schedule / ScreenLog / Settings
│   │   │   │   ├── repository/    # JPA Repository
│   │   │   │   ├── service/       # AdbService / DeviceManager / ScreenPowerService / ConfigService
│   │   │   │   └── scheduler/ScheduleExecutor.java
│   │   │   └── resources/
│   │   │       ├── application.yml                       # 公共配置（提交到 Git）
│   │   │       ├── application-local.yml.example        # 本地 + 部署模板（提交）
│   │   │       ├── application-local.yml                # 本地真实配置（gitignore）
│   │   │       ├── schema.sql                           # 完整 schema 模板
│   │   │       ├── static/css/scroff.css
│   │   │       └── templates/                            # Thymeleaf 页面
│   │   │           ├── _layout.html / dashboard.html / devices.html / device-form.html
│   │   │           ├── schedules.html / schedule-form.html
│   │   │           ├── logs.html / settings.html / help.html
│   │   └── test/java/com/scroff/server/controller/ScheduleControllerTest.java
│   ├── scripts/
│   │   ├── run-local.sh            # Linux/macOS 本地启动
│   │   └── run-local.bat           # Windows 本地启动
│   └── deploy/
│       ├── deploy-scroff.sh        # Ubuntu 一键部署
│       ├── scroff-server.service   # systemd unit
│       ├── migrate-schedule-target-all.sql  # 旧库升级用
│       └── README-ubuntu.md        # 部署文档
│
├── build-android.ps1               # Android 一键打包
├── build-server.ps1                # Server 一键打包（Gradle）
├── build-win10.ps1                 # Win10/11 一键发布（dotnet）
├── build-win7.ps1                  # Win7 一键构建（MSBuild）
│
├── NuGet.config                    # 优先用本地 NuGet 缓存
│
├── debug-scroff-screen-wakeup-fail.md     # 历史 debug 记录
└── debug-scroff-storage-access-denied.md
```

---

## 快速开始

> 所有构建脚本都在 **项目根目录** 执行，无需 `cd` 到子目录。

### 1. 集中控屏 Server（推荐）

最常见的用法：用 `scroff-server` 集中管理多台叫号机。

```powershell
# 1) 编译：默认 bootJar（跳过 test 节省时间）
.\build-server.ps1

# 产物：dist/scroff-server.jar + application.yml + deploy-ubuntu.sh + scroff-server.service
```

**本地启动**（连本机 MariaDB）：

```powershell
cd scroff-server
copy src\main\resources\application-local.yml.example src\main\resources\application-local.yml
notepad src\main\resources\application-local.yml       # 改 DB 密码 + ADB 路径
.\scripts\run-local.bat
```

启动后访问 `http://localhost:8880/`，首次进入根据 `/help` 页面 5 分钟接入第一台叫号机。

**部署到 Ubuntu**（生产环境）：

```bash
# 1) 拷产物到服务器
scp dist/* user@server:/tmp/

# 2) 一键部署（装 JDK 21 + ADB、构建、写 systemd、启动）
ssh user@server
sudo bash /tmp/deploy-scroff.sh
```

详细步骤见 [部署到 Ubuntu](#部署到-ubuntu)。

### 2. Android 本地版

```powershell
# 1) 构建 debug APK
.\build-android.ps1

# 产物：android\dist\app-debug.apk

# 2) 设备开启 USB 调试后
adb install -r android\dist\app-debug.apk
```

App 启动后给无障碍服务授权，即可添加本地定时任务。

### 3. Windows 本地版

```powershell
# Win 10/11 64 位（.NET 8 Desktop Runtime 需先安装）
.\build-win10.ps1
# 产物：windows\publish\

# Win 7/8/10 32 位（依赖系统自带 .NET Framework 4.8）
.\build-win7.ps1
# 产物：windows\win7\bin\Release\
```

把对应目录整个拷到目标机器，双击 `Scroff.exe` 即可。

---

## scroff-server 详解

### 架构

```
                ┌────────────────────────┐
   Web UI ────► │  Spring Boot 服务       │ ── adb -s host:port shell ...
   (8880)       │  · Thymeleaf 渲染        │
                │  · @Scheduled 触发       │
                │  · MariaDB 存任务         │
                │  · system_config 热配置  │
                └────────────────────────┘
                       │
                       │  ADB over TCP/IP
                       ▼
            ┌──────────────────────┐
            │ 叫号机 1  192.168.x.x │
            │ 叫号机 2  192.168.x.x │
            │ ...                   │
            └──────────────────────┘
```

### 核心特性

| 功能 | 说明 |
|------|------|
| 设备管理 | 增删改查；自动 `adb connect`；心跳检测（连续 3 次失败才转 OFFLINE） |
| 定时任务 | 6 段 Cron 表达式（秒 分 时 日 月 周）；`target_all` 支持"所有设备"模式 |
| 手动控制 | Web 表格行内一键开/关；批量"全部开"/"全部关" |
| 触发方式记录 | `SCHEDULE` / `MANUAL` / `API` 三种来源完整审计 |
| 日志查询 | `/logs` 页面 + 设备过滤 + 分页；`screen_log` 表自动落库 |
| 运行时配置 | `system_config` 表存 ADB profile 切换；Web `/settings` 即时生效、重启不丢 |
| REST API | `POST /api/devices/{id}/screen/{on\|off}` + `GET /api/devices/{id}/logs` |
| 帮助文档 | `/help` 页面内嵌 5 分钟上手教程 |

### 实体模型

| 表 | 说明 |
|----|------|
| `device` | 叫号机：name / host / adb_port / status / last_seen_at / last_error / location / category / notes / sort_order / enabled |
| `schedule` | 定时任务：device_id / name / action(ON/OFF) / cron / enabled / **target_all**(0=单台，1=所有设备) / last_run_* |
| `screen_log` | 执行历史：device_id / device_name / action / trigger_type / success / message / executed_at |
| `system_config` | 运行时键值配置：cfg_key / cfg_value / updated_at |

> 时间戳字段（`created_at` / `updated_at`）一律走 **`@PrePersist` / `@PreUpdate` 回调**，不依赖 DB `DEFAULT CURRENT_TIMESTAMP` —— 这样 `ddl-auto: update` 给旧表加列时不会因缺 DEFAULT 而 INSERT 失败。
>
> `system_config` 表的 `key` / `value` 是 MySQL 保留字，**故意**用 `cfg_key` / `cfg_value` 避开，避免 Hibernate 生成 DDL 时静默失败。

### 配置管理

**三层配置**（敏感信息逐层隔离）：

| 位置 | 提交到 Git？ | 内容 |
|------|------|------|
| `src/main/resources/application.yml` | ✅ | 端口、JPA、Thymeleaf、ADB profiles 占位符、默认 `active-profile-id` |
| `src/main/resources/application-local.yml.example` | ✅ | 本地开发 + 部署模板（占位符 `YOUR_LOCAL_DB_PASSWORD`） |
| `src/main/resources/application-local.yml` | ❌ | 本地真实配置（`scroff-server/.gitignore` 已忽略） |
| `/opt/scroff-server/application-local.yml` | ❌ | 部署时生成，**`chmod 600`** |

**ADB profile 解析优先级**（`AdbService.resolveActiveProfile()`）：

1. **`system_config` 表** `scroff.adb.active-profile-id`（Web UI 切换后写这里）
2. `application.yml` 里 `scroff.adb.active-profile-id`
3. `profiles` 中第一个 `enabled: true` 的
4. 兜底：`profiles` 中第一个（可能 disabled）

**典型 ADB profile 配置**（写在 `application.yml`）：

```yaml
scroff:
  adb:
    profiles:
      - id: linux-default
        name: "Linux 默认 /usr/bin/adb"
        executable: /usr/bin/adb
        enabled: true
      - id: wsl-ubuntu
        name: "WSL Ubuntu adb（通过 wsl.exe 包装）"
        executable: C:/Windows/System32/wsl.exe
        args: ["adb"]            # 包装器场景：args 插在 executable 和 ADB 子命令之间
        enabled: false
      - id: android-sdk
        name: "Android SDK (Windows)"
        executable: ${ADB_EXECUTABLE_ANDROID_SDK:C:/Users/your-name/AppData/Local/Android/Sdk/platform-tools/adb.exe}
        enabled: false
    active-profile-id: linux-default
    auto-connect-on-startup: true
    heartbeat-interval: 30      # 秒
    command-timeout: 10000      # 毫秒
    screen-off-command: "cd /sys/kernel/debug/dispdbg && echo disp0 > name && echo blank > command && echo {param} > param && echo 1 > start"
```

### REST API

```bash
# 立即开屏
curl -X POST http://server:8880/api/devices/1/screen/on

# 立即关屏
curl -X POST http://server:8880/api/devices/1/screen/off

# 查最近 20 条执行日志
curl "http://server:8880/api/devices/1/logs?size=20"
```

返回示例：
```json
{
  "ok": true,
  "message": "开屏成功",
  "deviceId": 1,
  "action": "ON"
}
```

| 状态码 | 含义 |
|--------|------|
| 200 | 成功 |
| 500 | 设备不存在 / 不在线 / ADB 命令失败（`ok: false` + 失败原因在 `message`） |

### 部署到 Ubuntu

详细文档见 [`scroff-server/deploy/README-ubuntu.md`](file:///d:/OpenCode/scroff/scroff-server/deploy/README-ubuntu.md)。这里给出最简流程。

**1) 准备产物**（开发机）：

```powershell
.\build-server.ps1
# 产物在 dist\：
#   scroff-server.jar
#   application.yml
#   deploy-ubuntu.sh
#   scroff-server.service
```

**2) 上传**：

```bash
scp dist/* user@server:/tmp/
```

**3) 一键部署**（服务器）：

```bash
sudo bash /tmp/deploy-scroff.sh
```

脚本会做：
1. 装 `openjdk-21-jdk-headless` + `android-tools-adb`（仅首次）
2. 克隆/拉取 Git 代码到 `/opt/scroff-build/`
3. Gradle 构建（`./gradlew clean bootJar -x test --no-daemon`）
4. 部署 jar + `application.yml` 到 `/opt/scroff-server/`
5. 首次部署时从 `.example` 复制 `application-local.yml`（**保留**你的真实密码/地址）
6. 注册 systemd unit `scroff-server.service`
7. 启动 + `systemctl status` 验证

**4) 编辑敏感配置**（首次部署必须）：

```bash
sudo nano /opt/scroff-server/application-local.yml
# 填入远程 MariaDB 地址、账号、密码、ADB 路径
sudo systemctl restart scroff-server
```

**5) 升级**：

```bash
sudo systemctl stop scroff-server
sudo cp /opt/scroff-server/scroff-server.jar /opt/scroff-server/scroff-server.jar.prev
sudo cp dist/scroff-server.jar /opt/scroff-server/
sudo systemctl start scroff-server
# 或直接重跑：sudo bash /tmp/deploy-scroff.sh  （自动备份旧 jar + 不覆盖 application-local.yml）
```

> 旧库（无 `target_all` 列 / 有 `FK fk_schedule_device`）需先跑 [`scroff-server/deploy/migrate-schedule-target-all.sql`](file:///d:/OpenCode/scroff/scroff-server/deploy/migrate-schedule-target-all.sql)：
> ```bash
> mysql -h<host> -u<user> -p scroff < scroff-server/deploy/migrate-schedule-target-all.sql
> ```

---

## Android 详解

### 功能
- 多个本地定时任务，持久化在 Room
- 每日重复 / 一次性 / 工作日 / 周末 等任意 Cron
- 任务启用/禁用
- 调试日志页（`DebugLogScreen`）
- 开机自启动恢复定时（`BootReceiver` + `RECEIVE_BOOT_COMPLETED`）

### 屏幕控制实现

两条路径（在 `service/KernelScreenController.kt` / `service/ScreenControlService.kt` 中按设备能力选择）：

1. **优先：内核 `dispdbg`**（root 设备 / 工业平板）
   ```bash
   adb shell "cd /sys/kernel/debug/dispdbg && echo disp0 > name && echo blank > command && echo {param} > param && echo 1 > start"
   # {param} = 1 关屏 / 0 开屏
   ```
2. **备选：`AccessibilityService` 电源键**（任意设备，需用户授权无障碍）

### 权限

- `WAKE_LOCK` — 唤醒设备
- `RECEIVE_BOOT_COMPLETED` — 开机恢复定时
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` — 精确定时（Android 12+/13+ 需用户授权）
- `POST_NOTIFICATIONS` — 通知通道
- 无障碍服务（`BIND_ACCESSIBILITY_SERVICE`）— 控制屏幕电源键

### 国内网络

`settings.gradle.kts` 已配置阿里云镜像优先（`maven.aliyun.com`），避免 `repo.maven.apache.org` TLS 握手失败。如果之前拉过官方源失败：

```bash
.\gradlew.bat --refresh-dependencies assembleDebug
```

---

## Windows 详解

### 两个项目

| 项目 | 框架 | 目标机器 | 命令 |
|------|------|----------|------|
| `windows/Scroff/` | .NET 8 WPF | Win 10/11 64-bit | `.\build-win10.ps1` |
| `windows/win7/` | .NET Framework 4.8 WPF | Win 7/8/10 32-bit | `.\build-win7.ps1` |

`build-win7.ps1` 通过微软官方的 `vswhere` 自动定位 MSBuild，能跨盘符/版本找到 Visual Studio 2019/2022 或 Build Tools。

### 屏幕控制实现

Win32 API `SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, ...)`：

- `SC_MONITORPOWER + 2` → 关闭显示器
- `SC_MONITORPOWER + -1` → 打开显示器

**已知坑（Win 7 兼容性）**：

- `HWND_BROADCAST` + `SendMessage` 在 Win 7 上**不可靠**，需改用 `PostMessage` 配合 `EnumWindows` 枚举可见顶层窗口（见 [`windows/Scroff/Services/ScreenControlService.cs`](file:///d:/OpenCode/scroff/windows/Scroff/Services/ScreenControlService.cs)）。
- Win 7 上单纯 `MONITOR_ON` 消息**不足以唤醒硬件**（LCD/LED 显示器需要 DDC/CI 信号），需叠加**模拟输入事件**（3 像素鼠标移动 + `SCROLL LOCK` 按键），与 OS 消息间隔 30ms。详见 [`debug-scroff-screen-wakeup-fail.md`](file:///d:/OpenCode/scroff/debug-scroff-screen-wakeup-fail.md)。
- Win 10/11 的 Modern Standby 改变了省电模型，旧 API 在新系统上偶尔不灵；如遇问题优先验证目标机器电源计划。

### 持久化

`StorageService.cs` 用 `FileMode.Create` 直接覆盖 + `FileShare.Read` + 5 次重试 + `lock` 互斥，规避了"残留 `.tmp` 文件 + `File.Delete` 抛 `UnauthorizedAccessException`"的历史 bug。详见 [`debug-scroff-storage-access-denied.md`](file:///d:/OpenCode/scroff/debug-scroff-storage-access-denied.md)。

---

## 屏幕控制原理速查

| 端 | 关屏 | 开屏 | 备选 |
|----|------|------|------|
| `scroff-server` | `dispdbg` 内核接口（Android 设备） | 同上 | `fb0/blank` |
| Android | `AccessibilityService` 电源键 | 同上 | `dispdbg`（需 root） |
| Windows | `SC_MONITORPOWER(2)` | `SC_MONITORPOWER(-1)` + 模拟输入 | 不适用 |

---

## 开发约定与踩坑记录

> 这是从本项目踩过的坑里提炼的"新坑预警"，给后续接手者省时间。完整细节见各 `entity` / `service` 源码注释。

### Gradle
- 用 **Gradle Kotlin DSL**（不是 Maven）；`settings.gradle.kts` 已配阿里云 + 腾讯云镜像
- `gradle daemon / wrapper` 缓存放在项目内 `.gradle/`（设 `$env:GRADLE_USER_HOME`），避免污染全局
- Gradle 发行包走腾讯云镜像：`https://mirrors.cloud.tencent.com/gradle/`
- 从 Maven 迁到 Gradle 后务必删掉 `target/`、`pom.xml`、`mvnw`，否则 `build-server.ps1` 会把它们当旧产物

### PowerShell
- `build-server.ps1` / `build-win10.ps1` 的 `Write-Host` **必须用英文**，含 UTF-8 中文会触发 PowerShell 解析器列号错位 bug（误导性 "string is missing the terminator" 错误）
- `cmd /c "cd /d ... && gradlew.bat ..."` 是 Windows 下从 PowerShell 调 Gradle 的可靠姿势：绕开 PowerShell 调 `.bat` 退出码不准
- **cmd.exe 的 multi-line `if (...)` 复合体内，echo 行禁止包含 `(` 或 `)`** —— 会触发 cmd 解析 bug，整个 if 复合体被破坏，前面所有 echo 会被吞

### scroff-server JPA / Spring
- 时间戳列一律走 `@PrePersist` / `@PreUpdate` 回调，**不要**依赖 DB `DEFAULT CURRENT_TIMESTAMP` —— `ddl-auto: update` 不会给已有列补 DEFAULT
- **避开 MySQL 保留字**做列名（`key`/`value`/`order`/`status`/`name`/`type`/`text`/`data`/`time`/`date` 等），用 `@Column(name="xxx_key")` 显式加前缀
- 给现有 NOT NULL 列加新字段时，**写 `@PostLoad` 兜底** —— `ddl-auto: update` 加列时老数据该列可能为 NULL
- **`@Transactional` + 自调用陷阱**：同类内 `this.xxx()` 绕过代理 → 事务失效。**用 self-injection**（构造注入 `@Lazy XxxService self`），需要事务的方法都改成 `self.xxx()`。**注意**：混用 Lombok `@RequiredArgsConstructor` + `@Lazy` 字段会报循环依赖，必须写**显式构造器**把 `@Lazy` 放在参数上
- `@Modifying` 查询统一加 `clearAutomatically = true`，否则 JPA 一级缓存导致后续 `findById` 拿到旧值
- 写状态的 Service 方法（`updateStatus` / `updateLastSeen` 等）必须 `public`，否则 Spring AOP 代理拦截不到
- **schema.sql 的 FK 约束**会让"所有设备"模式 schedule 保存失败 —— 删 FK，应用层显式级联

### Thymeleaf
- Thymeleaf **表格加新列**要同时改 4 个地方：`<th>`、`<td>`、空数据 `colspan`、（如有）`colgroup`/`col`，改完用 `grep -c '<th'` / `grep -c '<td'` 双重确认
- **radio + Boolean + 静态 `checked` 会冲突**：放弃 `th:field` 的隐式 checked，改用 `name` + `th:checked` 显式判断
- **checkbox 不勾选时 form 不提交该字段**，Boolean 包装类型会绑为 null，null 兜底**应是 FALSE**（用户的"不勾选"明确意图 = 禁用），不是 TRUE
- **`@ModelAttribute` form DTO 字段必须全部在 `applyForm` 中显式赋值**，缺一个就丢一个。加字段的顺序：**先写 `applyForm` 全部 `setXxx`，再写 form HTML，最后加 DTO 字段**。已有测试 [`ScheduleControllerTest.java`](file:///d:/OpenCode/scroff/scroff-server/src/test/java/com/scroff/server/controller/ScheduleControllerTest.java) 覆盖此场景

### CSS
- **flex item + `overflow-y: auto` 陷阱**：flex 容器里 flex item 默认 `min-height: auto`，禁止收缩到内容尺寸以下。**在要滚的那个 div 上加 `min-height: 0`**
- Spring Boot 默认对 `/static/**` 有缓存头，CSS 改完可能看不到 —— **硬刷新**（Ctrl+Shift+R / Ctrl+F5）

### ADB 错误处理
- 所有 controller 写 flash 时，**失败必须把 `last_error` 拼到消息里**，不要只贴 status 字符串
- 列表/详情页的 status badge 必须 `th:title="${d.lastError}"` 鼠标悬停可见
- 启动期强依赖外部命令的路径配置，**UI 必须可改且可重启不丢**（`profiles` + `system_config`）

---

## 许可证

[MIT License](file:///d:/OpenCode/scroff/LICENSE)
