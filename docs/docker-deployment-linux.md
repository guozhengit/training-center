# Training Center 生产部署与恢复

当前采用单机 Docker Compose：基础镜像包含 JDK 17、Maven、Python、pytest；应用镜像包含 Vue、Spring Boot、题库与面试资料；SQLite、沙箱与导出数据通过 bind mount 持久化。部署脚本要求 Linux、Bash 4.3+、Docker Engine、Compose 2.20+、Git、GNU coreutils 和 tar。

该部署适用于本人或可信人员训练。判题进程与 Web 共享容器、操作系统用户及文件权限，目录沙箱不能替代运行不可信代码所需的独立执行环境。当前 SQLite 部署保持单实例。

## 1. 目录与配置

```text
/home/docker/
├── training-center/                # Git 仓库
│   └── deploy/linux/.env            # 生产配置，不提交 Git
├── output/
│   ├── coding-ai-exam/              # 必须，含 catalog/questions.json
│   └── interview/                   # 必须
├── training-data/
│   ├── training-runtime/            # database、sandboxes、exports、logs
│   └── releases.log                 # 时间、状态、新镜像、旧镜像、备份路径
└── backups/                        # 数据备份，必须在 training-data 之外
```

`output/` 不在 Git 内。先用本地 `deploy/sync-data.ps1` 同步完毕，再执行镜像构建；导入题目时，`config/` 索引与 `output/` 内容必须成套更新。

```powershell
# 在本地 training-center 仓库执行
.\deploy\sync-data.ps1 -Server user@<服务器IP>
```

服务器首次配置：

```bash
cd /home/docker/training-center
cp deploy/linux/.env.example deploy/linux/.env
chmod 600 deploy/linux/.env
```

编辑 `.env`，设置数据目录、端口与 API Key。脚本会从自身目录读取 `.env`，与当前工作目录无关；已导出的环境变量优先。也可用 `TRAINING_ENV_FILE=/绝对路径/production.env` 指定其他配置文件。

配置使用字面量 `KEY=value`，允许成对的单引号或双引号；不执行 shell 命令，不展开变量，不支持 `export` 与行尾注释。`TRAINING_IMAGE` 由发布脚本选择，无需写入 `.env`。

| 配置 | 默认值 | 用途 |
|---|---|---|
| `TRAINING_DATA_DIR` | `/home/docker/training-data` | 运行数据与发布记录 |
| `BACKUP_DIR` | `/home/docker/backups` | 备份位置，应另行复制到异机存储 |
| `KEEP` | `7` | 本机保留备份份数，必须大于 0 |
| `TRAINING_PORT` | `8080` | 宿主机端口；容器内部仍用 8080 |
| `TRAINING_BIND_ADDRESS` | `0.0.0.0` | 宿主机有 Nginx 时改为 `127.0.0.1` |
| `TRAINING_API_KEY` | 空 | 启用时 API 发送 `X-API-Key` |
| `JAVA_OPTS` | `-Xms128m -Xmx512m ...` | Web JVM 内存参数 |
| `TRAINING_MEMORY_LIMIT` | `2G` | 整个容器限制，包含判题子进程 |
| `TRAINING_CPU_LIMIT` | `2.0` | 容器 CPU 限额 |
| `BASE_IMAGE` | `training-center-base:17` | 构建基础镜像 |
| `COMPOSE_PROJECT_NAME` | `deploy` | 保留旧脚本使用的 Compose 项目标识 |
| `TRAINING_HEALTH_TIMEOUT` | `180` | 等待容器健康的秒数 |

2G 容器限制沿用旧配置，不能据此认定支持持续双路判题：Web 堆为 512MB，两路 Maven 堆各可用 512MB，另有测试 JVM、线程栈和原生内存。低内存机器先单人串行使用；双路判题应在留足宿主机内存的情况下提高容器限制，并通过真实判题压测和 `docker stats` 定容量。不要把 Web 堆再设为容器内存的 75%。

