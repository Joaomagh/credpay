#!/usr/bin/env bash
set -euo pipefail

arch=${EXPECTED_ARCH:?native architecture required}
run_id=${GITHUB_RUN_ID:?CI run id required}
attempt=${GITHUB_RUN_ATTEMPT:?CI run attempt required}
[[ ${GITHUB_ACTIONS:-} = true && $arch =~ ^(amd64|arm64)$ ]]
[[ $run_id =~ ^[0-9]+$ && $attempt =~ ^[0-9]+$ ]]
owner="${run_id}-${attempt}-${arch}"
label="credpay.restore.owner=${owner}"
candidate=${1:?candidate image required}
bookworm='postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994'
[[ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$candidate")" = "linux/$arch" ]]
[[ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$bookworm")" = "linux/$arch" ]]

services=(transacoes processamento)
resources=()
for service in "${services[@]}"; do
  for side in source target; do
    name="credpay-pg-restore-${owner}-${service}-${side}"
    volume="${name}-data"
    if docker container inspect "$name" >/dev/null 2>&1 || docker volume inspect "$volume" >/dev/null 2>&1; then
      echo 'Restore resource name already exists; refusing to adopt it.' >&2
      exit 1
    fi
    resources+=("$name")
  done
done

cleanup() {
  for name in "${resources[@]}"; do
    if [[ "$(docker container inspect --format '{{index .Config.Labels "credpay.restore.owner"}}' "$name" 2>/dev/null || true)" = "$owner" ]]; then
      docker rm -f "$name" >/dev/null
    fi
  done
  for name in "${resources[@]}"; do
    volume="${name}-data"
    if [[ "$(docker volume inspect --format '{{index .Labels "credpay.restore.owner"}}' "$volume" 2>/dev/null || true)" = "$owner" ]]; then
      docker volume rm "$volume" >/dev/null
    fi
  done
}
trap cleanup EXIT

wait_ready() {
  local name=$1 ready=0
  for _ in $(seq 1 60); do
    if [[ "$(docker exec "$name" cat /proc/1/comm 2>/dev/null || true)" = postgres ]] \
      && docker exec -u postgres "$name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc 'SELECT 1' >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 1
  done
  [[ $ready -eq 1 ]]
}

mkdir -p .local/security/postgres-restore
umask 077
for service in "${services[@]}"; do
  source_name="credpay-pg-restore-${owner}-${service}-source"
  target_name="credpay-pg-restore-${owner}-${service}-target"
  source_volume="${source_name}-data"
  target_volume="${target_name}-data"
  docker volume create --label "$label" "$source_volume" >/dev/null
  docker volume create --label "$label" "$target_volume" >/dev/null
  docker run -d --name "$source_name" --label "$label" --network none \
    --security-opt no-new-privileges:true \
    -e POSTGRES_PASSWORD=fixture-only -e POSTGRES_DB=fixture \
    -v "$source_volume:/var/lib/postgresql/data" "$bookworm" >/dev/null
  docker run -d --name "$target_name" --label "$label" --network none \
    --security-opt no-new-privileges:true \
    -e POSTGRES_PASSWORD=fixture-only -e POSTGRES_DB=fixture \
    -v "$target_volume:/var/lib/postgresql/data" "$candidate" >/dev/null
  wait_ready "$source_name"
  wait_ready "$target_name"
  docker exec -i -u postgres "$source_name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture <<SQL >/dev/null
CREATE TABLE restore_probe (
  id uuid PRIMARY KEY,
  amount numeric(19,2) NOT NULL CHECK (amount > 0),
  payload jsonb NOT NULL
);
INSERT INTO restore_probe VALUES
  ('00000000-0000-0000-0000-000000000007', 250.00, '{"service":"$service"}');
SQL
  backup_file=".local/security/postgres-restore/${owner}-${service}.dump"
  bash infra/security/postgres-candidate/restore-fixture.sh "$source_name" "$target_name" "$backup_file"
  actual=$(docker exec -u postgres "$target_name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc \
    "SELECT id::text || '|' || amount::text || '|' || payload->>'service' FROM restore_probe")
  [[ $actual = "00000000-0000-0000-0000-000000000007|250.00|$service" ]]
  [[ "$(docker exec -u postgres "$target_name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc \
    "SELECT count(*) FROM pg_constraint WHERE conrelid = 'restore_probe'::regclass AND contype = 'c'")" = 1 ]]
  docker stop "$target_name" >/dev/null
  docker start "$target_name" >/dev/null
  wait_ready "$target_name"
  [[ "$(docker exec -u postgres "$target_name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc \
    'SELECT amount FROM restore_probe LIMIT 1')" = 250.00 ]]
  printf 'Restore fixture: %s, logical dump to fresh Alpine volume, data/constraint/restart passed.\n' "$service"
done
