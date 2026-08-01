#!/bin/bash
# ============================================================
# training-center 更新部署脚本 (Linux)
#
# 流程: git pull -> 重建应用镜像 -> 重建容器 -> 健康检查
# 基础镜像通常无需重建 (中间件未变), 需要时先手动重建。
#
# 用法:
#   ./training-center/deploy/linux/update.sh
# ============================================================
set -euo pipefail

WORKSPACE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
REPO_DIR="$WORKSPACE_ROOT/training-center"
COMPOSE_BASE="$REPO_DIR/deploy/docker-compose.yml"
COMPOSE_PROD="$REPO_DIR/deploy/linux/docker-compose.prod.yml"
PORT=8080

info() { echo -e "\033[0;32m[update]\033[0m $*"; }
die()  { echo -e "\033[0;31m[update] ERROR: $*\033[0m" >&2; exit 1; }

docker info >/dev/null 2>&1 || die "docker daemon 未运行"

# ---- 1. 拉取最新代码 ----
cd "$REPO_DIR"
info "git pull ..."
git pull --rebase

# ---- 1.5 同步 .dockerignore (构建上下文=工作区根目录) ----
[ -f "$REPO_DIR/.dockerignore" ] || die "仓库缺少 .dockerignore"
cp -f "$REPO_DIR/.dockerignore" "$WORKSPACE_ROOT/.dockerignore"

# ---- 2. 构建 ----
info "构建应用镜像 (基础镜像需已存在) ..."
docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_PROD" build

# ---- 3. 重建并重启容器 ----
info "重建容器 ..."
docker compose -f "$COMPOSE_BASE" -f "$COMPOSE_PROD" up -d --force-recreate

# ---- 4. 健康检查 ----
info "等待服务就绪 ..."
for i in $(seq 1 40); do
  if curl -sf "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; then
    echo
    info "更新完成: http://服务器IP:$PORT"
    curl -s "http://127.0.0.1:$PORT/api/health"; echo
    exit 0
  fi
  sleep 3
done
die "健康检查超时, 排查: docker logs --tail 50 training-center"
