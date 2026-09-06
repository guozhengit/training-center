#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
PULL=true
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --no-pull) PULL=false ;;
    --prune) ARGS+=(--prune) ;;
    -h|--help) echo 'Usage: update.sh [--no-pull] [--prune]'; exit 0 ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done
if [[ "$PULL" == true ]]; then
  [[ -z "$(git -C "$REPO_DIR" status --porcelain)" ]] || { echo 'Working tree is dirty; commit/stash deliberately, or use --no-pull for an intentional local build.' >&2; exit 1; }
  git -C "$REPO_DIR" pull --ff-only
fi
exec bash "$SCRIPT_DIR/deploy.sh" --no-base "${ARGS[@]}"
