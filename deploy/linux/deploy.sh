#!/bin/bash
# ============================================================
# training-center 一键部署脚本 (Linux)
#
# 用法:
#   ./training-center/deploy/linux/deploy.sh             # 完整部署
#   ./training-center/deploy/linux/deploy.sh --no-base   # 跳过基础镜像构建
#   ./training-center/deploy/linux/deploy.sh --prune     # 部署成功后清理悬空镜像
#
# 前置条件:
#   - Docker 已安装并运行 (支持 docker compose v2 或 docker-compose)
#   - 工作区目录结构 (脚本会自动校验):
#       <root>/training-center/        (本仓库)
#       <root>/output/coding-ai-exam/  (题库, 必须)
#       <root>/output/interview/       (面试资料, 必须)
#   - 服务器内存 >= 2G, 磁盘剩余 >= 10G
#
# 数据目录: /home/docker/training-data (可用 TRAINING_DATA_DIR 覆盖)
# 部署后:  http://服务器IP:8080
# ============================================================
set -euo pipefail

# ---- 路径 ----
WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
REPO_DIR="$WORKSPACE_ROOT/training-center"
COMPOSE_BASE="$REPO_DIR/deploy/docker-compose.yml"
COMPOSE_PROD="$REPO_DIR/deploy/linux/docker-compose.prod.yml"
IMAGE_BASE=training-center-base:17
DATA_DIR="${TRAINING_DATA_DIR:-/home/docker/training-data}"
PORT=8080
APP_UID=1000

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info() { echo -e "${GREEN}[deploy]${NC} $*"; }
warn() { echo -e "${YELLOW}[deploy]${NC} $*"; }
die()  { echo -e "${RED}[deploy] ERROR: $*${NC}" >&2; exit 1; }

# ---- 参数 ----
SKIP_BASE=false
PRUNE=false
for arg in "$@"; do
  case "$arg" in
    --no-base) SKIP_BASE=true ;;
    --prune)   PRUNE=true ;;
    -h|--help)
      echo "用法: deploy.sh [--no-base] [--prune]"
      echo "  --no-base  跳过基础镜像构建 (需已存在 training-center-base:17)"
      echo "  --prune    部署成功后清理悬空镜像 docker image prune -f"
      exit 0 ;;
    *) warn "未知参数: $arg" ;;
  esac
done

info "工作区根目录: $WORKSPACE_ROOT"

# ---- 0. 通用工具函数 ----
# 兼容 docker compose v2 (推荐) 与 docker-compose v1
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  die "未找到 docker compose v2 或 docker-compose"
fi

# 非 root 运行时用 sudo 设置数据目录属主, 避免脚本在 chown 处直接失败
chown_data() {
  local dir="$1"
  if [ "$(id -u)" -eq 0 ]; then
    chown -R "$APP_UID:$APP_UID" "$dir"
  elif command -v sudo >/dev/null 2>&1; then
    sudo chown -R "$APP_UID:$APP_UID" "$dir" \
      || warn "无法设置 $dir 属主, 请手动执行: sudo chown -R $APP_UID:$APP_UID $dir"
  else
    warn "非 root 且无 sudo, 无法设置 $dir 属主, 请手动执行: chown -R $APP_UID:$APP_UID $dir"
  fi
}

# ---- 1. 前置检查 ----
command -v docker >/dev/null 2>&1 || die "未安装 docker"
docker info >/dev/null 2>&1 || die "docker daemon 未运行 (sudo systemctl start docker)"
[ -f "$WORKSPACE_ROOT/output/coding-ai-exam/catalog/questions.json" ] \
    || die "缺少题库: output/coding-ai-exam/catalog/questions.json"
[ -d "$WORKSPACE_ROOT/output/interview" ] \
    || die "缺少面试资料: output/interview"

FREE_KB=$(df --output=avail -k "$WORKSPACE_ROOT" 2>/dev/null | tail -1 | tr -d ' ')
if [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt 10485760 ]; then
  warn "磁盘剩余不足 10G ($((FREE_KB/1024/1024))G), 构建可能失败"
fi
if (ss -lnt 2>/dev/null || netstat -lnt 2>/dev/null) | grep -q ":$PORT "; then
  die "端口 $PORT 已被占用"
fi

# 检测到导入索引时提醒同步 output (导入内容不在 git 内, 需先同步到服务器)
if [ -f "$REPO_DIR/config/imported-questions.json" ]; then
  warn "检测到导入索引 imported-questions.json; 请确认 output/ 已同步最新导入内容 (本地用 deploy/sync-data.ps1)"
fi

# ---- 1.5 同步 .dockerignore (构建上下文=工作区根目录, 该文件必须在其根部) ----
[ -f "$REPO_DIR/.dockerignore" ] || die "仓库缺少 .dockerignore"
if ! cp -f "$REPO_DIR/.dockerignore" "$WORKSPACE_ROOT/.dockerignore"; then
  die "无法写入 $WORKSPACE_ROOT/.dockerignore (需要写权限)"
fi
info "已同步 .dockerignore"

# ---- 2. 数据目录 (bind mount) ----
mkdir -p "$DATA_DIR/training-runtime"/{database,sandboxes,exports,logs}
chown_data "$DATA_DIR/training-runtime"
info "数据目录: $DATA_DIR/training-runtime (uid $APP_UID)"

# ---- 3. 基础镜像 (JDK + Maven + Python + pytest) ----
if [ "$SKIP_BASE" = true ]; then
  info "跳过基础镜像构建 (--no-base)"
elif docker image inspect "$IMAGE_BASE" >/dev/null 2>&1; then
  info "基础镜像 $IMAGE_BASE 已存在, 跳过 (强制重建: 先 docker image rm $IMAGE_BASE)"
else
  info "构建基础镜像 $IMAGE_BASE ..."
  docker build -f "$REPO_DIR/deploy/base/Dockerfile" -t "$IMAGE_BASE" "$WORKSPACE_ROOT"
fi

# ---- 4. 应用镜像 ----
info "构建应用镜像 training-center:latest ..."
docker build -f "$REPO_DIR/Dockerfile" -t training-center:latest "$WORKSPACE_ROOT"

# ---- 5. 启动 ----
info "启动容器 (compose + prod override) ..."
$DC -f "$COMPOSE_BASE" -f "$COMPOSE_PROD" up -d --force-recreate

# ---- 6. 健康检查 ----
info "等待服务就绪 ..."
for i in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
    echo
    info "部署完成: http://服务器IP:$PORT"
    curl -s "http://127.0.0.1:$PORT/api/health"; echo
    if [ "$PRUNE" = true ]; then
      docker image prune -f >/dev/null 2>&1 \
        && info "已清理悬空镜像" || warn "镜像清理失败"
    fi
    exit 0
  fi
  sleep 3
done
die "健康检查超时, 排查: docker logs --tail 50 training-center"
