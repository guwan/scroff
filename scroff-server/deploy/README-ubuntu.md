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
| JDK  | 21 (`openjdk-21-jdk-headless`) | Spring Boot 3.x 强制 |
| MariaDB | 远程实例 | 脚本不安装、不启动、不建库 |
| Git  | 已装 | 脚本不再安装 |
| ADB  | `android-tools-adb` (apt 首次装) | |
| 网络 | 与叫号机同子网，且能访问远程 mariadb | |

## 3. 一键安装

```bash
# 在项目根目录，把产物拷到服务器 /tmp
scp scroff-server.jar application.yml deploy-ubuntu.sh scroff-server.service user@server:/tmp/

# SSH 到服务器后
sudo bash /tmp/deploy-ubuntu.sh
```

脚本会自动完成：装 JDK 21 + adb → 拉代码 → 构建 → 部署 jar → 注册 systemd → 启动服务。
**不会**安装、启动、配置任何数据库。

## 4. 手工安装

### 4.1 装包

> mariadb 是远程的（由运维单独管理），git 视为服务器自带。
> 首次部署需要装的只有 JDK 21 + adb。

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk-headless android-tools-adb
```

### 4.2 部署 jar

```bash
sudo mkdir -p /opt/scroff-server
sudo cp scroff-server.jar application.yml /opt/scroff-server/
sudo useradd -r -s /bin/false scroff
sudo chown -R scroff:scroff /opt/scroff-server
```

### 4.3 写入敏感配置（远程 mariadb 连接信息 + ADB 路径）

> 重要：远程 mariadb 的地址、账号、密码全部写在这里。
> 文件权限必须 600，systemd 启动时由 scroff 用户读取。

```bash
sudo nano /opt/scroff-server/application-local.yml
```

示例内容（**根据实际远程 mariadb 改**）：

```yaml
spring:
  datasource:
    url: jdbc:mariadb://你的mariadb-host:3306/scroff
    username: scroff
    password: 你的密码
    driver-class-name: org.mariadb.jdbc.Driver

scroff:
  adb:
    active-profile-id: default
    profiles:
      - id: default
        name: Default
        executable: /usr/bin/adb
        enabled: true
```

```bash
sudo chmod 600 /opt/scroff-server/application-local.yml
sudo chown scroff:scroff /opt/scroff-server/application-local.yml
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

> 注意：升级只需替换 `scroff-server.jar`，**不要删 `application-local.yml`**，那里面存着远程 mariadb 的地址/账号/密码。

## 11. 配置管理（敏感信息处理）

项目公开在 Git 上，所以分了三层配置：

| 位置 | 提交到 Git？ | 内容 |
|------|------|------|
| `src/main/resources/application.yml` | ✅ 提交 | 端口、JPA、Thymeleaf、占位符形式的默认值（`${DB_PASSWORD:}`） |
| `src/main/resources/application-local.yml.example` | ✅ 提交 | 本地开发 + 部署模板（占位符 `YOUR_LOCAL_DB_PASSWORD`） |
| `src/main/resources/application-local.yml` | ❌ 忽略 | 本地开发真实配置 |
| `/opt/scroff-server/application-local.yml` | ❌ 部署时生成 | 生产环境敏感配置（**远程 mariadb 地址/账号/密码**、ADB 路径） |

**生产环境**用 `application-local.yml` 直接写敏感值（`chmod 600`），systemd 启动时由 scroff 用户读取，profile = `local`。

**轮换远程数据库密码**：

```bash
# 1. 改 application-local.yml
sudo nano /opt/scroff-server/application-local.yml
#    spring.datasource.password=新密码
#    spring.datasource.url=jdbc:mariadb://远程地址:3306/scroff

# 2. 同步改远程 mariadb 的用户密码
mysql -h远程地址 -uadmin -p -e "ALTER USER 'scroff'@'%' IDENTIFIED BY '新密码';"

# 3. 重启服务
sudo systemctl restart scroff-server
```

## 12. 本地开发（Windows / macOS / Linux）

```bash
# 第一次启动
cd scroff-server
./scripts/run-local.sh          # Linux/macOS
scripts\run-local.bat           # Windows
```

脚本会：
1. 检查 `application-local.yml` 是否存在，没有就从 `.example` 复制一份
2. 检查 `YOUR_LOCAL_DB_PASSWORD` 占位符是否还在，是就退出让你改
3. 执行 `./gradlew bootRun -Dspring.profiles.active=local`

> 提示：原来的 `./gradlew bootRun` 也能跑，但默认会连 `application.yml` 里的空密码，连接会失败。请用 `scripts/run-local.*` 启动。
