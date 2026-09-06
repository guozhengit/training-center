# Interview Training Center

本项目是一个本地面试训练中心，用于机试题、口述题和项目答辩训练。

它采用 Java 17 + Maven 多模块后端、Spring Boot Web API、Vue 3 前端和 SQLite 本地数据库。只读题库来自 `output/coding-ai-exam` 与 `output/interview`，运行期数据统一写入 `output/training-runtime`。

## 模块结构

```text
training-center/
├─ training-core/   # 题库、SQLite、复习队列、沙箱、判题 runner
├─ training-cli/    # 离线诊断与命令行工具
├─ training-web/    # Spring Boot API 与静态前端托管
├─ training-ui/     # Vue 3 可视化训练面板
├─ starters/        # 训练用 starter 代码，故意保留 TODO
├─ config/          # 口述题、项目题、OD 题与 starter 映射
├─ docs/            # 架构和部署说明
└─ deploy/          # Docker Compose 配置
```

## 环境要求

- JDK 17：`D:\jdk\jdk-17.0.12`
- Maven 3.9.9：`D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd`
- Node.js 20+
- Python 3.10+，用于 Python 题判题

## 后端测试

在仓库根目录 `D:\AI-SOURCE\jiupainews` 或 `training-center` 目录执行：

```powershell
$env:JAVA_HOME = 'D:\jdk\jdk-17.0.12'
$env:Path = 'D:\jdk\jdk-17.0.12\bin;' + $env:Path
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' test
```

说明：全量 Maven 测试包含真实沙箱矩阵，通常需要 5 分钟左右。

## 前端测试与构建

```powershell
cd D:\AI-SOURCE\jiupainews\training-center\training-ui
npm install
npm run test:run
npm run build
```

## 启动 Web 面板

后端：

```powershell
cd D:\AI-SOURCE\jiupainews\training-center
$env:JAVA_HOME = 'D:\jdk\jdk-17.0.12'
$env:Path = 'D:\jdk\jdk-17.0.12\bin;' + $env:Path
$env:TRAINING_WORKSPACE = 'D:\AI-SOURCE\jiupainews'
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -pl training-core -am -DskipTests install
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -f training-web\pom.xml spring-boot:run
```

访问：

- Web 面板：http://localhost:8080
- 健康检查：http://localhost:8080/api/health
- Swagger：http://localhost:8080/api/swagger-ui.html

前端开发模式：

```powershell
cd D:\AI-SOURCE\jiupainews\training-center\training-ui
npm run dev
```

开发模式下访问 http://localhost:5173，Vite 会把 `/api` 代理到 `localhost:8080`。

如需开启轻量 API Key 鉴权，后端设置 `TRAINING_API_KEY`；前端开发或构建时设置
`VITE_TRAINING_API_KEY`，也可以在浏览器 `localStorage` 写入 `training:apiKey`。
这是本地工具级别保护，不替代正式多用户登录。

## CLI

```powershell
cd D:\AI-SOURCE\jiupainews\training-center
$env:JAVA_HOME = 'D:\jdk\jdk-17.0.12'
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -pl training-cli -am package
java -jar training-cli\target\training-cli-1.0.0-SNAPSHOT.jar --help
```

当前 CLI 已提供环境诊断、题库检查、数据库迁移、训练会话创建、提交/判题记录、历史查看与导出。

## Linux 服务器部署

推荐使用 `deploy/linux/` 工具包一键部署（含基础镜像构建、compose、备份脚本）：

```bash
cd /home/docker/training-center
cp deploy/linux/.env.example deploy/linux/.env
chmod 600 deploy/linux/.env
# 编辑生产配置后执行；需要 Docker Compose 2.20+
bash deploy/linux/deploy.sh
```

更新使用 `bash deploy/linux/update.sh`：构建版本镜像 → 停写备份 → 重建容器 → 等待就绪。
发布记录保存在数据目录 `releases.log`；数据库迁移后的降级需同时恢复匹配备份。

详见 [docs/docker-deployment-linux.md](docs/docker-deployment-linux.md) 和 [deploy/linux/README.md](deploy/linux/README.md)。

## 数据边界

- 只读题库：`output/coding-ai-exam`、`output/interview`
- 可写运行期：`output/training-runtime`
- Web 判题沙箱：`output/training-runtime/sandboxes/web-judge`
- SQLite 数据库：`output/training-runtime/database/training.db`

`starters/` 是训练用起始代码，出现 `TODO` 或未完成实现是设计预期，不代表答案库损坏。
