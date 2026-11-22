#!/usr/bin/env bash
set -euo pipefail

source_name=${1:?source container required}
target_name=${2:?target container required}
backup_file=${3:?fixture backup path required}
run_id=${GITHUB_RUN_ID:?CI run id required}
attempt=${GITHUB_RUN_ATTEMPT:?CI run attempt required}
arch=${EXPECTED_ARCH:?native architecture required}
[[ ${GITHUB_ACTIONS:-} = true && $run_id =~ ^[0-9]+$ && $attempt =~ ^[0-9]+$ ]]
[[ $arch =~ ^(amd64|arm64)$ ]]
owner="${run_id}-${attempt}-${arch}"
label='credpay.restore.owner'
for name in "$source_name" "$target_name"; do
  [[ $name = "credpay-pg-restore-${owner}-"* ]]
  [[ "$(docker container inspect --format "{{index .Config.Labels \"$label\"}}" "$name")" = "$owner" ]]
done
[[ $backup_file = ".local/security/postgres-restore/${owner}-"*'.dump' ]]
[[ ! -e $backup_file ]]

# Red phase: the test must detect that an empty target has no restored row.
:
