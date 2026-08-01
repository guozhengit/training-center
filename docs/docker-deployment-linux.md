# Training Center Docker 容器化部署手册

本文档面向服务器已安装 Docker 的部署场景。推荐使用仓库内置部署工具包 `deploy/linux/`，一条命令完成部署。

默认服务器目录：

```bash
/home/docker
```

默认项目目录：

```bash
/home/docker/training-center
```

## 1. 部署目标

使用 Docker 构建并运行 `training-center`，容器内包含：

- JDK 17 + Maven + Python + pytest（判题运行环境，来自基础镜像 `training-center-base:17`）
- Spring Boot Web 服务
- 已打包的 Vue 前端静态资源
- 只读题库 `output/coding-ai-exam` + 面试资料 `output/interview`

推荐部署方式：

```text
两个镜像 (base + app) + 宿主机数据卷 + 8080 端口
```

## 2. 目录要求

Dockerfile 的 build context 必须是工作区根目录 `/home/docker`，构建时会复制：

```bash
training-center/           # 仓库
output/coding-ai-exam/     # 题库 (判题)
output/interview/          # 面试资料 (口述/项目题)
```

推荐目录：

```bash
/home/docker/
├── training-center/          # 本仓库
├── output/
│   ├── coding-ai-exam/       # 题库 (必须)
│   └── interview/            # 面试资料 (必须)
├── training-data/            # 运行数据 (deploy.sh 自动创建)
└── backups/                  # backup.sh 输出目录
```

检查：

```bash
cd /home/docker
ls training-center
ls output/coding-ai-exam/catalog/questions.json
ls output/interview
```

`output/coding-ai-exam` 或 `output/interview` 缺失时，[catalog] 会出现 Missing source root 报错。

> 两份数据的同步方式不同：
>
> - `training-center/` 在 git 内，`git pull` 即可拿到最新代码与 `config/` 索引（含导入索引）
> - `output/` 不在 git 内，构建镜像时烘焙进镜像；本地改动需用 `deploy/sync-data.ps1` 推送到服务器

## 3. Docker daemon 优化（推荐先做）

国内网络拉取镜像建议配置镜像加速，同时开启日志滚动防止磁盘被日志占满：

```bash
sudo cp training-center/deploy/linux/docker-daemon.json /etc/docker/daemon.json
sudo systemctl restart docker
```

包含：registry 镜像加速、容器日志滚动（单容器 10MB x 3）、BuildKit 缓存自动回收（上限 20GB）。

## 4. 一键部署

先在本地开发机把题库与面试资料推送到服务器（`output/` 不在 git 内）：

```powershell
# 本地 Windows (OpenSSH 客户端, 需已配置免密登录)
.\deploy\sync-data.ps1 -Server user@<服务器IP>
```

然后在服务器上执行：

```bash
cd /home/docker
chmod +x training-center/deploy/linux/*.sh
./training-center/deploy/linux/deploy.sh
```

脚本自动完成：

1. 前置检查（docker、题库、面试资料、磁盘、端口 8080）
2. 创建数据目录并设置属主（容器非 root 用户 uid 1000 运行）
3. 构建基础镜像 `training-center-base:17`（已存在则跳过）
4. 构建应用镜像 `training-center:latest`
5. compose + prod override 启动（数据卷 bind mount 到 `/home/docker/training-data`）
6. 健康检查并输出访问地址

## 5. 手动构建镜像

如要手动执行：

### 5.1 构建基础镜像（JDK + Maven + Python + pytest）

```bash
cd /home/docker
docker build -f training-center/deploy/base/Dockerfile -t training-center-base:17 .
```

构建期自检中间件（任一缺失直接构建失败）。中间件升级只需重建此镜像一次。

### 5.2 构建应用镜像

```bash
cd /home/docker
docker build -f training-center/Dockerfile -t training-center:latest .
```

> `.dockerignore` 位于工作区根目录 `/home/docker/.dockerignore`（Docker 只在 context 根目录读取它），用于排除 `output/doc`、`node_modules` 等加速构建传输。

## 6. 启动

### 6.1 docker compose（推荐）

生产环境使用 compose + prod override（bind mount）：

```bash
cd /home/docker
docker compose \
  -f training-center/deploy/docker-compose.yml \
  -f training-center/deploy/linux/docker-compose.prod.yml \
  up -d
```

### 6.2 docker run

