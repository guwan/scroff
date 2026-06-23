# Scroff - 屏幕定时开关控制器 (Screen Schedule Controller)

跨平台屏幕定时开关应用，基于 **React Native + TypeScript + NativeWind v4**，目标平台：**Android** 与 **Windows**。

## 功能

- 自定义开屏 / 关屏时间（默认 07:50 / 17:30，±30 秒精度）
- 后台常驻：到点自动执行开关屏
- AsyncStorage 持久化配置
- 深色主题 UI，Tailwind class 编写样式
- 手动立即开/关屏

## 技术栈

| 类别 | 选型 |
| ---- | ---- |
| 框架 | React Native 0.76.5 |
| 语言 | TypeScript 5.x（`strict: true`） |
| 样式 | NativeWind v4 (Tailwind CSS) |
| 平台 | Android, Windows (react-native-windows) |
| 持久化 | @react-native-async-storage/async-storage |
| 包管理 | pnpm |

## 目录结构

```
.
├── App.tsx                 # 根组件
├── index.js                # RN 入口
├── app.json
├── package.json
├── tsconfig.json           # TS 严格模式 + 路径别名
├── babel.config.js         # NativeWind + module-resolver
├── metro.config.js
├── tailwind.config.js
├── global.css              # @tailwind 指令
├── nativewind-env.d.ts
├── src/
│   ├── components/         # TimePicker / ScheduleCard / StatusBadge
│   ├── modules/            # Native Module 封装
│   │   ├── ScreenControl.ts
│   │   ├── android/        # Android 原生参考实现
│   │   └── windows/        # Windows 原生参考实现
│   ├── services/           # SchedulerService / StorageService
│   ├── screens/            # HomeScreen
│   ├── hooks/              # useScheduler / useScreenControl
│   └── types/              # 全局类型
```

## 快速开始

### 1. 安装依赖

```bash
pnpm install
```

### 2. 浏览器预览（最快看到 UI）

```bash
pnpm web          # 启动 webpack-dev-server，访问 http://localhost:3000（热更新）
# 或生产构建
pnpm web:build    # 输出 web/dist/ 包含 styles.css + bundle.js + index.html
```

> Web 平台使用 `react-native-web` + **本地 Tailwind**（PostCSS 在 webpack 构建期处理 `global.css`）。  
> **完全离线可用**，不依赖任何 CDN。  
> 控屏按钮走 `ScreenControl.ts` 的降级实现。

### 3. Metro 启动（连接原生平台）

```bash
pnpm start
```

### 4. 平台构建

#### Android

```bash
pnpm android
```

> 首次需要脚手架生成完整原生工程：
>
> ```bash
> npx @react-native-community/cli@latest init ScroffScreen --version 0.76.5 --pm pnpm --skip-install
> ```
>
> 生成后将本项目 `src/`、`App.tsx`、`index.js`、`package.json`、`tsconfig.json` 等合并到 `ScroffScreen/` 根目录。
>
> 合并后再按 `src/modules/android/` 下的参考文件实现 `ScreenControlModule.kt` / `ScreenControlPackage.kt`，并在 `AndroidManifest.xml` 追加权限（参考 `src/modules/android/AndroidManifest.reference.xml`）。
>
> 如执行 `npx react-native-windows-init` 报 SIGINT，请改用 `npx --yes react-native-windows-init@0.76 --overwrite --pm pnpm`（必须与 RN 主版本匹配）。

#### Windows

```bash
pnpm windows
```

> **重要：Windows 平台需以管理员身份运行 VS / 终端，否则 `SendMessage` 控制显示器会失败。**
>
> 同样需要先脚手架：
>
> ```bash
> npx react-native-windows-init --overwrite
> ```
>
> 然后按 `src/modules/windows/ScreenControlModule.reference.cs` 实现 C# 原生模块，在 `Package.appxmanifest` 添加 `runFullTrust` 能力。

## 关键权限

### Android

```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.DEVICE_POWER" />
<uses-permission android:name="android.permission.SHUTDOWN" />
```

若使用 `DevicePolicyManager.lockNow()`，需要：

1. 创建 `res/xml/device_admin_policy.xml`
2. 注册 `DeviceAdminReceiver`
3. 运行时 `dpm.bindDeviceAdmin(...)` 激活设备管理员

### Windows

需在 `Package.appxmanifest` 添加：

```xml
<rescap:Capability Name="runFullTrust" />
```

桌面应用以管理员身份运行，否则 `SendMessage(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, 2)` 调用会被拒绝。

## 后台保活方案

- **Android**：`WorkManager` + `ForegroundService`（推荐），或 `react-native-headless-js`。
- **Windows**：`BackgroundTaskBuilder` 注册 `TimeTrigger` 后台任务，或 `AppService` 中常驻计时器。

## 已知限制

- 定时器精度为 ±30 秒，依赖系统调度。
- Android 关屏需要设备管理员权限；部分厂商 ROM 可能拒绝 `lockNow()`。
- iOS 不在支持范围（系统不允许第三方 App 控屏）。

## License

MIT
