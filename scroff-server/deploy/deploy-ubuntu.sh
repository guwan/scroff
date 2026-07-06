#!/bin/bash
# =============================================================
# Scroff Server - 部署脚本（Ubuntu 22.04 / 24.04）
# =============================================================
# 首次运行：装包 → 建库 → 建用户 → 拉代码 → 构建 → 注册 systemd → 启动
# 再次运行：拉最新代码 → 构建 → 停旧服务 → 部署新 jar → 启动新服务
# 再次运行不会重建库、不会重装包，安全可重复
#
# 配置策略：
#   - 公共配置 application.yml 由脚本从 git 拉取 + 部署
#   - 敏感配置 application-local.yml 由用户管理，脚本不覆盖
#     （首次部署自动从 .example 复制并提示用户修改）
#   - systemd 用 --spring.profiles.active=local 加载 application-local.yml
#
# 用法：
#   sudo bash deploy-ubuntu.sh
# 自定义参数（可选）：
#   SCROFF_GIT_REPO=https://github.com/yourname/scroff.git sudo bash deploy-ubuntu.sh
#   SCROFF_GIT_BRANCH=develop sudo bash deploy-ubuntu.sh
#   SCROFF_DB_PASS=强密码 sudo bash deploy-ubuntu.sh
# =============================================================

set -euo pipefail

# ---------- 可通过环境变量覆盖 ----------
SCROFF_HOME="${SCROFF_HOME:-/opt/scroff-server}"
SCROFF_USER="${SCROFF_USER:-scroff}"
SCROFF_PORT="${SCROFF_PORT:-8080}"
DB_NAME="${DB_NAME:-scroff}"
DB_USER="${DB_USER:-scroff}"
DB_PASS="${SCROFF_DB_PASS:-scroff_pwd}"   # 首次建库用，部署后可改 application-local.yml
GIT_REPO="${SCROFF_GIT_REPO:-https://github.com/YOURNAME/scroff.git}"   # ← 改成你的仓库
GIT_BRANCH="${SCROFF_GIT_BRANCH:-main}"
BUILD_DIR="${BUILD_DIR:-/opt/scroff-build}"                              # git 克隆 + gradle 构建的目录
SERVICE_NAME="${SERVICE_NAME:-scroff-server}"
IS_FRESH_INSTALL=false

# ---------- 颜色（让输出更易读） ----------
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
say()  { echo -e "${GREEN}==>${NC} $*"; }
warn() { echo -e "${YELLOW}WARN${NC} $*"; }
die()  { echo -e "${RED}ERROR${NC} $*"; exit 1; }

# ---------- 0. 首次安装检测 ----------
if [ ! -d "$SCROFF_HOME" ] || [ ! -f "/etc/systemd/system/${SERVICE_NAME}.service" ]; then
    IS_FRESH_INSTALL=true
    say "[0/9] 首次安装检测：$SCROFF_HOME 不存在或服务未注册"
fi
if [ "$(id -u)" -ne 0 ]; then
    die "请用 sudo 运行：sudo bash $0"
fi

# ---------- 1. 装系统包（仅首次） ----------
if [ "$IS_FRESH_INSTALL" = true ]; then
    say "[1/9] 装系统包：openjdk-17 + mariadb-server + adb + git"
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        openjdk-17-jdk-headless mariadb-server adb curl git ca-certificates
fi

# ---------- 2. 建用户（仅首次） ----------
if [ "$IS_FRESH_INSTALL" = true ]; then
    say "[2/9] 建服务用户 $SCROFF_USER"
    id -u "$SCROFF_USER" >/dev/null 2>&1 || useradd -r -s /bin/false "$SCROFF_USER"
fi

# ---------- 3. 建库（仅首次） ----------
if [ "$IS_FRESH_INSTALL" = true ]; then
    say "[3/9] 建数据库 $DB_NAME / 用户 $DB_USER"
    systemctl enable --now mariadb
    # 等 mariadb 完全启动
    for i in 1 2 3 4 5; do
        if mysql -uroot -e "SELECT 1" >/dev/null 2>&1; then break; fi
        sleep 1
    done
    mysql -uroot <<EOF
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF
    warn "数据库初始密码: $DB_PASS （请到 $SCROFF_HOME/application-local.yml 同步改）"
fi

# ---------- 4. 拉代码 ----------
say "[4/9] 拉取代码到 $BUILD_DIR (branch: $GIT_BRANCH)"
mkdir -p "$BUILD_DIR"
if [ ! -d "$BUILD_DIR/.git" ]; then
    git clone -b "$GIT_BRANCH" "$GIT_REPO" "$BUILD_DIR"
    say "    首次克隆完成"
else
    cd "$BUILD_DIR"
    git fetch origin
    git reset --hard "origin/$GIT_BRANCH"
    COMMIT=$(git log -1 --oneline)
    say "    已更新到: $COMMIT"
fi
cd "$BUILD_DIR/scroff-server"

# ---------- 5. 构建 ----------
say "[5/9] 构建 jar（gradle clean bootJar，可能需要几分钟首次会下依赖）"
chmod +x gradlew 2>/dev/null || true
./gradlew clean bootJar -x test --no-daemon

