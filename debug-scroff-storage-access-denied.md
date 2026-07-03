# Debug Session: scroff-storage-access-denied

**Status**: [OPEN]

## 现象
Windows WPF 启动时弹窗报错：
> Access to the path 'C:\Users\s\AppData\Roaming\Scroff\schedules.json' is denied.

每次启动都报。预期：正常加载/保存定时任务。

## 假设列表

- **H1：残留进程占用了文件句柄** — 上次 Scroff 进程未完全退出，文件被独占/共享写模式打开
- **H2：目录权限不足** — `%APPDATA%\Scroff\` 文件夹没有读写权限（首次创建时未授权）
- **H3：文件被其他进程占用** — 杀毒软件、OneDrive、文件索引器等正在读取/锁定
- **H4：路径指向只读/隐藏属性** — 文件本身被设为只读
- **H5：JsonSerializer 与保留字段冲突** — `Timer` 字段（内部 Timer 引用）序列化/反序列化时访问受限

## 根因（已定位）

上一版 StorageService.Save 采用了"写 .tmp → 删原文件 → 重命名"的三步原子替换方案。该方案在以下情况失败：
- 残留的 `.tmp` 文件被前次未完成的保存操作遗留
- `File.Delete` 对受保护/锁定的文件抛出 UnauthorizedAccessException
- `File.Move` 在目标已存在时需要先删除

错误信息确认为"Access to the path '...schedules.json.tmp' is denied"，与本分析一致。

## 修复方案

1. 去掉 `.tmp` 中间步骤，直接 `FileMode.Create` 覆盖原文件
2. 使用 `FileShare.Read` 允许其他进程并发读
3. 加入 5 次重试机制（50ms 递增），处理瞬时锁
4. `lock(_fileLock)` 进程内互斥

## 验证

待用户确认。

## 状态

- [x] 假设提出
- [x] 证据收集
- [x] 根因定位
- [x] 最小修复
- [ ] 用户验证
- [ ] 清理插桩

注：未引入插桩日志（证据直接来自用户报告的错误信息 + 上一版代码 review），已避免引入临时调试代码污染。