```bash
docker rm -f training-center 2>/dev/null || true

docker run -d \
  --name training-center \
  --init \
  -p 8080:8080 \
  -e TZ=Asia/Shanghai \
  -e TRAINING_WORKSPACE=/workspace \
  -e TRAINING_JDK_HOME=/opt/java/openjdk \
  -e TRAINING_MAVEN_HOME=/opt/maven \
  -e TRAINING_PYTHON=python3 \
  -e SERVER_PORT=8080 \
  -e JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Duser.timezone=Asia/Shanghai" \
  -v /home/docker/training-data/training-runtime:/workspace/output/training-runtime \
  --restart unless-stopped \
  training-center:latest
```

## 7. 验证服务

```bash
docker ps | grep training-center
docker logs -f training-center
curl http://127.0.0.1:8080/api/health
```

期望返回：

```json
{"status":"UP"}
```

浏览器访问 `http://服务器IP:8080`。外网无法访问时，检查云安全组、防火墙和端口映射。

## 8. 更新部署

```bash
cd /home/docker
./training-center/deploy/linux/update.sh
```

脚本流程：`git pull` → 重建应用镜像 → 重建容器 → 健康检查。基础镜像未变时无需重建。

### 8.1 题目导入后的部署（重要）

`training import` 会把内容写到两个位置：

| 内容 | 位置 | 同步方式 |
|---|---|---|
| 导入索引 | `training-center/config/imported-questions.json`（git 内） | `git pull` |
| 题目内容 | `output/coding-ai-exam/`、`output/interview/imported/`（不在 git 内） | `sync-data.ps1` |

因此导入题目后需要四步，缺一不可：

```bash
# 1. 本地: 提交并推送 config/ 改动（导入索引）
git add training-center/config
git commit -m "feat: import questions"
git push

# 2. 本地 Windows: 推送 output 数据（题目内容）
.\deploy\sync-data.ps1 -Server user@<服务器IP>

# 3. 服务器: 拉取代码
cd /home/docker/training-center && git pull

# 4. 服务器: 重建并重启（update.sh 已包含 git pull，步骤 3、4 可合并）
./training-center/deploy/linux/update.sh
```

> 若只改了 `config/` 而忘记同步 `output/`，新题目的内容文件在容器内不存在，
> 运行期会报 `Missing source path`。`deploy.sh` 检测到导入索引时会给出提示。

## 9. 数据备份与恢复

运行数据位于宿主机：

```bash
/home/docker/training-data/training-runtime
```

备份（保留最近 7 份）：

```bash
./training-center/deploy/linux/backup.sh
```

建议加入 crontab 每日执行：

```cron
0 2 * * * /home/docker/training-center/deploy/linux/backup.sh
```

手动备份：

```bash
cd /home/docker
tar -czf training-data-backup-$(date +%Y%m%d_%H%M%S).tar.gz training-data
```

恢复：

```bash
tar -xzf training-data-backup-xxxx.tar.gz -C /home/docker
docker restart training-center
```

## 10. 常用运维命令

```bash
docker ps                                          # 查看容器
docker logs -f training-center                     # 查看日志
docker exec -it training-center bash               # 进入容器
docker exec -it training-center ls /workspace      # 查看工作区
docker inspect --format='{{json .State.Health}}' training-center   # 健康状态
docker stop training-center                        # 停止
docker rm training-center                          # 删除容器
```

## 11. 强制重建基础镜像（中间件升级）

```bash
docker image rm training-center-base:17
cd /home/docker
docker build -f training-center/deploy/base/Dockerfile -t training-center-base:17 .
./training-center/deploy/linux/update.sh
```

## 12. 常见问题

### 12.1 [catalog] Missing interview source root

`output/interview` 未打进镜像或目录缺失。确认宿主机存在 `output/interview` 后重新 `deploy.sh`。

### 12.2 数据目录没有写权限

容器以 uid 1000 运行，bind mount 目录需 1000 属主：

```bash
sudo chown -R 1000:1000 /home/docker/training-data/training-runtime
```

### 12.3 外部打不开页面

```bash
curl http://127.0.0.1:8080/api/health   # 本机是否正常
ss -lntp | grep 8080
```

本机正常外部不通，通常是云安全组或防火墙未放行 8080。

### 12.4 页面还是旧版本

执行 `./training-center/deploy/linux/update.sh` 重建并重启。
