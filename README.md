# Scroff - 屏幕定时开关控制器

屏幕定时开关控制器，支持 Android (Kotlin) 和 Windows (C# WPF) 平台，可设置定时任务自动关闭/打开屏幕。

## 平台支持

| 平台 | 技术栈 | 运行环境 | 目录 |
|------|--------|----------|------|
| **Windows 10/11 (64位)** | C# WPF / .NET 8 | .NET 8 Desktop Runtime | `windows/Scroff/` |
| **Windows 7/8/10 (32位)** | C# WPF / .NET Framework 4.8 | .NET Framework 4.8 (系统自带) | `windows/win7/` |
| **Android** | Kotlin / Jetpack Compose | Android 7.0+ (API 24) | `android/` |

## Windows 构建说明

三个平台都提供 PowerShell 一键构建脚本，**在项目根目录执行即可**，无需 `cd` 到子目录。

### Win 10/11 (64位) - 主项目
```powershell
# 默认 Release | win-x64，依赖目标机器的 .NET 8 Desktop Runtime
.\build-win10.ps1
# 等价于：dotnet publish windows\Scroff\Scroff.csproj -c Release -r win-x64 --self-contained false -o windows\publish
```
发布产物在 `windows\publish\`，**整个目录拷贝**到目标机器即可运行。

### Win 7/8/10 (32位) - 兼容项目
```powershell
# 默认 Release | x86，.NET Framework 4.8 是 Win 7 系统自带
.\build-win7.ps1
# 等价于：msbuild windows\win7\Scroff.Win7.csproj /p:Configuration=Release /p:Platform=x86
```
发布产物在 `windows\win7\bin\Release\`，**整个目录拷贝**到目标机器即可运行（无需安装额外依赖，.NET Framework 4.8 是 Win 7 系统自带）。

> 前置条件：
> - Win 10/11 版本：安装 [**.NET 8 SDK**](https://dotnet.microsoft.com/zh-cn/download/dotnet/8.0)
> - Win 7 版本：安装 Visual Studio 2019/2022 或 Build Tools，并勾选「.NET 桌面开发」工作负载（含 .NET Framework 4.8 SDK/目标包）
> - Android 版本：安装 [**Android Studio Hedgehog+**](https://developer.android.com/studio)，自带 JDK 17 和 Android SDK 35；首次构建会下载 ~200MB 依赖

> 说明：`build-win7.ps1` 通过微软官方的 `vswhere` 自动定位 MSBuild，能跨盘符/版本找到 Visual Studio 2019/2022 或 Build Tools。若你已把 `msbuild` 加入 PATH，也可直接：
> ```powershell
> msbuild windows\win7\Scroff.Win7.csproj /p:Configuration=Release /p:Platform=x86
> ```
> 或使用「Developer Command Prompt for VS 2022」命令行环境（已预置 PATH）。

## 功能

- 定时关闭/打开屏幕
- 支持多个定时任务
- 每日重复执行
- 任务启用/禁用切换
- 数据本地持久化

## 项目结构

```
scroff/
├── android/                    # Android 端 (Kotlin)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/scroff/
│   │   │   │   ├── MainActivity.kt          # 主界面
│   │   │   │   ├── ScroffApp.kt             # Application
│   │   │   │   ├── data/                    # 数据层
│   │   │   │   │   ├── AppDatabase.kt       # Room 数据库
│   │   │   │   │   ├── dao/ScheduleDao.kt   # 数据访问
│   │   │   │   │   └── model/Schedule.kt    # 数据模型
│   │   │   │   ├── service/                 # 服务层
│   │   │   │   │   ├── ScreenControlService.kt  # 屏幕控制
│   │   │   │   │   └── ScheduleScheduler.kt     # 定时调度
│   │   │   │   ├── receiver/                # 广播接收器
│   │   │   │   │   └── ScheduleReceiver.kt
│   │   │   │   └── ui/                      # UI 层 (Jetpack Compose)
│   │   │   │       ├── screen/HomeScreen.kt
│   │   │   │       ├── component/ScheduleCard.kt
│   │   │   │       ├── navigation/ScroffNavHost.kt
│   │   │   │       └── theme/
│   │   │   └── res/                         # 资源文件
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── windows/                    # Windows 端 (C# WPF)
│   ├── Scroff.sln             # Visual Studio 解决方案
│   └── Scroff/
│       ├── Scroff.csproj      # 项目文件 (.NET 8 + WPF)
│       ├── App.xaml           # 应用入口
│       ├── Views/
│       │   └── MainWindow.xaml / .cs   # 主界面
│       ├── ViewModels/
│       │   └── MainViewModel.cs        # 视图模型 (MVVM)
│       └── Services/
│           ├── ScreenControlService.cs  # 屏幕控制 (Win32 API)
│           ├── SchedulerService.cs      # 定时调度
│           └── StorageService.cs        # JSON 持久化
│
└── assets/
    └── images/
        └── screen.svg
```

## 技术栈

### Android

| 技术 | 说明 |
|------|------|
| Kotlin | 开发语言 |
| Jetpack Compose | UI 框架 |
| Material 3 | 设计规范 |
| Room | 本地数据库 |
| AlarmManager | 精确定时调度 |
| AccessibilityService | 屏幕控制 |
| DataStore | 偏好设置存储 |

### Windows

| 技术 | 说明 |
|------|------|
| C# | 开发语言 |
| WPF | UI 框架 |
| .NET 8 | 运行时 |
| CommunityToolkit.Mvvm | MVVM 工具包 |
| Win32 API (user32.dll) | 屏幕控制 |
| System.Text.Json | 数据序列化 |

## 构建与运行

### Android

1. 使用 Android Studio 打开 `android/` 目录
2. 同步 Gradle
3. 连接 Android 设备或启动模拟器
4. 点击 Run

**要求：**
- Android Studio Hedgehog | 2023.1.1+
- JDK 17
- Android SDK 35
- 最低支持 Android 8.0 (API 26)

> **国内网络环境**：`settings.gradle.kts` 已配置**阿里云镜像**优先（`maven.aliyun.com`），避免 `repo.maven.apache.org` TLS 握手失败。
> 如果之前拉过官方源失败，重新构建前请清理失败缓存：
> ```bash
> .\gradlew.bat --refresh-dependencies assembleDebug
> ```

### Windows

1. 使用 Visual Studio 2022 打开 `windows/Scroff.sln`
2. 还原 NuGet 包
3. 选择 Debug | Any CPU
4. 按 F5 运行

**要求：**
- Visual Studio 2022 17.8+
- .NET 8 SDK
- ".NET 桌面开发" 工作负载

## 屏幕控制原理

### Android

通过 `AccessibilityService` 的 `performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)` 模拟电源键操作。需要在系统设置中手动开启无障碍服务权限。

### Windows

通过 Win32 API `SendMessage` 发送 `WM_SYSCOMMAND` 消息，参数 `SC_MONITORPOWER` 控制显示器电源状态：
- `MONITOR_OFF (2)` — 关闭显示器
- `MONITOR_ON (-1)` — 打开显示器

## 许可证

[MIT License](LICENSE)