API Key 在运行时配置。浏览器打开页面后，可在控制台执行 `localStorage.setItem('training:apiKey', '你的key')` 并刷新；普通请求与判题 fetch 流都会带请求头。生产共享密钥不要通过 `VITE_TRAINING_API_KEY` 编译进可公开下载的 JS。通过公网使用时先配置 HTTPS 和访问限制。

## 2. 首次部署

```bash
cd /home/docker/training-center
bash deploy/linux/deploy.sh
```

脚本检查 Docker、Compose、已有容器的数据挂载与所属项目，构建基础镜像（缺失时）和应用镜像，再检查 uid 1000 对运行目录的写权限。首次部署无数据时直接启动；已有运行数据时先备份。使用 `up --wait` 等待容器健康后才报告成功。

脚本不覆盖父目录 `.dockerignore`。应用构建自动使用 `Dockerfile.dockerignore`，只把构建所需源码、题库与镜像配置加入上下文。[Docker 支持与 Dockerfile 同名的忽略文件，并优先使用它](https://docs.docker.com/build/concepts/context/#filename-and-location)。

应用镜像使用 `training-center:<UTC时间>-<Git短SHA>-<进程号>` 标记。这里的时间同时区分未纳入 Git 的题库内容版本；镜像才是本次发布的完整产物。

运行目录必须对 uid 1000 可写；脚本会检查并在有权限时修正。权限检查失败应先按提示处理，不会继续停掉原服务。

## 3. 更新与指定制品

```bash
cd /home/docker/training-center
bash deploy/linux/update.sh

# 已经手动选择 Git 版本，或明确需要构建当前本地修改时
bash deploy/linux/update.sh --no-pull

# 基础镜像已经存在时，构建并发布当前目录
bash deploy/linux/deploy.sh --no-base

# 使用已经存在于本机的镜像，不重新构建
bash deploy/linux/deploy.sh --image training-center:<版本标签>
```

更新顺序：

1. 检查工作树，再 `git pull --ff-only`；不会自动 rebase 生产代码。
2. 旧服务保持运行，构建新镜像并验证数据目录写权限。
3. 为旧镜像添加独立 rollback 标签，停写，生成完整数据备份。
4. 重建容器，不临时拉取或重新构建镜像，等待健康状态。
5. 在 `TRAINING_DATA_DIR/releases.log` 记录结果。成功后才清理超过 `KEEP` 的备份。

构建失败不会停服务；备份失败会尝试重新启动原服务；容器替换开始后若启动失败，会留下旧镜像、备份路径及 failed 记录，不自动降级数据库。此流程存在停写备份和启动期间的维护窗口，数据量越大，窗口越长。

可选 `--prune` 清理 Docker 悬空镜像；不会清理有标签的发布或 rollback 镜像。这些镜像需结合异机备份和保留策略人工清理，避免长期占满磁盘。

首次构建或手动构建示例：

```bash
cd /home/docker
docker build -f training-center/deploy/base/Dockerfile -t training-center-base:17 .
docker build -f training-center/Dockerfile -t training-center:manual-test .
bash training-center/deploy/linux/deploy.sh --image training-center:manual-test
```

基础镜像依赖更新后直接重建，再发布应用；不需要先删除旧基础镜像。

## 4. 验收与反向代理

```bash
docker inspect --format='{{json .State.Health}}' training-center
docker logs --tail 100 training-center
curl --fail http://127.0.0.1:8080/api/health
docker stats --no-stream training-center
```

如果修改了 `TRAINING_PORT`，调整宿主机 curl 的端口。生产容器检查 `/actuator/health/readiness`，等待 Spring 启动完成并允许接收请求后才标记健康；它不代替业务验收。上线还应在浏览器检查题库摘要、创建会话并真实运行一次 Java/Python 判题。[Spring Boot readiness 说明](https://docs.spring.io/spring-boot/3.3/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)。

宿主机已有 HTTPS Nginx 时，将应用绑定到 `127.0.0.1`，在现有 `server` 中加入以下 location，并按实际端口修改 upstream。不要用此片段覆盖已有 TLS、域名或登录配置。

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_read_timeout 180s;
}
```

关闭代理响应缓冲，使判题 SSE 进度及时到达浏览器；超时需大于应用的 150 秒判题流期限。[Nginx 缓冲与超时说明](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)。

## 5. 备份与恢复

```bash
bash /home/docker/training-center/deploy/linux/backup.sh
```

备份脚本短暂停止原本运行的容器，打包 SQLite/WAL、沙箱、导出和发布记录，校验归档后原子发布；失败时清理临时归档并尝试恢复原服务。原本停止的容器不会被启动。发布与备份通过同一个目录锁排斥并发操作。

不能直接对正在写入的 SQLite 数据目录执行 tar 当作一致快照。若以后要求不停服务，应改用 [SQLite Online Backup API](https://www.sqlite.org/backup.html)，并另外设计沙箱文件与数据库之间的一致性策略。

每日备份可安排在低峰维护窗口：

```cron
0 2 * * * /bin/bash /home/docker/training-center/deploy/linux/backup.sh >> /home/docker/backup.log 2>&1
```

恢复前从 `releases.log` 选择同一次发布前的备份和旧镜像。数据库已迁移时，不要仅切换旧镜像。恢复会退回到备份时间点。

以下示例使用默认数据目录；必须将归档名和镜像标签替换为已选记录：

```bash
docker stop --time 180 training-center
mkdir -p /home/docker/restore-review
tar -tzf /home/docker/backups/training-data-<备份标识>.tar.gz
tar -xzf /home/docker/backups/training-data-<备份标识>.tar.gz -C /home/docker/restore-review

# 将当前数据保留到新的旁路目录；不要把旧数据库覆盖到仍有新 WAL 的目录上。
mv /home/docker/training-data/training-runtime /home/docker/training-runtime-before-restore-$(date +%s)
mv /home/docker/restore-review/training-data/training-runtime /home/docker/training-data/training-runtime
sudo chown -hR 1000:1000 /home/docker/training-data/training-runtime
bash /home/docker/training-center/deploy/linux/deploy.sh --image training-center:rollback-<镜像标识>
```

自定义数据目录的归档顶层名称等于该目录的 basename，先检查 `tar -tzf` 再调整恢复路径。`.env` 位于仓库中，不在数据备份内；应单独安全保存，并在跨版本恢复时核对配置兼容性。

## 6. Docker daemon 与脚本排查

容器日志轮转已配置为 10MB × 3，无需更改全局 daemon。`deploy/linux/docker-daemon.json` 只是合法 JSON 示例；需要采用缓存回收等配置时，先与服务器原配置合并，使用 `dockerd --validate --config-file=...` 校验，通过后再安排 Docker 重启。不要直接覆盖现有配置。镜像加速地址由运维选择可信且可用的源，不预置不确定可用的公共代理。[Docker daemon 配置说明](https://docs.docker.com/reference/cli/dockerd/#daemon-configuration-file)。

异常退出留下 `.deploy.lock` 时，先确认没有部署或备份脚本运行，再用 `rmdir /home/docker/training-data/.deploy.lock` 移除空锁目录。不要在其他操作还运行时强行解锁。

本地与 CI 验证：

```bash
for script in deploy/linux/*.sh deploy/base/*.sh; do bash -n "$script"; done
python3 -m unittest discover -s deploy/linux/tests -v
docker compose -f deploy/docker-compose.yml -f deploy/linux/docker-compose.prod.yml config --quiet
```

脚本测试使用临时目录及模拟 Docker；配置测试只调用真实 Compose 的 `config`，不启动容器。完整镜像构建、真实升级恢复演练及容量压测仍需在有 Docker Engine 的预发布环境执行。
