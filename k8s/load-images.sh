#!/usr/bin/env bash
set -euo pipefail

# Build Compose images and load them into a kind cluster (default name: kind).
# Usage: ./k8s/load-images.sh [kind-cluster-name]

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER="${1:-kind}"

IMAGES=(
  seckill-admin-service:0.2.0-SNAPSHOT
  seckill-command-service:0.2.0-SNAPSHOT
  seckill-persist-service:0.2.0-SNAPSHOT
  seckill-query-service:0.2.0-SNAPSHOT
  seckill-event-service:0.2.0-SNAPSHOT
  seckill-frontend:0.2.0-SNAPSHOT
)

cd "$ROOT"
docker compose build admin-service command-service persist-service query-service event-service frontend

for image in "${IMAGES[@]}"; do
  kind load docker-image "$image" --name "$CLUSTER"
done
