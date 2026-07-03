# Debug Session: scroff-screen-wakeup-fail

**Status**: [OPEN]

## 现象
调用 `ScreenControlService.TurnScreenOn()` 后，显示器未真正唤醒（仍黑屏）。
关闭屏幕（`TurnScreenOff`）功能正常。
预期：调用 TurnScreenOn 后显示器立即亮起。

## 假设列表

- **H1：`WM_SYSCOMMAND` + `SC_MONITORPOWER` + `MONITOR_ON` 不足以唤醒硬件** — Win 7+ 系统只更新内部状态，LCD/LED 显示器需要 DDC/CI 信号
- **H2：显示器处于真正的"省电/DPMS"状态而非"Off"** — `MONITOR_ON` 消息只对"软件关闭"有效
- **H3：需要模拟输入事件才能触发显示器唤醒** — 鼠标移动或按键是唤醒的可靠手段
- **H4：调用时显示器尚未进入"Off"状态** — 时序问题，消息丢失
- **H5：Win 10/11 的 Modern Standby 改变了省电模型** — 旧 API 在新系统上不再可靠

## 根因（已通过证据确认）

`H1 + H3` 联合成立：单纯 `SendMessage SC_MONITORPOWER MONITOR_ON` 不足以唤醒硬件，
需要叠加 **模拟输入事件**（`mouse_event MOUSEEVENTF_MOVE` 或 `keybd_event`）。

## 修复策略

1. 保留原 `SendMessage` 调用（确保系统状态正确）
2. 立即叠加 `mouse_event` 模拟鼠标移动（1 像素）
3. 备选：使用 `keybd_event` 模拟按键

## 状态

- [x] 假设提出
- [x] 根因分析
- [x] 实施修复
- [ ] 用户验证
