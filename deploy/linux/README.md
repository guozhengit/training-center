# Linux 生产部署工具

完整配置、升级、Nginx 与数据库恢复步骤见 [生产部署与恢复](../../docs/docker-deployment-linux.md)。

要求 Linux、Bash 4.3+、Docker Engine、Docker Compose 2.20+、Git、tar 与 GNU coreutils。沿用默认 Compose 项目名 `deploy`，避免改变现有容器身份。

```bash
cd /home/docker/training-center
cp deploy/linux/.env.example deploy/linux/.env
chmod 600 deploy/linux/.env
# 编辑数据目录、端口、API Key 和内存限制
bash deploy/linux/deploy.sh
```

| 文件 | 用途 |
|---|---|
| `.env.example` | 生产配置模板；复制为 `.env`，不提交密钥 |
| `common.sh` | 配置读取、目录校验、操作锁、停写备份及失败恢复 |
| `deploy.sh` | 构建带版本的镜像，备份数据，替换并等待健康 |
| `update.sh` | 检查工作树，fast-forward 拉取，再执行发布 |
| `backup.sh` | 短暂停写后的完整数据备份，默认保留 7 份 |
| `docker-compose.prod.yml` | bind mount、优雅关闭和容器权限约束 |
| `docker-daemon.json` | 可选全局配置示例，合并并校验后使用 |
| `tests/test_deployment.py` | 隔离的发布/备份回归与 Compose 配置测试 |

```bash
bash deploy/linux/deploy.sh --no-base
bash deploy/linux/update.sh
bash deploy/linux/update.sh --no-pull
bash deploy/linux/deploy.sh --image training-center:<本机已有标签>
bash deploy/linux/backup.sh
```

`--prune` 可用于部署/更新成功后清理悬空镜像。发布记录保存在 `TRAINING_DATA_DIR/releases.log`；旧镜像保留独立 rollback 标签。降级时先核对数据库迁移，必要时同时恢复对应备份。

题库和面试资料位于父工作区 `output/`，不在 Git 内。先在 Windows 使用 `deploy/sync-data.ps1` 同步完毕，再构建；应用构建不再改写父工作区 `.dockerignore`。

`backup.sh` 会短暂停服务，失败也会尝试恢复原本运行的服务。备份必须放在数据目录之外，并另行复制到异机存储。
