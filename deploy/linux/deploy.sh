#!/bin/bash
# Build while the current service stays available; snapshot data before replacement.
set -euo pipefail
SKIP_BASE=false
PRUNE=false
EXISTING_IMAGE=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-base) SKIP_BASE=true ;;
    --prune) PRUNE=true ;;
    --image)
      [[ $# -ge 2 && -n "$2" && "$2" != -* ]] || { echo '--image requires a local image tag or digest' >&2; exit 1; }
      EXISTING_IMAGE="$2"; shift ;;
    -h|--help)
      echo 'Usage: deploy.sh [--no-base] [--prune] [--image LOCAL_IMAGE]'
      echo 'Loads deploy/linux/.env. Existing data is backed up before replacement.'
      exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
  shift
done
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
init_compose
lock_operation
read_container
if [[ "$CONTAINER_RUNNING" != absent ]]; then
  owner="$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$CONTAINER")"
  [[ "$owner" == "$COMPOSE_PROJECT_NAME" ]] || die "Container belongs to Compose project '$owner'; check COMPOSE_PROJECT_NAME"
fi

if [[ -z "$EXISTING_IMAGE" ]]; then
  [[ -f "$WORKSPACE_ROOT/output/coding-ai-exam/catalog/questions.json" ]] || die 'Missing output/coding-ai-exam/catalog/questions.json'
  [[ -d "$WORKSPACE_ROOT/output/interview" ]] || die 'Missing output/interview'
  if ! docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
    [[ "$SKIP_BASE" == false ]] || die "Missing base image: $BASE_IMAGE"
    docker build -f "$REPO_DIR/deploy/base/Dockerfile" -t "$BASE_IMAGE" "$WORKSPACE_ROOT"
  fi
  export VCS_REF="$(git -C "$REPO_DIR" rev-parse --short HEAD 2>/dev/null || printf unknown)"
  export TRAINING_IMAGE="training-center:$(date -u +%Y%m%dT%H%M%S)-$VCS_REF-$$"
  info "Building $TRAINING_IMAGE"
  "${DC[@]}" build training-center
else
  docker image inspect "$EXISTING_IMAGE" >/dev/null 2>&1 || die "Local image does not exist: $EXISTING_IMAGE"
  export TRAINING_IMAGE="$EXISTING_IMAGE"
fi

# Validate runtime permissions before stopping the old service.
mkdir -p "$DATA_DIR/training-runtime"/{database,sandboxes,exports,logs}
if [[ "$(id -u)" -eq 0 ]]; then
  chown -hR 1000:1000 "$DATA_DIR/training-runtime"
elif [[ "$(id -u)" -ne 1000 ]] && command -v sudo >/dev/null; then
  sudo -n chown -hR 1000:1000 "$DATA_DIR/training-runtime" || die 'Cannot set runtime ownership; run sudo chown manually'
fi
docker run --rm --user 1000 --entrypoint sh \
  -v "$DATA_DIR/training-runtime:/data" "$TRAINING_IMAGE" \
  -c 'test -w /data/database && test -w /data/sandboxes && test -w /data/exports && test -w /data/logs' \
  || die 'Runtime directory must be writable by uid 1000'

PREVIOUS_IMAGE=none
if [[ "$CONTAINER_RUNNING" != absent ]]; then
  PREVIOUS_IMAGE="training-center:rollback-$(date -u +%Y%m%dT%H%M%S)-$$"
  docker tag "$(docker inspect -f '{{.Image}}' "$CONTAINER")" "$PREVIOUS_IMAGE"
  backup_data
elif [[ -n "$(find "$DATA_DIR/training-runtime" -type f -print -quit)" ]]; then
  backup_data
fi

printf '%s\t%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" pending "$TRAINING_IMAGE" "$PREVIOUS_IMAGE" "$BACKUP_FILE" >> "$DATA_DIR/releases.log"
info "Starting $TRAINING_IMAGE; previous image: $PREVIOUS_IMAGE"
# After replacement begins, an image downgrade may conflict with a new DB schema.
RESTART_ON_EXIT=false
if ! "${DC[@]}" up -d --no-build --pull never --force-recreate --wait --wait-timeout "$HEALTH_TIMEOUT" training-center; then
  printf '%s\t%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" failed "$TRAINING_IMAGE" "$PREVIOUS_IMAGE" "$BACKUP_FILE" >> "$DATA_DIR/releases.log"
  die "Deployment failed. Inspect docker logs $CONTAINER. Previous image: $PREVIOUS_IMAGE; snapshot: $BACKUP_FILE. See recovery instructions before downgrading."
fi
printf '%s\t%s\t%s\t%s\t%s\n' "$(date -u +%FT%TZ)" healthy "$TRAINING_IMAGE" "$PREVIOUS_IMAGE" "$BACKUP_FILE" >> "$DATA_DIR/releases.log"
[[ -z "$BACKUP_FILE" ]] || prune_backups
info "Healthy: http://${TRAINING_BIND_ADDRESS:-0.0.0.0}:$TRAINING_PORT (replace 0.0.0.0 with the server address)"
if [[ "$PRUNE" == true ]]; then docker image prune -f; fi
