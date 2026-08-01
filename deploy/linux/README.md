# Training Center Linux 服务器部署工具包

服务器部署三板斧脚本，配合 `docs/docker-deployment-linux.md` 使用。

## 文件说明

| 文件 | 作用 |
|---|---|
| `deploy.sh` | 一键部署：前置检查 → 数据目录 → 基础镜像 → 应用镜像 → 启动 → 健康检查 |
| `update.sh` | 更新部署：`git pull` → 重建镜像 → 重建容器 → 健康检查 |
| `backup.sh` | 备份运行数据（默认保留最近 7 份） |
| `docker-compose.prod.yml` | 生产 override：数据卷改用 bind mount 到宿主机 |
| `docker-daemon.json` | Docker daemon 优化：国内镜像加速 + 日志滚动 + 构建缓存回收 |

## 服务器目录规划

```bash
/home/docker/
├── training-center/          # 本仓库 (git clone)
├── output/
│   ├── coding-ai-exam/       # 题库 (必须)
│   └── interview/            # 面试资料 (必须)
├── training-data/            # 运行数据 (deploy.sh 自动创建)
│   └── training-runtime/
│       ├── database/
│       ├── sandboxes/
│       ├── exports/
│       └── logs/
└── backups/                  # backup.sh 输出目录
```

## 首次部署

```bash
cd /home/docker
git clone <仓库地址> training-center
# 上传/同步 output/coding-ai-exam 与 output/interview
chmod +x training-center/deploy/linux/*.sh
./training-center/deploy/linux/deploy.sh
```

部署完成后访问 `http://服务器IP:8080`。

## Docker daemon 优化（可选，推荐）

```bash
sudo cp training-center/deploy/linux/docker-daemon.json /etc/docker/daemon.json
sudo systemctl restart docker
```

## 日常运维

```bash
# 更新代码
./training-center/deploy/linux/update.sh

# 备份数据 (建议 cron 每日执行: 0 2 * * * /home/docker/training-center/deploy/linux/backup.sh)
./training-center/deploy/linux/backup.sh

# 查看日志 / 状态
docker logs -f training-center
docker ps | grep training-center

# 强制重建基础镜像 (中间件升级时)
docker image rm training-center-base:17
./training-center/deploy/linux/deploy.sh
```

## 注意事项

- 容器以非 root 用户 (uid 1000) 运行，`deploy.sh` 已自动处理数据目录属主
- 数据目录 `/home/docker/training-data` 可用 `TRAINING_DATA_DIR` 环境变量覆盖
- 健康检查通过 `curl http://127.0.0.1:8080/api/health`，返回 `{"status":"UP"}`
- 外网访问需放行云安全组 / 防火墙 8080 端口
