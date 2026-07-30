## Training Center 启动与部署指南

### 一、环境要求

| 组件 | 版本要求 | 用途 |
|------|----------|------|
| JDK | 17+ | 后端编译与运行 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20+ | 前端构建与开发服务器 |
| Python | 3.10+ | 判题系统（pytest 驱动） |
| Git | 2.x | 版本管理 |

### 二、工作区目录结构

应用通过 `WorkspaceLocator` 自动定位工作区根目录。根目录下必须同时存在 `training-center/config/` 目录和 `output/coding-ai-exam/catalog/questions.json` 文件：

```
D:/AI-SOURCE/jiupainews/           ← 工作区根目录
├── training-center/               ← 项目本体（Git 仓库）
│   ├── config/                    ← 只读题库索引
│   ├── starters/                  ← 只读起始代码
│   ├── training-core/             ← 核心领域逻辑
│   ├── training-cli/              ← 离线 CLI 工具
│   ├── training-web/              ← Spring Boot 后端
│   ├── training-ui/               ← Vue 3 前端
│   ├── deploy/                    ← 部署配置
│   └── Dockerfile
└── output/
    ├── coding-ai-exam/            ← 只读参考答案 + 测试
    ├── interview/                 ← 口述/项目题源文件
    └── training-runtime/          ← 可写运行时（数据库、沙箱、日志）
```

### 三、后端启动

#### 3.1 编译

```bash
cd D:/AI-SOURCE/jiupainews/training-center

# Windows 环境需指定 JAVA_HOME 和 Maven 路径
set JAVA_HOME=D:\jdk\jdk-17.0.12
"D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn" compile -q
```

Linux / macOS：

```bash
export JAVA_HOME=/path/to/jdk-17
mvn compile -q
```

#### 3.2 运行

```bash
# 方式一：Maven 直接启动（开发推荐）
mvn spring-boot:run -pl training-web

# 方式二：打包后运行 fat JAR
mvn package -pl training-web -am -DskipTests -q
java -jar training-web/target/training-web-1.0.0-SNAPSHOT.jar
```

启动后监听 **http://localhost:8080**。

#### 3.3 关键环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `TRAINING_WORKSPACE` | 空（自动向上查找） | 工作区根目录 |
| `TRAINING_JDK_HOME` | `D:/jdk/jdk-17.0.12` | 判题使用的 JDK |
| `TRAINING_MAVEN_HOME` | `D:/Program Files (x86)/apache-maven-3.9.9` | 判题使用的 Maven |
| `TRAINING_PYTHON` | `python` | Python 可执行文件路径 |
| `TRAINING_API_KEY` | 空（不启用鉴权） | 生产环境 API 密钥 |
| `SERVER_PORT` | `8080` | 服务端口 |

#### 3.4 验证

```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/api/dashboard/catalog-summary
```

Swagger 文档：http://localhost:8080/api/swagger-ui.html

### 四、前端启动

#### 4.1 安装依赖

```bash
cd training-center/training-ui
npm install
```

#### 4.2 开发模式

```bash
npm run dev
```

启动 Vite 开发服务器，监听 **http://localhost:5173**，自动代理 `/api` 请求到 `http://localhost:8080`。

#### 4.3 生产构建

```bash
npm run build
```

输出到 `training-ui/dist/`。后端 Maven 有 `frontend` profile 可将 dist 复制到 `training-web/src/main/resources/static/`，实现单 JAR 部署：

```bash
mvn package -pl training-web -am -DskipTests -Pfrontend -q
```

#### 4.4 前端测试

```bash
npm run test:run    # 单次运行（CI）
npm run test        # watch 模式
```

### 五、CLI 工具

training-cli 提供离线诊断能力，无需启动 Web 服务：

```bash
mvn package -pl training-cli -am -DskipTests -q
java -jar training-cli/target/training-cli-1.0.0-SNAPSHOT.jar doctor     # 环境诊断
java -jar training-cli/target/training-cli-1.0.0-SNAPSHOT.jar catalog    # 题库检查
java -jar training-cli/target/training-cli-1.0.0-SNAPSHOT.jar migrate    # 数据库迁移
```

### 六、容器化部署

#### 6.1 架构概览

Dockerfile 采用三阶段构建：

| 阶段 | 基础镜像 | 产物 |
|------|----------|------|
| frontend-build | `node:20-alpine` | `dist/` 静态资源 |
| backend-build | `eclipse-temurin:17-jdk` | fat JAR（内嵌前端） |
| runtime | `eclipse-temurin:17-jdk` | 最终镜像（含 Maven + Python + pytest） |

运行时镜像包含判题所需的完整工具链（JDK、Maven、Python3、pytest），因为自动判题功能需要在容器内编译和执行用户代码。

#### 6.2 构建镜像

```bash
# 在 jiupainews 根目录执行（build context 需要包含 output/coding-ai-exam）
cd D:/AI-SOURCE/jiupainews
docker build -f training-center/Dockerfile -t training-center:latest .
```

构建上下文为 `jiupainews/` 根目录，因为镜像需要打包 `output/coding-ai-exam/` 参考答案。

#### 6.3 Docker Compose 部署

```bash
cd training-center/deploy
docker compose up -d
```

docker-compose.yml 配置要点：

- 端口映射：`8080:8080`
- 数据卷：`training-runtime` 挂载到 `/workspace/output/training-runtime`（持久化数据库、沙箱、导出文件）
- 资源限制：2G 内存 / 2 CPU
- 健康检查：每 30s 探测 `/api/health`
- 重启策略：`unless-stopped`

#### 6.4 生产环境配置

```yaml
# docker-compose.override.yml（生产覆盖）
services:
  training-center:
    environment:
      - TRAINING_API_KEY=your-secret-key-here
      - JAVA_OPTS=-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Xlog:gc*
    ports:
      - "80:8080"    # 或通过 Nginx 反代
    deploy:
      resources:
        limits:
          memory: 4G
          cpus: "4.0"
```

设置 `TRAINING_API_KEY` 后，所有 `/api/**` 请求需携带 `X-API-Key` 头。

#### 6.5 运维命令

```bash
# 查看日志
docker logs -f training-center

# 进入容器调试
docker exec -it training-center bash

# 重建（题库更新后）
docker compose down
docker compose build --no-cache
docker compose up -d

# 备份运行时数据
docker run --rm -v training-center_training-runtime:/data -v $(pwd):/backup \
  alpine tar czf /backup/training-runtime-backup.tar.gz /data
```

#### 6.6 .dockerignore

项目已配置 `.dockerignore`，排除 `node_modules`、`target`、`.git` 等目录以加速构建。

### 七、常见问题

**Q: 启动报 "Cannot locate workspace root"**
A: 确保从 `training-center` 目录或其子目录启动，且上级目录中存在 `output/coding-ai-exam/catalog/questions.json`。或显式设置 `TRAINING_WORKSPACE` 环境变量。

**Q: 判题返回空结果**
A: 检查 `TRAINING_JDK_HOME` 和 `TRAINING_MAVEN_HOME` 是否指向有效路径。容器内无需配置（已内置）。

**Q: 前端开发时 API 404**
A: 确认后端已在 8080 端口运行。Vite 代理配置在 `vite.config.js` 中将 `/api` 转发到 `localhost:8080`。

**Q: Docker 构建失败（copy output/coding-ai-exam）**
A: 构建上下文必须是 `jiupainews/` 根目录，不能是 `training-center/` 子目录。
