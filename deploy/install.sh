#!/usr/bin/env bash
# ============================================================
# 自助收费打印系统 · Ubuntu 22.04 / 24.04 一键部署脚本
#
# 用法（在仓库根目录执行）：
#   sudo bash deploy/install.sh              # 全量安装
#   sudo SKIP_APT=1 bash deploy/install.sh   # 依赖已装好，只重新发布
#
# 脚本做的事：
#   1. 安装 JDK17 / Maven / Node / LibreOffice / 中文字体 / MySQL / Nginx
#   2. 建系统用户 printsys 与目录 /opt/printsys/{backend,frontend,data,logs}
#   3. 编译后端 jar 与前端 dist
#   4. 建库导入 schema，写入 .env（若不存在）
#   5. 安装 systemd 服务与 Nginx 站点，启动并做健康检查
#
# 幂等：重复执行只会更新程序文件，不会覆盖已有的 .env 与数据库数据。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP=/opt/printsys
JAR="$APP/backend/print-system.jar"

log()  { printf '\033[1;34m[%s]\033[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\033[1;33m[警告]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[失败]\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "请用 sudo 执行"
[[ -f "$ROOT/backend/pom.xml" ]] || die "找不到 backend/pom.xml，请在仓库根目录执行"

# ---------------- 1. 系统依赖 ----------------
if [[ "${SKIP_APT:-0}" != "1" ]]; then
  log "安装系统依赖（首次执行约 5~10 分钟，主要是 LibreOffice 与中文字体）"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq \
    openjdk-17-jre-headless maven curl ca-certificates \
    libreoffice-writer libreoffice-calc libreoffice-impress \
    fonts-noto-cjk fonts-wqy-zenhei \
    nginx mysql-server

  if ! command -v node >/dev/null 2>&1; then
    log "安装 Node.js 20（用于构建前端）"
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
    apt-get install -y -qq nodejs
  fi
else
  log "SKIP_APT=1，跳过依赖安装"
fi

command -v java >/dev/null || die "未找到 java，请先安装 openjdk-17"
command -v soffice >/dev/null || warn "未找到 soffice：Office 文档将无法转 PDF，只能按估算页数计价（不推荐）"

# ---------------- 2. 用户与目录 ----------------
log "创建运行用户与目录"
id -u printsys >/dev/null 2>&1 || useradd -r -m -d "$APP" -s /usr/sbin/nologin printsys
mkdir -p "$APP"/{backend,frontend,data/uploads,logs}
chown -R printsys:printsys "$APP"

# ---------------- 3. 环境变量 ----------------
if [[ ! -f "$APP/.env" ]]; then
  log "生成 $APP/.env（请务必按需修改后再重启服务）"
  cp "$ROOT/.env.example" "$APP/.env"
  # 自动填充两个随机密钥，避免出现空值启动失败
  agent_token="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  db_pass="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  admin_pass="Adm$(head -c 9 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 9)!"
  sed -i "s|^AGENT_TOKEN=.*|AGENT_TOKEN=$agent_token|" "$APP/.env"
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$db_pass|" "$APP/.env"
  sed -i "s|^ADMIN_PASSWORD=.*|ADMIN_PASSWORD=$admin_pass|" "$APP/.env"
  chmod 600 "$APP/.env"
  chown root:root "$APP/.env"
  warn "已生成随机口令，请立即记录："
  echo "   数据库口令 : $db_pass"
  echo "   管理端口令 : $admin_pass"
  echo "   助手密钥   : $agent_token"
  echo "   （修改 $APP/.env 后 systemctl restart printsys-backend 生效）"
else
  log "$APP/.env 已存在，保持不变"
fi
# shellcheck disable=SC1091
set -a; source "$APP/.env"; set +a

# ---------------- 4. 编译后端 ----------------
log "编译后端（跳过测试，首次会下载依赖）"
( cd "$ROOT/backend" && mvn -B -q clean package -DskipTests )
JAR_SRC="$(ls -1t "$ROOT"/backend/target/print-system-*.jar | grep -v sources | head -1)"
[[ -n "$JAR_SRC" ]] || die "后端编译产物不存在"
install -o printsys -g printsys -m 644 "$JAR_SRC" "$JAR"
log "后端 jar 已更新：$JAR"

# ---------------- 5. 编译前端 ----------------
if command -v npm >/dev/null 2>&1; then
  log "编译前端"
  ( cd "$ROOT/frontend" && { npm ci --no-audit --no-fund 2>/dev/null || npm install --no-audit --no-fund; } && npm run build )
  rm -rf "$APP/frontend/dist.new"
  cp -r "$ROOT/frontend/dist" "$APP/frontend/dist.new"
  rm -rf "$APP/frontend/dist"
  mv "$APP/frontend/dist.new" "$APP/frontend/dist"
  chown -R www-data:www-data "$APP/frontend/dist"
  log "前端已发布到 $APP/frontend/dist"
else
  warn "未找到 npm，跳过前端构建；请把 frontend/dist 手动拷到 $APP/frontend/dist"
fi

# ---------------- 6. 数据库 ----------------
log "初始化数据库（若已存在则跳过建表）"
mysql -uroot <<SQL
CREATE DATABASE IF NOT EXISTS ${DB_NAME:-print_system} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER:-printsys}'@'localhost' IDENTIFIED BY '${DB_PASSWORD}';
GRANT ALL PRIVILEGES ON ${DB_NAME:-print_system}.* TO '${DB_USER:-printsys}'@'localhost';
FLUSH PRIVILEGES;
SQL
mysql -uroot "${DB_NAME:-print_system}" < "$ROOT/backend/schema-mysql.sql"
mysql -uroot -e "SELECT COUNT(*) AS tables_ready FROM information_schema.tables WHERE table_schema='${DB_NAME:-print_system}';"

