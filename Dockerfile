# ============================================================
# Stage 1: Build frontend
# ============================================================
FROM node:20-alpine AS frontend-build

WORKDIR /app/training-ui
COPY training-center/training-ui/package.json training-center/training-ui/package-lock.json ./
RUN npm ci --silent
COPY training-center/training-ui/ ./
RUN npx vite build

# ============================================================
# Stage 2: Build backend (fat JAR with frontend embedded)
# ============================================================
FROM eclipse-temurin:17-jdk AS backend-build

WORKDIR /app
COPY training-center/pom.xml ./
COPY training-center/training-core/pom.xml training-core/
COPY training-center/training-cli/pom.xml training-cli/
COPY training-center/training-web/pom.xml training-web/

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
# Stage 3: Runtime (JDK + Maven + Python for judging)
# ============================================================
FROM eclipse-temurin:17-jdk AS runtime

LABEL maintainer="guoyongzheng"
LABEL description="Interview Training Center - coding/oral/project practice platform"

# Install Maven + Python + pytest
ARG MAVEN_VERSION=3.9.9
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv \
        curl ca-certificates \
    && pip3 install --no-cache-dir --break-system-packages pytest \
    && curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
        | tar -xz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# Create app user (avoid running as root)
RUN useradd -m -s /bin/bash training

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

# Create mutable runtime directories
RUN mkdir -p output/training-runtime/database \
             output/training-runtime/sandboxes \
             output/training-runtime/exports \
             output/training-runtime/logs \
    && chown -R training:training output/training-runtime

# Copy the application JAR
COPY --from=backend-build --chown=training:training \
    /app/training-web/target/training-web-1.0.0-SNAPSHOT.jar /app/training-web.jar

USER training

# Environment defaults for Linux container
ENV TRAINING_WORKSPACE=/workspace
ENV TRAINING_JDK_HOME=${JAVA_HOME}
ENV TRAINING_MAVEN_HOME=/opt/maven
ENV TRAINING_PYTHON=python3
ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -sf http://localhost:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/training-web.jar"]
