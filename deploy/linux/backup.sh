#!/bin/bash
# Full offline snapshot. Resume a running service even if archiving fails.
set -euo pipefail
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  echo 'Usage: backup.sh (settings: deploy/linux/.env or environment variables)'
  exit 0
fi
[[ $# == 0 ]] || { echo 'Unexpected backup argument' >&2; exit 1; }
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_docker
validate_backup
lock_operation
backup_data
prune_backups
info "Backup complete; keeping $KEEP archives. A previously running service will now resume."