# ---------------- 7. systemd + nginx ----------------
log "安装 systemd 服务"
install -m 644 "$ROOT/deploy/printsys-backend.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable printsys-backend >/dev/null
systemctl restart printsys-backend

log "配置 Nginx"
install -m 644 "$ROOT/deploy/nginx.conf" /etc/nginx/conf.d/printsys.conf
if grep -q "print.example.com" /etc/nginx/conf.d/printsys.conf; then
  warn "nginx.conf 里还是示例域名 print.example.com，请改成真实域名后执行："
  echo "    sed -i 's/print.example.com/你的域名/g' /etc/nginx/conf.d/printsys.conf"
  echo "    certbot --nginx -d 你的域名 && nginx -t && systemctl reload nginx"
  # 证书还不存在时 nginx -t 会失败，先不 reload，避免打断现有站点
else
  nginx -t && systemctl reload nginx
fi

# ---------------- 8. 健康检查 ----------------
log "等待后端启动并做健康检查"
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/api/health" >/dev/null 2>&1; then
    log "后端健康检查通过"
    curl -s "http://127.0.0.1:${SERVER_PORT:-8080}/api/health"; echo
    break
  fi
  [[ $i -eq 60 ]] && { warn "后端 60s 内未就绪，查看日志： journalctl -u printsys-backend -n 100 --no-pager"; exit 1; }
  sleep 1
done

cat <<EOF

================ 部署完成 ================
管理端      : https://<域名>/admin/login
用户端      : https://<域名>/?t=T01   （?t= 是终端号，与打印助手 TerminalId 一致）
打印助手    : 在打印电脑上运行 print-agent，ApiBaseUrl 指向本服务器
环境变量    : $APP/.env（改完 systemctl restart printsys-backend）
后端日志    : journalctl -u printsys-backend -f
上线前检查  :
  1. PAY_MOCK_ENABLED=false            （务必确认，否则可白打）
  2. ALIPAY_SELLER_ID / WECHAT_MCH_ID  （回调金额与收款方校验）
  3. PUBLIC_BASE_URL 为公网可达域名     （支付回调依赖它）
  4. SMS_PROVIDER 已换成真实短信通道
  5. CONVERT_ENABLED=true 且 soffice 可用（按真实页数计价）
==========================================
EOF
