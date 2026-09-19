#!/usr/bin/env bash
# ============================================================
# 离线首次部署 (服务器端执行)
#
# 前置: 镜像归档 + 源码归档已传到 /home/docker/
#   /home/docker/training-center-20260914-fix2.tar.gz      (镜像, ~400MB)
#   /home/docker/training-center-src-20260914-fix2.tar.gz  (源码, 只为拿 deploy/ 脚本)
#
# 用法:
#   bash first-deploy.sh
#
# 可调 (环境变量覆盖):
#   DOCKER_HOME=/home/docker
#   IMAGE_ARCHIVE=/home/docker/training-center-20260914-fix2.tar.gz
#   SRC_ARCHIVE=/home/docker/training-center-src-20260914-fix2.tar.gz
#   TRAINING_PORT=9091          # 9090 已被 typing-game 占用
#
# 设计要点:
#   - 镜像内已烤入题库(output/coding-ai-exam + output/interview),
#     因此走 deploy.sh --image 模式, 跳过服务器侧构建与题库校验。
#   - 不需要服务器安装 JDK / Maven / Node / Python, 也不需要访问 GitHub。
# ============================================================
set -euo pipefail

# ---------------- 可调参数 ----------------
DOCKER_HOME="${DOCKER_HOME:-/home/docker}"
IMAGE_ARCHIVE="${IMAGE_ARCHIVE:-$DOCKER_HOME/training-center-20260914-fix2.tar.gz}"
SRC_ARCHIVE="${SRC_ARCHIVE:-$DOCKER_HOME/training-center-src-20260914-fix2.tar.gz}"

TRAINING_PORT="${TRAINING_PORT:-9091}"
TRAINING_BIND_ADDRESS="${TRAINING_BIND_ADDRESS:-0.0.0.0}"
TRAINING_API_KEY="${TRAINING_API_KEY:-}"
JAVA_OPTS="${JAVA_OPTS:--Xms64m -Xmx256m -XX:+UseContainerSupport -Duser.timezone=Asia/Shanghai}"
TRAINING_MEMORY_LIMIT="${TRAINING_MEMORY_LIMIT:-1G}"
TRAINING_CPU_LIMIT="${TRAINING_CPU_LIMIT:-1.0}"
TRAINING_DATA_DIR="${TRAINING_DATA_DIR:-$DOCKER_HOME/training-data}"
BACKUP_DIR="${BACKUP_DIR:-$DOCKER_HOME/backups}"
KEEP="${KEEP:-7}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-deploy}"
TRAINING_HEALTH_TIMEOUT="${TRAINING_HEALTH_TIMEOUT:-180}"
BASE_IMAGE="${BASE_IMAGE:-training-center-base:17}"

REPO_DIR="$DOCKER_HOME/training-center"

