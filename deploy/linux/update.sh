#!/bin/bash
# ============================================================
# training-center 更新部署脚本 (Linux)
#
# 流程: git pull -> 重建应用镜像 -> 重建容器 -> 健康检查
# 基础镜像通常无需重建 (中间件未变), 需要时先手动重建。
#
# 用法:
#   ./training-center/deploy/linux/update.sh
#   ./training-center/deploy/linux/update.sh --prune
# ============================================================
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
REPO_DIR="$WORKSPACE_ROOT/training-center"
COMPOSE_BASE="$REPO_DIR/deploy/docker-compose.yml"
COMPOSE_PROD="$REPO_DIR/deploy/linux/docker-compose.prod.yml"
DATA_DIR="${TRAINING_DATA_DIR:-/home/docker/training-data}"
PORT=8080
APP_UID=1000

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info() { echo -e "${GREEN}[update]${NC} $*"; }
warn() { echo -e "${YELLOW}[update]${NC} $*"; }
die()  { echo -e "${RED}[update] ERROR: $*\033[0m" >&2; exit 1; }

PRUNE=false
for arg in "$@"; do
  case "$arg" in
    --prune) PRUNE=true ;;
    *) warn "未知参数: $arg" ;;
  esac
done

docker info >/dev/null 2>&1 || die "docker daemon 未运行"
if docker compose version >/dev/null 2>&1; then
  DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
  DC="docker-compose"
else
  die "未找到 docker compose v2 或 docker-compose"
fi

# ---- 1. 拉取最新代码 ----
cd "$REPO_DIR"
info "git pull ..."
git pull --rebase

# ---- 1.5 同步 .dockerignore (构建上下文=工作区根目录) ----
[ -f "$REPO_DIR/.dockerignore" ] || die "仓库缺少 .dockerignore"
if ! cp -f "$REPO_DIR/.dockerignore" "$WORKSPACE_ROOT/.dockerignore"; then
  warn "无法写入 $WORKSPACE_ROOT/.dockerignore, 构建上下文可能包含多余文件"
fi

# ---- 2. 构建 ----
info "构建应用镜像 (基础镜像需已存在) ..."
$DC -f "$COMPOSE_BASE" -f "$COMPOSE_PROD" build

# ---- 3. 重建并重启容器 ----
info "重建容器 ..."
$DC -f "$COMPOSE_BASE" -f "$COMPOSE_PROD" up -d --force-recreate

# ---- 3.5 数据目录属主 (bind mount 目录可能存在新子目录) ----
chown_data() {
  if [ "$(id -u)" -eq 0 ]; then
    chown -R "$APP_UID:$APP_UID" "$1"
  elif command -v sudo >/dev/null 2>&1; then
    sudo chown -R "$APP_UID:$APP_UID" "$1" || warn "请手动执行: sudo chown -R $APP_UID:$APP_UID $1"
  else
    warn "非 root 且无 sudo, 无法设置 $1 属主"
  fi
}
if [ -d "$DATA_DIR/training-runtime" ]; then
  chown_data "$DATA_DIR/training-runtime"
fi

# ---- 4. 健康检查 ----
info "等待服务就绪 ..."
for i in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
    echo
    info "更新完成: http://服务器IP:$PORT"
    curl -s "http://127.0.0.1:$PORT/api/health"; echo
    if [ "$PRUNE" = true ]; then
      docker image prune -f >/dev/null 2>&1 && info "已清理悬空镜像" || warn "镜像清理失败"
    fi
    exit 0
  fi
  sleep 3
done
die "健康检查超时, 排查: docker logs --tail 50 training-center"
