#!/usr/bin/env bash
# =================================================================
# SAR-javaparser 远程部署脚本 (与 SAR 系列项目风格统一)
# =================================================================
set -euo pipefail

# 1) 参数与变量初始化
# 默认路径改为 sar-javaparser 以作区分
REMOTE_DIR="${1:-/opt/sar-javaparser}"
BUNDLE="${2:-$HOME/sar_javaparser.tgz}"

compose_down () {
  local dir="$1"
  if [ -f "$dir/docker-compose.yml" ]; then
    echo "[deploy] docker compose down in $dir"
    ( cd "$dir" && (docker compose down --remove-orphans || docker-compose down --remove-orphans || true) )
  fi
}

# ---------------------------
# 0) 基础校验
# ---------------------------
if ! command -v docker >/dev/null 2>&1; then
  echo "Error: docker not found"
  exit 2
fi

mkdir -p "$REMOTE_DIR"

# ---------------------------
# 1) 停止旧容器
# ---------------------------
# 依照你先前的逻辑，重点停止部署在 current 目录下的实例
compose_down "$REMOTE_DIR/current"

# ---------------------------
# 2) 端口占用检查 (8080)
# ---------------------------
echo "[deploy] check port 8080"
(ss -ltnp | grep ':8080' || true)
(docker ps --format "table {{.Names}}\t{{.Ports}}" | grep 8080 || true)

# ---------------------------
# 3) 清理并解压新 Bundle 到 current
# ---------------------------
echo "[deploy] preparing directory: $REMOTE_DIR/current"
rm -rf "$REMOTE_DIR/current"
mkdir -p "$REMOTE_DIR/current"

# 校验压缩包完整性
gzip -t "$BUNDLE"
tar -xzf "$BUNDLE" -C "$REMOTE_DIR/current"

cd "$REMOTE_DIR/current"

# ---------------------------
# 4) 产物校验 (适配 javaparser 项目)
# ---------------------------
# 确保部署所需的关键文件存在
test -f "docker-compose.yml"
test -f "app.jar"
test -f "Dockerfile"

# ---------------------------
# 4.1) 检查 .env 文件 (LLM API Keys)
# ---------------------------
if [ -f ".env" ]; then
    echo "[deploy] .env file found, will be used by docker-compose"
    # 显示配置的 LLM Provider (不显示敏感 API Key)
    if grep -q "^LLM_PROVIDER=" .env; then
        echo "[deploy] LLM_PROVIDER: $(grep '^LLM_PROVIDER=' .env | cut -d'=' -f2)"
    fi
else
    echo "[deploy] WARNING: .env file not found"
    echo "[deploy] LLM API Keys will not be available unless set in environment"
    echo "[deploy] To configure API keys, create .env file with:"
    echo "    OPENAI_API_KEY=your_key_here"
    echo "    ANTHROPIC_API_KEY=your_key_here"
    echo "    QWEN_API_KEY=your_key_here"
fi

# ---------------------------
# 5) 启动新栈
# ---------------------------
echo "[deploy] starting new stack in $REMOTE_DIR/current"

# 尝试使用新版 docker compose，失败则回退到 docker-compose
if docker compose version >/dev/null 2>&1; then
    docker compose up -d --build --remove-orphans
else
    docker-compose up -d --build --remove-orphans
fi

# ---------------------------
# 5.1) 等待服务启动并检查健康状态
# ---------------------------
echo "[deploy] waiting for services to be healthy..."
sleep 5

# 检查 MySQL 容器状态
if docker ps --filter "name=javaparser-mysql" --format "{{.Status}}" | grep -q "healthy"; then
    echo "[deploy] ✓ MySQL is healthy"
else
    echo "[deploy] ⚠ MySQL is not healthy yet, check logs: docker logs javaparser-mysql"
fi

# 检查后端容器状态
if docker ps --filter "name=javaparser-backend" --format "{{.Status}}" | grep -q "Up"; then
    echo "[deploy] ✓ Backend is running"
else
    echo "[deploy] ⚠ Backend is not running, check logs: docker logs javaparser-backend"
fi

# ---------------------------
# 6) 清理临时压缩包
# ---------------------------
rm -f "$BUNDLE"

echo "deploy ok"
