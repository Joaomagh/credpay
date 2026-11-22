#!/usr/bin/env bash
set -euo pipefail

image=${1:?candidate image required}
arch=${2:?architecture required}
run_id=${GITHUB_RUN_ID:?CI run id required}
attempt=${GITHUB_RUN_ATTEMPT:?CI run attempt required}
[[ $run_id =~ ^[0-9]+$ && $attempt =~ ^[0-9]+$ && $arch =~ ^(amd64|arm64)$ ]]
owner="${run_id}-${attempt}-${arch}"
name="credpay-pg-candidate-${owner}"
volume="${name}-data"
label="credpay.candidate.owner=${owner}"

if docker container inspect "$name" >/dev/null 2>&1 || docker volume inspect "$volume" >/dev/null 2>&1; then
  echo 'Candidate resource name already exists; refusing to adopt it.' >&2
  exit 1
fi

cleanup() {
if [[ "$(docker container inspect --format '{{index .Config.Labels "credpay.candidate.owner"}}' "$name" 2>/dev/null || true)" = "$owner" ]]; then
    docker rm -f "$name" >/dev/null
  fi
  if [[ "$(docker volume inspect --format '{{index .Labels "credpay.candidate.owner"}}' "$volume" 2>/dev/null || true)" = "$owner" ]]; then
    docker volume rm "$volume" >/dev/null
  fi
}
trap cleanup EXIT

[[ "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")" = "linux/$arch" ]]
[[ "$(docker image inspect --format '{{.Config.User}}' "$image")" = postgres ]] \
  || { echo 'Candidate default image user must be postgres.' >&2; exit 1; }
[[ "$(docker run --rm --network none --entrypoint id "$image" -u)" = 70 ]] \
  || { echo 'Candidate default runtime UID must be 70.' >&2; exit 1; }
docker run --rm --network none --user postgres --entrypoint postgres "$image" --version \
  | grep -Eq '^postgres \(PostgreSQL\) 17\.12([ .]|$)'
docker run --rm --network none --entrypoint sh "$image" -ec \
  'test -L /usr/local/bin/gosu && test "$(readlink /usr/local/bin/gosu)" = /sbin/su-exec'
[[ "$(docker run --rm --network none --user root --entrypoint gosu "$image" postgres id -u)" = 70 ]] \
  || { echo 'Candidate root-start fallback failed to drop to postgres.' >&2; exit 1; }
docker volume create --label "$label" "$volume" >/dev/null
docker run -d --name "$name" --label "$label" --network none \
  --security-opt no-new-privileges:true \
  -e POSTGRES_PASSWORD=fixture-only -e POSTGRES_DB=fixture \
  -v "$volume:/var/lib/postgresql/data" "$image" >/dev/null
ready=0
for _ in $(seq 1 60); do
  if [[ "$(docker exec "$name" cat /proc/1/comm 2>/dev/null || true)" = postgres ]] \
    && docker exec -u postgres "$name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc 'SELECT 1' >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[[ "$ready" -eq 1 ]]
[[ "$(docker exec "$name" awk '/^Uid:/ {print $2}' /proc/1/status)" = "$(docker exec "$name" id -u postgres)" ]]
docker exec -u postgres "$name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc \
  'CREATE TABLE candidate_probe (id integer PRIMARY KEY); INSERT INTO candidate_probe VALUES (7);' >/dev/null
docker stop "$name" >/dev/null
docker rm "$name" >/dev/null
docker run -d --name "$name" --label "$label" --network none \
  --security-opt no-new-privileges:true \
  -e POSTGRES_PASSWORD=fixture-only -e POSTGRES_DB=fixture \
  -v "$volume:/var/lib/postgresql/data" "$image" >/dev/null
ready=0
for _ in $(seq 1 60); do
  if [[ "$(docker exec "$name" cat /proc/1/comm 2>/dev/null || true)" = postgres ]] \
    && docker exec -u postgres "$name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc 'SELECT 1' >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[[ "$ready" -eq 1 ]]
[[ "$(docker exec -u postgres "$name" psql -v ON_ERROR_STOP=1 -U postgres -d fixture -Atqc 'SELECT id FROM candidate_probe')" = 7 ]]
printf 'Candidate probe: linux/%s, privilege drop, initialization and restart with owned volume passed.\n' "$arch"