info() { printf '[offline] %s\n' "$*"; }
die()  { printf '[offline] ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------- 0. 环境检查 ----------------
info 'Step 0/6 检查环境'
command -v docker >/dev/null || die 'Docker 未安装'
docker info >/dev/null 2>&1 || die 'Docker daemon 不可用'

bash --version | head -1
if [[ -z "${BASH_VERSINFO[0]:-}" || "${BASH_VERSINFO[0]}" -lt 4 \
      || ( "${BASH_VERSINFO[0]}" -eq 4 && "${BASH_VERSINFO[1]}" -lt 3 ) ]]; then
  die "需要 Bash >= 4.3, 当前 ${BASH_VERSION:-unknown}"
fi

compose_ver="$(docker compose version --short 2>/dev/null)" || die 'Docker Compose >= 2.20 是必需的'
info "Compose $compose_ver"
[[ "$compose_ver" =~ ^v?([0-9]+)\.([0-9]+) ]] || die "无法识别 Compose 版本: $compose_ver"
(( ${BASH_REMATCH[1]} > 2 || (${BASH_REMATCH[1]} == 2 && ${BASH_REMATCH[2]} >= 20) )) \
  || die "Docker Compose >= 2.20 是必需的, 当前 $compose_ver"

[[ -f "$IMAGE_ARCHIVE" ]] || die "镜像归档不存在: $IMAGE_ARCHIVE"
[[ -f "$SRC_ARCHIVE"   ]] || die "源码归档不存在: $SRC_ARCHIVE"

# 磁盘余量: 解压 1.09G + 导入后镜像约 1.1G, 预留 4G
avail_kb="$(df -Pk "$DOCKER_HOME" | awk 'NR==2 {print $4}')"
avail_gb=$(( avail_kb / 1024 / 1024 ))
info "磁盘可用: ${avail_gb}G"
(( avail_gb >= 4 )) || die "磁盘可用空间不足 4G (当前 ${avail_gb}G)"

# ---------------- 1. 导入镜像 ----------------
info 'Step 1/6 导入镜像 (约 1~3 分钟)'
load_out="$(gunzip -c "$IMAGE_ARCHIVE" | docker load 2>&1)" || die 'docker load 失败'
printf '%s\n' "$load_out"

IMAGE_TAG="$(printf '%s\n' "$load_out" | sed -n 's/^Loaded image: //p' | tail -1)"
[[ -n "$IMAGE_TAG" ]] || die '无法从 docker load 输出解析镜像标签'
docker image inspect "$IMAGE_TAG" >/dev/null 2>&1 || die "镜像导入后不可用: $IMAGE_TAG"
info "镜像就绪: $IMAGE_TAG"

# ---------------- 2. 展开源码 ----------------
info 'Step 2/6 展开源码 (只需要 deploy/ 脚本)'
if [[ -d "$REPO_DIR" ]] && [[ -n "$(find "$REPO_DIR" -mindepth 1 -print -quit 2>/dev/null)" ]]; then
  stamp="$(date -u +%Y%m%dT%H%M%S)"
  info "目录非空, 备份为 $REPO_DIR.bak-$stamp"
  mv -- "$REPO_DIR" "$REPO_DIR.bak-$stamp"
fi
mkdir -p "$REPO_DIR"
tar -xzf "$SRC_ARCHIVE" -C "$DOCKER_HOME"
[[ -f "$REPO_DIR/deploy/linux/deploy.sh"   ]] || die "解压后缺少 deploy/linux/deploy.sh"
[[ -f "$REPO_DIR/deploy/linux/common.sh"   ]] || die "解压后缺少 deploy/linux/common.sh (新版脚本的命脉)"
[[ -f "$REPO_DIR/deploy/docker-compose.yml" ]] || die "解压后缺少 deploy/docker-compose.yml"
find "$REPO_DIR/deploy" -name '*.sh' -exec chmod +x {} +
info '部署脚本就位'

# ---------------- 3. 数据目录 ----------------
info 'Step 3/6 准备数据目录 (应用以 uid 1000 运行)'
mkdir -p "$TRAINING_DATA_DIR/training-runtime"/{database,sandboxes,exports,logs}
mkdir -p "$BACKUP_DIR"
if [[ "$(id -u)" -eq 0 ]]; then
  chown -R 1000:1000 "$TRAINING_DATA_DIR" "$BACKUP_DIR"
elif command -v sudo >/dev/null; then
  sudo -n chown -R 1000:1000 "$TRAINING_DATA_DIR" "$BACKUP_DIR" \
    || die "无法设置属主, 请手动: sudo chown -R 1000:1000 $TRAINING_DATA_DIR $BACKUP_DIR"
else
  die "需要 root 或 sudo 来设置 uid 1000 属主"
fi
info "数据目录: $TRAINING_DATA_DIR/training-runtime"

# ---------------- 4. 写生产配置 ----------------
info 'Step 4/6 写入 deploy/linux/.env'
cat > "$REPO_DIR/deploy/linux/.env" <<EOF
TRAINING_DATA_DIR=$TRAINING_DATA_DIR
BACKUP_DIR=$BACKUP_DIR
KEEP=$KEEP
TRAINING_PORT=$TRAINING_PORT
TRAINING_BIND_ADDRESS=$TRAINING_BIND_ADDRESS
TRAINING_API_KEY=$TRAINING_API_KEY
JAVA_OPTS=$JAVA_OPTS
TRAINING_MEMORY_LIMIT=$TRAINING_MEMORY_LIMIT
TRAINING_CPU_LIMIT=$TRAINING_CPU_LIMIT
BASE_IMAGE=$BASE_IMAGE
COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME
TRAINING_HEALTH_TIMEOUT=$TRAINING_HEALTH_TIMEOUT
EOF
chmod 600 "$REPO_DIR/deploy/linux/.env"
info "端口 $TRAINING_PORT, 内存上限 $TRAINING_MEMORY_LIMIT, CPU $TRAINING_CPU_LIMIT"

# ---------------- 5. 部署 ----------------
info "Step 5/6 启动容器 (使用已有镜像, 不构建)"
cd "$REPO_DIR"
bash deploy/linux/deploy.sh --image "$IMAGE_TAG"

# ---------------- 6. 验收 ----------------
info 'Step 6/6 验收'
docker inspect --format='健康状态: {{.State.Status}} / {{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' training-center
printf '健康检查: '
if curl -fsS --max-time 10 "http://127.0.0.1:${TRAINING_PORT}/api/health"; then
  printf '\n'
else
  printf '失败\n'
fi
docker stats --no-stream training-center

cat <<EOF2

[offline] 部署完成
  访问地址:  http://<服务器IP>:$TRAINING_PORT/
  发布记录:  $TRAINING_DATA_DIR/releases.log
  容器日志:  docker logs --tail 100 training-center

  清理归档可释放约 360MB:
    rm -f "$IMAGE_ARCHIVE" "$SRC_ARCHIVE"

  注意: 当前 1G 内存上限 + 256m 堆, 只能单路判题, 请勿并发。
EOF2
