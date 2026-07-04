#!/bin/bash
# =============================================================
# Scroff Server 一键部署脚本（Ubuntu 22.04 / 24.04）
# 用法：sudo bash deploy-ubuntu.sh
# =============================================================
set -e

SCROFF_USER=scroff
SCROFF_HOME=/opt/scroff-server
SCROFF_PORT=8080
DB_NAME=scroff
DB_USER=scroff
DB_PASS=scroff_pwd

echo "==> 1/8 安装系统依赖"
apt-get update
apt-get install -y openjdk-17-jdk-headless mariadb-server adb curl

echo "==> 2/8 创建运行用户"
id -u $SCROFF_USER >/dev/null 2>&1 || useradd -r -s /bin/false $SCROFF_USER

echo "==> 3/8 建数据库"
systemctl enable --now mariadb
mysql -uroot <<EOF
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
EOF

echo "==> 4/8 部署 jar"
mkdir -p $SCROFF_HOME
cp scroff-server.jar $SCROFF_HOME/
cp application.yml $SCROFF_HOME/ 2>/dev/null || true
chown -R $SCROFF_USER:$SCROFF_USER $SCROFF_HOME

echo "==> 5/8 部署 systemd unit"
cat > /etc/systemd/system/scroff-server.service <<UNIT
[Unit]
Description=Scroff Server - ADB-based screen controller
After=network.target mariadb.service
Wants=mariadb.service

[Service]
Type=simple
User=$SCROFF_USER
WorkingDirectory=$SCROFF_HOME
ExecStart=/usr/bin/java -jar $SCROFF_HOME/scroff-server.jar --spring.config.location=$SCROFF_HOME/application.yml
Restart=always
RestartSec=5
StandardOutput=append:/var/log/scroff-server.log
StandardError=append:/var/log/scroff-server.err.log
Environment=JAVA_OPTS="-Xms256m -Xmx512m"

[Install]
WantedBy=multi-user.target
UNIT

echo "==> 6/8 启动服务"
systemctl daemon-reload
systemctl enable --now scroff-server
sleep 3
systemctl status scroff-server --no-pager

echo "==> 7/8 验证"
curl -fsS http://127.0.0.1:$SCROFF_PORT/ -o /dev/null && echo "✓ Web 响应正常" || echo "✗ Web 不可达"

echo "==> 8/8 adb 守护"
# 启动 adb server，确保端口可用
adb start-server 2>/dev/null || true

cat <<EOF

============================================================
 部署完成！

 访问地址：http://<服务器IP>:$SCROFF_PORT
 数据库  ：$DB_NAME  (user: $DB_USER / pass: $DB_PASS)
 日志    ：/var/log/scroff-server.log
 服务管理：
   sudo systemctl status  scroff-server
   sudo systemctl restart scroff-server
   sudo systemctl stop    scroff-server

 下一步：把叫号机 USB 插上本机一次，执行：
   adb tcpip 5555
   adb devices        # 记下设备 IP
 然后在 Web 后台「设备」菜单里添加 IP:5555 即可。
============================================================
EOF
