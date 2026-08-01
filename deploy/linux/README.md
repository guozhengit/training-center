# Training Center Linux 服务器部署工具包

服务器部署脚本，配合 `docs/docker-deployment-linux.md` 使用。

## 文件说明

| 文件 | 运行位置 | 作用 |
|---|---|---|
| `deploy.sh` | 服务器 | 一键部署：前置检查 → 数据目录 → 基础镜像 → 应用镜像 → 启动 → 健康检查 |
| `update.sh` | 服务器 | 更新部署：`git pull` → 重建镜像 → 重建容器 → 健康检查 |
| `backup.sh` | 服务器 | 备份运行数据（默认保留最近 7 份） |
| `docker-compose.prod.yml` | 服务器 | 生产 override：数据卷改用 bind mount 到宿主机 |
| `docker-daemon.json` | 服务器 | Docker daemon 优化：国内镜像加速 + 日志滚动 + 构建缓存回收 |
| `../sync-data.ps1` | 本地 Windows | 把 `output/`（题库 + 面试资料，不在 git 内）scp 推送到服务器 |

两个部署脚本的公共参数：

```bash
./deploy/linux/deploy.sh [--no-base] [--prune]
./deploy/linux/update.sh  [--prune]
```

- `--no-base`：跳过基础镜像构建（`deploy.sh`，需 `training-center-base:17` 已存在）
- `--prune`：成功部署后执行 `docker image prune -f` 清理悬空镜像

脚本兼容 `docker compose` v2 与 `docker-compose` v1；非 root 运行时会自动用 `sudo` 处理数据目录属主。

## 服务器目录规划

```bash
/home/docker/
├── training-center/          # 本仓库 (git clone)
├── output/
│   ├── coding-ai-exam/       # 题库 (必须, 构建时烘焙进镜像)
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
git clone git@github.com:guozhengit/training-center.git training-center
# 本地机器推送题库与面试资料 (output/ 不在 git 内):
#   .\deploy\sync-data.ps1 -Server user@<服务器IP>
chmod +x training-center/deploy/linux/*.sh
./training-center/deploy/linux/deploy.sh
```

部署完成后访问 `http://服务器IP:8080`。

## 题目导入后的部署（重要）

导入功能会把两部分内容写到不同位置：

| 内容 | 位置 | 如何同步 |
|---|---|---|
| 导入索引 | `training-center/config/imported-questions.json` 等 | 在 git 内，`git pull` 即到 |
| 题目内容 | `output/coding-ai-exam/...`、`output/interview/imported/` | 不在 git 内，必须 `sync-data.ps1` 推送 |

本地导入题目后，按顺序执行：

```powershell
# 1. 本地: 提交并推送 config/ 改动 (导入索引)
git add training-center/config
git commit -m "feat: import questions"
git push

# 2. 本地: 推送 output 数据 (题目内容)
.\deploy\sync-data.ps1 -Server user@<服务器IP>

# 3. 服务器: 拉代码 + 重建镜像 + 重启
cd /home/docker/training-center
git pull
./deploy/linux/update.sh
```

> 提示：`deploy.sh` 检测到 `config/imported-questions.json` 时会提示先同步 output，
> 避免新题目因为内容文件缺失而在运行期报 `Missing source path`。

## Docker daemon 优化（可选，推荐）

```bash
sudo cp training-center/deploy/linux/docker-daemon.json /etc/docker/daemon.json
sudo systemctl restart docker
```

## 日常运维

```bash
# 更新代码 (含导入索引)
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

- 容器以非 root 用户 (uid 1000) 运行，脚本会自动设置数据目录属主；非 root 用户执行脚本时依赖 `sudo`
- 数据目录 `/home/docker/training-data` 可用 `TRAINING_DATA_DIR` 环境变量覆盖
- 健康检查通过 `curl http://127.0.0.1:8080/api/health`，返回 `{"status":"UP"}`
- 外网访问需放行云安全组 / 防火墙 8080 端口
