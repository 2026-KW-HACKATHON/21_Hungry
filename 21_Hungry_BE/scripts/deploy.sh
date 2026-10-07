#!/usr/bin/env bash

set -Eeuo pipefail

APP_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ENV_FILE="${ENV_FILE:-$APP_DIR/.env}"
COMPOSE_FILE="$APP_DIR/compose.prod.yaml"
STATE_DIR="$APP_DIR/.deploy"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing production environment file: $ENV_FILE" >&2
  exit 1
fi

cd "$APP_DIR"

mkdir -p "$STATE_DIR"
release_id="${RELEASE_ID:-$(git rev-parse --verify HEAD)}"
new_image="21-hungry-api:${release_id}"
previous_image="$(API_IMAGE="${API_IMAGE:-scratch}" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" images -q api 2>/dev/null | head -n 1 || true)"
export API_IMAGE="$new_image"

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet
docker build --pull --tag "$new_image" .
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build --remove-orphans

for attempt in {1..12}; do
  if docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
      exec -T api wget -qO- http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "Backend deployment is healthy."
    exit 0
  fi

  echo "Waiting for backend readiness ($attempt/12)..."
  sleep 5
done

echo "Backend failed its readiness check." >&2
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps >&2
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" logs --tail=100 api >&2
if [[ -n "$previous_image" ]]; then
  echo "Restoring previous image $previous_image" >&2
  export API_IMAGE="$previous_image"
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build api >&2
fi
exit 1