JAR_PATH="$BUILD_DIR/scroff-server/build/libs/scroff-server.jar"
[ -f "$JAR_PATH" ] || die "构建失败，未找到 $JAR_PATH"
JAR_SIZE=$(du -h "$JAR_PATH" | cut -f1)
say "    构建完成: $JAR_PATH ($JAR_SIZE)"

# ---------- 6. 停旧服务 ----------
if systemctl is-active --quiet "$SERVICE_NAME"; then
    say "[6/9] 停旧服务 $SERVICE_NAME"
    systemctl stop "$SERVICE_NAME"
else
    say "[6/9] 旧服务未运行，跳过 stop"
fi

# ---------- 7. 部署 ----------
say "[7/9] 部署到 $SCROFF_HOME"
mkdir -p "$SCROFF_HOME"

# 备份上一版本 jar
if [ -f "$SCROFF_HOME/scroff-server.jar" ]; then
    if [ -f "$SCROFF_HOME/scroff-server.jar.prev" ]; then
        rm -f "$SCROFF_HOME/scroff-server.jar.prev"
    fi
    mv "$SCROFF_HOME/scroff-server.jar" "$SCROFF_HOME/scroff-server.jar.prev"
    say "    备份旧 jar → scroff-server.jar.prev"
fi

# 部署新 jar + application.yml
cp "$JAR_PATH" "$SCROFF_HOME/scroff-server.jar"
cp "$BUILD_DIR/scroff-server/src/main/resources/application.yml" "$SCROFF_HOME/"

# application-local.yml 由用户管理（首次部署从 .example 复制并提示）
if [ ! -f "$SCROFF_HOME/application-local.yml" ]; then
    cp "$BUILD_DIR/scroff-server/src/main/resources/application-local.yml.example" \
       "$SCROFF_HOME/application-local.yml"
    warn "首次部署：已复制 application-local.yml.example → application-local.yml"
    warn "请编辑: sudo nano $SCROFF_HOME/application-local.yml"
    warn "      填入数据库密码（默认 $DB_PASS）和 ADB 路径"
    warn "      然后再 sudo systemctl restart $SERVICE_NAME"
fi

# 权限
chown -R "$SCROFF_USER:$SCROFF_USER" "$SCROFF_HOME"
chmod 600 "$SCROFF_HOME/application-local.yml" 2>/dev/null || true

# ---------- 8. 注册 systemd（仅首次） ----------
if [ "$IS_FRESH_INSTALL" = true ]; then
    say "[8/9] 注册 systemd 服务 $SERVICE_NAME"
    cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<UNIT
[Unit]
Description=Scroff Server - ADB-based screen controller
After=network.target mariadb.service
Wants=mariadb.service

[Service]
Type=simple
User=${SCROFF_USER}
WorkingDirectory=${SCROFF_HOME}
# 使用 local profile，自动加载同目录的 application-local.yml
ExecStart=/usr/bin/java -jar ${SCROFF_HOME}/scroff-server.jar --spring.profiles.active=local
Restart=always
RestartSec=5
StandardOutput=append:/var/log/${SERVICE_NAME}.log
StandardError=append:/var/log/${SERVICE_NAME}.err.log
Environment=JAVA_OPTS=-Xms256m -Xmx512m

# 安全加固（测试稳定后建议开启）
# NoNewPrivileges=true
# ProtectSystem=strict
# ReadWritePaths=${SCROFF_HOME} /var/log

[Install]
WantedBy=multi-user.target
UNIT
else
    say "[8/9] systemd 服务已注册，跳过"
fi

# ---------- 9. 启动 ----------
say "[9/9] 启动服务"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl start "$SERVICE_NAME"
sleep 3
systemctl status "$SERVICE_NAME" --no-pager || true

# 验证
if curl -fsS "http://127.0.0.1:${SCROFF_PORT}/" -o /dev/null; then
    say "Web 响应 OK: http://<server-ip>:${SCROFF_PORT}"
else
    warn "Web 未响应，排查: sudo journalctl -u $SERVICE_NAME -n 100"
fi

# adb 启动
adb start-server 2>/dev/null || true

# 总结
DEPLOYED_COMMIT=$(cd "$BUILD_DIR" && git log -1 --oneline 2>/dev/null || echo "<unknown>")
cat <<EOF

============================================================
 部署完成！

 当前版本:   $DEPLOYED_COMMIT
 Web URL:     http://<server-ip>:${SCROFF_PORT}
 服务管理:    sudo systemctl {status|restart|stop} ${SERVICE_NAME}
 实时日志:    sudo journalctl -u ${SERVICE_NAME} -f
 历史 jar:    $SCROFF_HOME/scroff-server.jar.prev  (回滚: mv .prev scroff-server.jar)

 敏感配置:    $SCROFF_HOME/application-local.yml  (chmod 600)
              改完密码后: sudo systemctl restart ${SERVICE_NAME}

 重新部署:    sudo bash $0
              （自动检测为更新模式，只拉新代码 + 构建 + 重启，
                不会重建库、不会重装包、不会覆盖 application-local.yml）
============================================================
EOF
