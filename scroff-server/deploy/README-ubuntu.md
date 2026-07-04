# Scroff Server 部署文档（Ubuntu 22.04 / 24.04）

叫号机集中控屏服务 —— 通过 ADB over TCP/IP 管理安卓屏幕开关。

## 1. 架构

```
                ┌────────────────────────┐
   Web UI ────► │  Spring Boot 服务       │ ── adb -s host:port shell ...
   (8080)       │  · Thymeleaf 渲染        │
                │  · @Scheduled 触发       │
                │  · MariaDB 存任务         │
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

## 2. 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| OS   | Ubuntu 22.04 / 24.04 LTS | 其它 Debian 系发行版也兼容 |
| JDK  | 17 (`openjdk-17-jdk-headless`) | Spring Boot 3.x 强制 |
| MariaDB | 10.6+ (Ubuntu 仓库即可) | |
| ADB  | `android-tools-adb` (apt) | |
| 网络 | 与叫号机同子网 | |

## 3. 一键安装

```bash
# 在项目根目录，把 scroff-server.jar 和 application.yml 拷到当前目录
scp scroff-server.jar application.yml user@server:/tmp/

# SSH 到服务器后
sudo bash deploy-ubuntu.sh
```

脚本会自动完成：装包 → 建库 → 部署 jar → 注册 systemd → 启动服务。

## 4. 手工安装

### 4.1 装包

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk-headless mariadb-server adb
```

### 4.2 建库

```bash
sudo systemctl enable --now mariadb
sudo mysql -uroot <<'SQL'
CREATE DATABASE scroff
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
CREATE USER 'scroff'@'localhost' IDENTIFIED BY '改成强密码';
GRANT ALL PRIVILEGES ON scroff.* TO 'scroff'@'localhost';
FLUSH PRIVILEGES;
SQL
```

修改 `application.yml` 里的 `spring.datasource.username/password`。

### 4.3 部署 jar

```bash
sudo mkdir -p /opt/scroff-server
sudo cp scroff-server.jar application.yml /opt/scroff-server/
sudo useradd -r -s /bin/false scroff
sudo chown -R scroff:scroff /opt/scroff-server
```

### 4.4 注册 systemd

```bash
sudo cp scroff-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now scroff-server
sudo systemctl status scroff-server
```

### 4.5 验证

```bash
curl http://127.0.0.1:8080/
```

## 5. 配置叫号机（首次）

每台叫号机需要**一次** USB 插本地服务器：

```bash
# 插上 USB
adb devices
# 192.168.1.50:5555  device      ← 已 TCP 连接的会出现在这里

# 把 USB 设备切到 TCP/IP 模式
adb tcpip 5555
# restarting in TCP mode port: 5555

# 拔掉 USB
adb connect 192.168.1.50:5555
# connected to 192.168.1.50:5555
```

之后在 Web 后台 `/devices/new` 添加：
- 名称：前台叫号机 1
- IP：192.168.1.50
- 端口：5555

## 6. 配置定时任务

Web 后台 `/schedules/new`：

| 场景 | Cron | 动作 |
|------|------|------|
| 每天 22:00 关屏 | `0 0 22 * * *` | OFF |
| 每天 08:00 开屏 | `0 0 8 * * *` | ON |
| 工作日 12:00 关屏 | `0 0 12 * * 1-5` | OFF |
| 每月 1 号 00:00 重启屏 | `0 0 0 1 * *` | ON |

## 7. REST API（外部对接）

```bash
# 立即开屏
curl -X POST http://server:8080/api/devices/1/screen/on

# 立即关屏
curl -X POST http://server:8080/api/devices/1/screen/off

# 查最近 20 条日志
curl http://server:8080/api/devices/1/logs?size=20
```

返回 JSON：`{ "ok": true, "message": "开屏成功", "deviceId": 1, "action": "ON" }`

## 8. 关键路径

| 用途 | 路径 |
|------|------|
| 服务日志 | `/var/log/scroff-server.log` |
| 错误日志 | `/var/log/scroff-server.err.log` |
| jar | `/opt/scroff-server/scroff-server.jar` |
| 配置 | `/opt/scroff-server/application.yml` |
| 单元文件 | `/etc/systemd/system/scroff-server.service` |

## 9. 故障排查

```bash
# 服务跑没跑？
sudo systemctl status scroff-server

# 看实时日志
sudo journalctl -u scroff-server -f

# adb 能连上设备吗？
adb devices
adb -s 192.168.1.50:5555 shell echo ok

# 手动测一下命令能不能跑通
adb -s 192.168.1.50:5555 shell "cd /sys/kernel/debug/dispdbg && echo disp0 > name && echo blank > command && echo 1 > param && echo 1 > start"
```

如果 `dispdbg` 在叫号机上不可用，参考：
- [内核 dispdbg 文档](https://www.kernel.org/doc/Documentation/...)
- 备选：`/sys/devices/virtual/graphics/fb0/blank`（echo 4 关 / echo 0 开）
- 在 `application.yml` 里改 `scroff.adb.screen-off-command`

## 10. 升级

```bash
# 停服务
sudo systemctl stop scroff-server

# 备份旧 jar
sudo cp /opt/scroff-server/scroff-server.jar /opt/scroff-server/scroff-server.jar.bak

# 部署新 jar
sudo cp scroff-server.jar /opt/scroff-server/

# 启动
sudo systemctl start scroff-server
```

数据库 schema 由 JPA 自动管理（`ddl-auto: update`），新增字段不会丢数据。
