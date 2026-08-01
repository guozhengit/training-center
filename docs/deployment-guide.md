# Training Center 启动与部署指南

本文档偏本地开发与通用启动说明。服务器部署请优先参考：

- [Linux 服务器部署手册](server-deployment-linux.md)
- [Docker 容器化部署手册](docker-deployment-linux.md)
- [静态资源提交策略](static-resource-policy.md)

## 1. 环境要求

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 17+ | 后端编译、运行、Java 题判题 |
| Maven | 3.9+ | 后端构建和 Java 题测试 |
| Node.js | 20+ | Vue 前端构建 |
| Python | 3.10+ | Python 题判题 |
| Git | 2.x | 版本管理 |

本机推荐路径：

```text
JDK    D:\jdk\jdk-17.0.12
Maven  D:\Program Files (x86)\apache-maven-3.9.9
```

## 2. 工作区结构

应用会通过 `TRAINING_WORKSPACE` 或自动向上查找定位工作区根目录。有效工作区需要同时存在：

- `training-center/config/`
- `output/coding-ai-exam/catalog/questions.json`

推荐结构：

```text
D:/AI-SOURCE/jiupainews/
├─ training-center/
│  ├─ config/
│  ├─ starters/
│  ├─ training-core/
│  ├─ training-cli/
│  ├─ training-web/
│  ├─ training-ui/
│  └─ deploy/
└─ output/
   ├─ coding-ai-exam/
   ├─ interview/
   └─ training-runtime/
```

## 3. 后端启动

### 3.1 编译与测试

```powershell
cd D:\AI-SOURCE\jiupainews\training-center
$env:JAVA_HOME = 'D:\jdk\jdk-17.0.12'
$env:Path = 'D:\jdk\jdk-17.0.12\bin;' + $env:Path
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' test
```

全量测试包含真实沙箱矩阵，通常需要 5 分钟左右。

### 3.2 开发启动

```powershell
cd D:\AI-SOURCE\jiupainews\training-center
$env:JAVA_HOME = 'D:\jdk\jdk-17.0.12'
$env:Path = 'D:\jdk\jdk-17.0.12\bin;' + $env:Path
$env:TRAINING_WORKSPACE = 'D:\AI-SOURCE\jiupainews'
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -pl training-web -am spring-boot:run
```

启动后访问：

- Web 面板：http://localhost:8080
- API 健康检查：http://localhost:8080/api/health
- Swagger：http://localhost:8080/api/swagger-ui.html

### 3.3 打包运行

```powershell
cd D:\AI-SOURCE\jiupainews\training-center
npm --prefix training-ui run build
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -pl training-web -am -Pfrontend -DskipTests package
java -jar training-web\target\training-web-1.0.0-SNAPSHOT.jar
```

## 4. 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `TRAINING_WORKSPACE` | 自动查找 | 工作区根目录 |
| `TRAINING_JDK_HOME` | `D:/jdk/jdk-17.0.12` | 判题使用的 JDK |
| `TRAINING_MAVEN_HOME` | `D:/Program Files (x86)/apache-maven-3.9.9` | 判题使用的 Maven |
| `TRAINING_PYTHON` | `python` | Python 可执行文件 |
| `SERVER_PORT` | `8080` | 服务端口 |

## 5. 前端开发

```powershell
cd D:\AI-SOURCE\jiupainews\training-center\training-ui
npm install
npm run dev
```

访问 http://localhost:5173。Vite 开发服务器会把 `/api` 代理到 `http://localhost:8080`。

生产构建：

```powershell
npm run test:run
npm run build
```

## 6. CLI 工具

```powershell
cd D:\AI-SOURCE\jiupainews\training-center
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -pl training-cli -am package
java -jar training-cli\target\training-cli-1.0.0-SNAPSHOT.jar --help
```

当前可用命令：

- `doctor`：检查本地环境。
- `catalog`：加载并筛选题库。
- `migrate`：初始化或迁移 SQLite 数据库。

## 7. Docker Compose 部署

从 `jiupainews` 根目录构建镜像，因为 Dockerfile 需要读取 `output/coding-ai-exam`：

先构建基础镜像（JDK + Maven + Python + pytest），再构建应用镜像：

```powershell
cd D:\AI-SOURCE\jiupainews
docker build -f training-center/deploy/base/Dockerfile -t training-center-base:17 .
docker build -f training-center/Dockerfile -t training-center:latest .
```

启动：

```powershell
cd D:\AI-SOURCE\jiupainews\training-center\deploy
docker compose up -d
```

Compose 默认：

- 映射端口：`8080:8080`
- 挂载数据卷：`training-runtime` 到 `/workspace/output/training-runtime`
- 健康检查：`/api/health`
- 重启策略：`unless-stopped`
- `init: true`（PID 1 回收判题子进程）

## 8. 常见问题

### Cannot locate workspace root

确认从 `training-center` 或其子目录启动，且上级目录存在 `output/coding-ai-exam/catalog/questions.json`。也可以显式设置：

```powershell
$env:TRAINING_WORKSPACE = 'D:\AI-SOURCE\jiupainews'
```

### 判题返回环境错误

检查：

- `TRAINING_JDK_HOME` 是否指向有效 JDK。
- `TRAINING_MAVEN_HOME` 是否指向有效 Maven。
- `TRAINING_PYTHON` 是否能执行。

### Docker 构建找不到题库

构建上下文必须是 `jiupainews` 根目录，不能直接用 `training-center` 子目录作为上下文。
