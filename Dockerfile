# ============================================================
# Training Center 应用镜像
#
# 构建上下文必须为工作区根目录 (jiupai 根目录), 需包含:
#   - training-center/          (本仓库)
#   - output/coding-ai-exam/    (只读题库, 判题资源)
#
# 运行时基础镜像 (JDK 17 + Maven + Python + pytest):
#   - 镜像: training-center-base:17
#   - 构建: docker build -f training-center/deploy/base/Dockerfile -t training-center-base:17 .
#   - 可用 --build-arg BASE_IMAGE=... 覆盖
# ============================================================

# ---- 运行时基础镜像 (可从构建命令覆盖) ----
ARG BASE_IMAGE=training-center-base:17

# ============================================================
# Stage 1: Build frontend
# ============================================================
FROM node:20-alpine AS frontend-build

WORKDIR /app/training-ui
COPY training-center/training-ui/package.json training-center/training-ui/package-lock.json ./
COPY training-center/deploy/npm/.npmrc /root/.npmrc
RUN npm ci --silent
COPY training-center/training-ui/ ./
RUN npx vite build

# ============================================================
# Stage 2: Build backend (fat JAR with frontend embedded)
# 使用基础镜像做构建环境 (JDK + Maven 已就绪), 不再额外拉 eclipse-temurin
# ============================================================
ARG BASE_IMAGE=training-center-base:17
FROM ${BASE_IMAGE} AS backend-build

WORKDIR /app
COPY training-center/pom.xml ./
COPY training-center/training-core/pom.xml training-core/
COPY training-center/training-cli/pom.xml training-cli/
COPY training-center/training-web/pom.xml training-web/

# 阿里云 Maven 镜像, 加速依赖下载 (可替换为自己的 settings.xml)
COPY training-center/deploy/maven/settings.xml /root/.m2/settings.xml

# Download dependencies (cached layer)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline -pl training-core,training-web -am -q || true

COPY training-center/training-core/ training-core/
COPY training-center/training-cli/ training-cli/
COPY training-center/training-web/ training-web/

# Copy frontend dist into static resources
COPY --from=frontend-build /app/training-ui/dist/ training-web/src/main/resources/static/

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B package -pl training-web -am -DskipTests -q

# ============================================================
# Stage 3: Runtime (基于 training-center-base 基础镜像)
# ============================================================
ARG BASE_IMAGE=training-center-base:17
FROM ${BASE_IMAGE} AS runtime

LABEL maintainer="guoyongzheng"
LABEL description="Interview Training Center - coding/oral/project practice platform"

# 创建应用用户 (避免以 root 运行), 固定 uid 1000 便于 bind mount 属主设置
# 基础镜像自带 uid 1000 的 ubuntu 用户, 先移除避免冲突
RUN userdel -r ubuntu 2>/dev/null || true \
    && useradd -m -s /bin/bash -u 1000 training

# Workspace layout:
#   /workspace/training-center/config/       ← read-only question indexes
#   /workspace/training-center/starters/     ← read-only starter code
#   /workspace/output/coding-ai-exam/        ← read-only reference answers + tests
#   /workspace/output/training-runtime/      ← MUTABLE: database, sandboxes, exports, logs
WORKDIR /workspace

# Copy read-only content (baked into image)
COPY --chown=training:training training-center/config/ training-center/config/
COPY --chown=training:training training-center/starters/ training-center/starters/
COPY --chown=training:training output/coding-ai-exam/ output/coding-ai-exam/
COPY --chown=training:training output/interview/ output/interview/

# Create mutable runtime directories
RUN mkdir -p output/training-runtime/database \
             output/training-runtime/sandboxes \
             output/training-runtime/exports \
             output/training-runtime/logs \
    && chown -R training:training output/training-runtime

# 预热判题 Maven 依赖缓存到 training 用户 .m2
# 冷缓存首次判题需下载 JUnit/spring-boot parent 等, 耗时超过 90s 判题超时;
# 这里用参考答案项目真实执行一次 mvn test: 既预置缓存, 又自检题库可编译通过
# (构建期失败则暴露题库损坏, 而不是运行期才暴露)
RUN mkdir -p /home/training/.m2 \
    && chown -R training:training /home/training/.m2 \
    && cd /workspace/output/coding-ai-exam/java \
    && su training -c "HOME=/home/training /opt/maven/bin/mvn -B -ntp -q -Dmaven.repo.local=/home/training/.m2/repository test" \
    && rm -rf /workspace/output/coding-ai-exam/java/target

# 应用版本, 与 pom.xml 保持一致, 升级时通过 --build-arg TRAINING_VERSION=... 覆盖
ARG TRAINING_VERSION=1.0.0-SNAPSHOT

# Copy the application JAR
COPY --from=backend-build --chown=training:training \
    /app/training-web/target/training-web-${TRAINING_VERSION}.jar /app/training-web.jar

USER training

# Environment defaults for Linux container
ENV TRAINING_WORKSPACE=/workspace
ENV TRAINING_JDK_HOME=${JAVA_HOME}
ENV TRAINING_MAVEN_HOME=/opt/maven
ENV TRAINING_PYTHON=python3
ENV TRAINING_SANDBOX_RETENTION_HOURS=72
ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -sf http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/training-web.jar"]
