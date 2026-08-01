#!/bin/bash
# ============================================================
# 构建 Training Center 基础镜像 (JDK 17 + Maven + Python + pytest)
#
# 用法:
#   ./deploy/base/build.sh                 # 默认版本
#   MAVEN_VERSION=3.9.9 ./deploy/base/build.sh
#
# 必须在工作区根目录 (jiupai, 含 training-center/ 与 output/) 下执行
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

cd "${WORKSPACE_ROOT}"

IMAGE_NAME="${IMAGE_NAME:-training-center-base:17}"
MAVEN_VERSION="${MAVEN_VERSION:-3.9.9}"

echo "==> 构建基础镜像: ${IMAGE_NAME}"
echo "==> Maven 版本:   ${MAVEN_VERSION}"
echo "==> 工作区根目录: ${WORKSPACE_ROOT}"

docker build \
    -f training-center/deploy/base/Dockerfile \
    -t "${IMAGE_NAME}" \
    --build-arg MAVEN_VERSION="${MAVEN_VERSION}" \
    .

echo "==> 完成: docker images | grep training-center-base"
