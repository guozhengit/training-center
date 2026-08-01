#!/bin/bash
# ============================================================
# training-center 数据备份脚本 (Linux)
#
# 备份生产数据 (SQLite 数据库/提交/导出/日志), 默认保留最近 7 份。
#
# 用法:
#   ./training-center/deploy/linux/backup.sh
#   TRAINING_DATA_DIR=/custom/path ./training-center/deploy/linux/backup.sh
#   BACKUP_DIR=/mnt/backup ./training-center/deploy/linux/backup.sh
#
# 恢复:
#   tar -xzf backups/training-data-<STAMP>.tar.gz -C /tmp
#   sudo rsync -a /tmp/training-data/ /home/docker/training-data/
#   sudo docker restart training-center
# ============================================================
set -euo pipefail

DATA_DIR="${TRAINING_DATA_DIR:-/home/docker/training-data}"
BACKUP_DIR="${BACKUP_DIR:-/home/docker/backups}"
KEEP="${KEEP:-7}"

mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d_%H%M%S)
FILE="$BACKUP_DIR/training-data-$STAMP.tar.gz"

tar -czf "$FILE" -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"

ls -t "$BACKUP_DIR"/training-data-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "[backup] 完成: $FILE ($(du -h "$FILE" | cut -f1))"
echo "[backup] 保留最近 $KEEP 份: $BACKUP_DIR"
