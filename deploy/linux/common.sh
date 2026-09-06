#!/bin/bash
# Shared settings and snapshot lifecycle. Environment files are data, not shell code.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKSPACE_ROOT="$(cd "$REPO_DIR/.." && pwd)"
info() { printf '[training] %s\n' "$*"; }
die() { printf '[training] ERROR: %s\n' "$*" >&2; exit 1; }

ENV_FILE="${TRAINING_ENV_FILE:-$SCRIPT_DIR/.env}"
if [[ -f "$ENV_FILE" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" =~ ^([A-Z_][A-Z0-9_]*)=(.*)$ ]] || die "Invalid KEY=value in $ENV_FILE"
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    case "$key" in
      TRAINING_DATA_DIR|BACKUP_DIR|KEEP|TRAINING_PORT|TRAINING_BIND_ADDRESS|TRAINING_API_KEY|JAVA_OPTS|TRAINING_MEMORY_LIMIT|TRAINING_CPU_LIMIT|BASE_IMAGE|COMPOSE_PROJECT_NAME|TRAINING_HEALTH_TIMEOUT) ;;
      *) die "Unsupported setting in $ENV_FILE: $key" ;;
    esac
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value="${value:1:${#value}-2}"; fi
    if [[ ! -v "$key" ]]; then export "$key=$value"; fi
  done < "$ENV_FILE"
elif [[ -n "${TRAINING_ENV_FILE:-}" ]]; then
  die "Environment file does not exist: $ENV_FILE"
fi

export TRAINING_DATA_DIR="${TRAINING_DATA_DIR:-/home/docker/training-data}"
export TRAINING_PORT="${TRAINING_PORT:-8080}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-deploy}"
export BASE_IMAGE="${BASE_IMAGE:-training-center-base:17}"
BACKUP_DIR="${BACKUP_DIR:-/home/docker/backups}"
KEEP="${KEEP:-7}"
HEALTH_TIMEOUT="${TRAINING_HEALTH_TIMEOUT:-180}"
CONTAINER=training-center
LOCK_DIR=''
BACKUP_TEMP=''
BACKUP_FILE=''
RESTART_ON_EXIT=false

[[ "$TRAINING_DATA_DIR" == /* ]] || die "TRAINING_DATA_DIR must be an absolute path"
DATA_DIR="$(realpath -m -- "$TRAINING_DATA_DIR")"
[[ "$DATA_DIR" != / ]] || die "TRAINING_DATA_DIR must not be /"
export TRAINING_DATA_DIR="$DATA_DIR"
[[ "$TRAINING_PORT" =~ ^[0-9]{1,5}$ ]] && ((10#$TRAINING_PORT > 0 && 10#$TRAINING_PORT <= 65535)) \
  || die "TRAINING_PORT must be between 1 and 65535"

require_docker() {
  command -v docker >/dev/null || die "Docker is required"
  docker info >/dev/null 2>&1 || die "Docker daemon is unavailable"
}

init_compose() {
  require_docker
  local version major minor
  version="$(docker compose version --short)" || die "Docker Compose >= 2.20 is required"
  [[ "$version" =~ ^v?([0-9]+)\.([0-9]+) ]] || die "Unrecognized Compose version: $version"
  major="${BASH_REMATCH[1]}"; minor="${BASH_REMATCH[2]}"
  ((major > 2 || (major == 2 && minor >= 20))) || die "Docker Compose >= 2.20 is required"
  [[ "$HEALTH_TIMEOUT" =~ ^[1-9][0-9]{0,3}$ ]] || die "Invalid TRAINING_HEALTH_TIMEOUT"
  # Avoid cwd-dependent Compose .env loading; settings are already exported literally.
  DC=(docker compose --env-file /dev/null --project-name "$COMPOSE_PROJECT_NAME"
      -f "$REPO_DIR/deploy/docker-compose.yml" -f "$SCRIPT_DIR/docker-compose.prod.yml")
  "${DC[@]}" config --quiet
}

cleanup_operation() {
  local status=$?
  trap - EXIT
  if [[ "$RESTART_ON_EXIT" == true ]]; then
    docker start "$CONTAINER" >/dev/null || { printf '[training] Could not restart %s\n' "$CONTAINER" >&2; status=1; }
  fi
  if [[ -n "$BACKUP_TEMP" && -f "$BACKUP_TEMP" ]]; then rm -f -- "$BACKUP_TEMP"; fi
  if [[ -n "$LOCK_DIR" ]]; then rmdir -- "$LOCK_DIR" || status=1; fi
  exit "$status"
}

lock_operation() {
  mkdir -p -- "$DATA_DIR"
  local candidate="$DATA_DIR/.deploy.lock"
  mkdir -- "$candidate" 2>/dev/null || die "Another operation holds $candidate (remove only after confirming it is inactive)"
  LOCK_DIR="$candidate"
  trap cleanup_operation EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

read_container() {
  CONTAINER_RUNNING="$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" || {
    CONTAINER_RUNNING=absent
    return
  }
  local source
  source="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/workspace/output/training-runtime"}}{{.Source}}{{end}}{{end}}' "$CONTAINER")"
  [[ -n "$source" && "$(realpath -m -- "$source")" == "$DATA_DIR/training-runtime" ]] \
    || die "Existing container uses different runtime storage; check TRAINING_DATA_DIR before proceeding"
}

validate_backup() {
  [[ "$KEEP" =~ ^[1-9][0-9]{0,3}$ ]] || die "KEEP must be a positive integer (1-9999)"
  [[ "$BACKUP_DIR" == /* ]] || die "BACKUP_DIR must be absolute"
  BACKUP_DIR="$(realpath -m -- "$BACKUP_DIR")"
  [[ "$BACKUP_DIR" != "$DATA_DIR" && "$BACKUP_DIR" != "$DATA_DIR/"* ]] \
    || die "BACKUP_DIR must be outside TRAINING_DATA_DIR"
  [[ -d "$DATA_DIR/training-runtime" ]] || die "Runtime data directory is missing"
  mkdir -p -- "$BACKUP_DIR"
}

backup_data() {
  validate_backup
  read_container
  if [[ "$CONTAINER_RUNNING" == true ]]; then
    RESTART_ON_EXIT=true
    docker stop --time 180 "$CONTAINER" >/dev/null
  fi
  local stamp
  stamp="$(date -u +%Y%m%dT%H%M%S%N)-$$"
  BACKUP_FILE="$BACKUP_DIR/training-data-$stamp.tar.gz"
  BACKUP_TEMP="$(mktemp "$BACKUP_DIR/.training-data-$stamp.XXXXXX")"
  tar --exclude="$(basename "$DATA_DIR")/.deploy.lock" -czf "$BACKUP_TEMP" \
    -C "$(dirname "$DATA_DIR")" "$(basename "$DATA_DIR")"
  tar -tzf "$BACKUP_TEMP" >/dev/null
  mv -- "$BACKUP_TEMP" "$BACKUP_FILE"
  BACKUP_TEMP=''
  info "Backup: $BACKUP_FILE"
}

prune_backups() {
  local files=() count i
  shopt -s nullglob
  files=("$BACKUP_DIR"/training-data-*.tar.gz)
  shopt -u nullglob
  count=$((${#files[@]} - KEEP))
  for ((i=0; i<count; i++)); do rm -f -- "${files[i]}"; done
}
