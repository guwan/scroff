#!/bin/bash
# =============================================================
# Scroff Server - 部署脚本（Ubuntu 22.04 / 24.04）
# =============================================================
# 前置（已装）：mariadb-server（远程的，不在本机）、git
# 本脚本首次会装：openjdk-21-jdk-headless、adb、curl、ca-certificates
#
# 数据库说明：
#   本脚本不会安装、启动、建库。
#   远程 mariadb 由运维单独管理，连接信息写到 application-local.yml。
#
# 首次运行：装包 → 建用户 → 拉代码 → 构建 → 注册 systemd → 启动
# 再次运行：拉最新代码 → 构建 → 停旧服务 → 部署新 jar → 启动新服务
# 再次运行不会重装包，安全可重复
#
# 配置策略：
#   - 公共配置 application.yml 由脚本从 git 拉取 + 部署
#   - 敏感配置 application-local.yml 由用户管理，脚本不覆盖
#     （首次部署自动从 .example 复制并提示用户修改）
#   - systemd 用 --spring.profiles.active=local 加载 application-local.yml
#
# 用法：
#   sudo bash deploy-scroff.sh
# 自定义参数（可选）：
#   SCROFF_GIT_REPO=https://github.com/yourname/scroff.git sudo bash deploy-scroff.sh
#   SCROFF_GIT_BRANCH=develop sudo bash deploy-scroff.sh
# =============================================================

set -euo pipefail

# ---------- 可通过环境变量覆盖 ----------
SCROFF_HOME="${SCROFF_HOME:-/opt/scroff-server}"
SCROFF_USER="${SCROFF_USER:-scroff}"
SCROFF_PORT="${SCROFF_PORT:-8880}"
GIT_REPO="${SCROFF_GIT_REPO:-https://github.com/guwan/scroff.git}"   # ← 改成你的仓库
GIT_BRANCH="${SCROFF_GIT_BRANCH:-master}"
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
    say "[0/8] 首次安装检测：$SCROFF_HOME 不存在或服务未注册"
fi
if [ "$(id -u)" -ne 0 ]; then
    die "请用 sudo 运行：sudo bash $0"
fi

# ---------- 1. 装系统包（仅首次） ----------
#   JDK 21 (Spring Boot 3.x 要求)
#   adb 由 android-tools-adb 包提供（apt 仓库里 `adb` 是个虚拟包指向它）
#   mariadb-server、git 视为已存在（远程 mariadb + 宿主自带 git），不重复装
if [ "$IS_FRESH_INSTALL" = true ]; then
    say "[1/8] 装系统包：openjdk-21 + android-tools-adb + curl + ca-certificates"
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        openjdk-21-jdk-headless android-tools-adb curl ca-certificates

    # 验证 adb 真的装上了（装失败就立刻报错，别等服务跑起来才发现 adb 缺）
    if ! command -v adb >/dev/null 2>&1 || [ ! -x /usr/bin/adb ]; then
        die "adb 装失败，请手动检查 apt 源：apt-cache search android-tools-adb"
    fi
    say "    adb 已就绪: $(/usr/bin/adb version | head -1)"
fi

# ---------- 2. 建用户（仅首次） ----------
if [ "$IS_FRESH_INSTALL" = true ]; then
    say "[2/8] 建服务用户 $SCROFF_USER"
    id -u "$SCROFF_USER" >/dev/null 2>&1 || useradd -r -s /bin/false "$SCROFF_USER"
fi

# ---------- 3. 拉代码 ----------
say "[3/8] 拉取代码到 $BUILD_DIR (branch: $GIT_BRANCH)"
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

# ---------- 4. 构建 ----------
say "[4/8] 构建 jar（gradle clean bootJar，可能需要几分钟首次会下依赖）"
chmod +x gradlew 2>/dev/null || true
./gradlew clean bootJar -x test --no-daemon

JAR_PATH="$BUILD_DIR/scroff-server/build/libs/scroff-server.jar"
[ -f "$JAR_PATH" ] || die "构建失败，未找到 $JAR_PATH"
JAR_SIZE=$(du -h "$JAR_PATH" | cut -f1)
say "    构建完成: $JAR_PATH ($JAR_SIZE)"

# ---------- 5. 停旧服务 ----------
if systemctl is-active --quiet "$SERVICE_NAME"; then
    say "[5/8] 停旧服务 $SERVICE_NAME"
    systemctl stop "$SERVICE_NAME"
else
    say "[5/8] 旧服务未运行，跳过 stop"
fi

# ---------- 6. 部署 ----------
say "[6/8] 部署到 $SCROFF_HOME"
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
    warn "      填入远程 mariadb 的地址、账号、密码和 ADB 路径"
    warn "      然后再 sudo systemctl restart $SERVICE_NAME"
fi

# 权限
chown -R "$SCROFF_USER:$SCROFF_USER" "$SCROFF_HOME"
chmod 600 "$SCROFF_HOME/application-local.yml" 2>/dev/null || true

# ---------- 7. 注册 systemd（仅首次） ----------
if [ "$IS_FRESH_INSTALL" = true ]; then
    say "[7/8] 注册 systemd 服务 $SERVICE_NAME"
    # 显式指向 JDK 21 的 java，避免系统装了多个 JDK 时跑错版本
    JAVA_BIN="$(update-alternatives --query java 2>/dev/null | awk -F': ' '/^Value:/ {print $2; exit}')"
    if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
        # 兜底：常见路径直接找
        for j in /usr/lib/jvm/java-21-openjdk-amd64/bin/java \
                 /usr/lib/jvm/java-21-openjdk/bin/java; do
            [ -x "$j" ] && JAVA_BIN="$j" && break
        done
    fi
    [ -x "$JAVA_BIN" ] || die "找不到 JDK 21 的 java 可执行文件"
    say "    使用 JDK: $JAVA_BIN"

    cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<UNIT
[Unit]
Description=Scroff Server - ADB-based screen controller
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${SCROFF_USER}
WorkingDirectory=${SCROFF_HOME}
# 使用 local profile，自动加载同目录的 application-local.yml
ExecStart=${JAVA_BIN} -jar ${SCROFF_HOME}/scroff-server.jar --spring.profiles.active=local
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
    say "[7/8] systemd 服务已注册，跳过"
fi

# ---------- 8. 启动 ----------
say "[8/8] 启动服务"
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
              改完密码/地址后: sudo systemctl restart ${SERVICE_NAME}

 重新部署:    sudo bash $0
              （自动检测为更新模式，只拉新代码 + 构建 + 重启，
                不会重装包、不会覆盖 application-local.yml）
============================================================
EOF
