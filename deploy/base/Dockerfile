# ============================================================
# Training Center 基础镜像
#
# 包含判题运行所需全部中间件:
#   - JDK 17   (Java 判题 / Spring Boot 运行时)
#   - Maven    (Java 题单元测试)
#   - Python3  (Python 题判题)
#   - pytest   (Python 判题 runner)
#   - curl     (容器健康检查)
#
# 构建命令 (在工作区根目录执行):
#   docker build -f training-center/deploy/base/Dockerfile -t training-center-base:17 .
#
# 独立成基础镜像的理由:
#   - JDK/Maven/Python 等中间件升级频率远低于应用代码
#   - 应用镜像构建不再依赖 archive.apache.org 等外部网络
#   - 同一基础镜像可被应用镜像与 CI 复用
#   - 换基础镜像版本时只需重构建一次, 应用构建走本地缓存
# ============================================================
FROM eclipse-temurin:17-jdk

LABEL maintainer="guoyongzheng"
LABEL description="Training Center runtime base: JDK 17 + Maven + Python3 + pytest"

# Maven 版本, 可在构建时通过 --build-arg MAVEN_VERSION=3.9.9 覆盖
ARG MAVEN_VERSION=3.9.9
# Maven 下载地址 (默认华为云镜像, 国内快; 失败自动回退官方源)
ARG MAVEN_URL=https://repo.huaweicloud.com/apache/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz
# pip 源 (默认阿里云, 国内快)
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

# Python 判题环境 + curl(健康检查) + Maven
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv \
        curl ca-certificates \
    && pip3 install --no-cache-dir --break-system-packages \
        -i "${PIP_INDEX_URL}" pytest \
    && { curl -fsSL "${MAVEN_URL}" \
            || curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; } \
        | tar -xz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}" /opt/maven \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

# 构建期自检, 中间件缺失/损坏时构建失败, 而不是运行期才暴露
RUN mvn -version \
    && python3 --version \
    && python3 -m pytest --version
