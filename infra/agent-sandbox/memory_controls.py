"""Prova limitada de OOM no cgroup próprio, preservando observador PID1."""

import os

from containment import _worker, parse_observations

EXPECTED = {"MEM_LIMIT": "1073741824", "MEM_SWAP": "0", "MEM_OOM_GROUP": "0", "MEM_OBSERVER": "PASS",
            "MEM_POSITIVE_BYTES": "33554432", "MEM_RECOVERY_BYTES": "33554432", "MEM_HEAVY_EXIT": "137",
            "MEM_LOAD_STARTED": "PASS", "AJ_RES_MEMORY": "PASS",
            **{f"MEM_{phase}_{key}": int for phase in ("BEFORE", "AFTER") for key in ("MAX", "OOM", "KILL")}}

SCRIPT = r'''set -eu
read -r limit < /sys/fs/cgroup/memory.max
read -r swap < /sys/fs/cgroup/memory.swap.max
read -r group < /sys/fs/cgroup/memory.oom.group
printf 'MEM_LIMIT=%s\nMEM_SWAP=%s\nMEM_OOM_GROUP=%s\nMEM_OBSERVER=PASS\n' "$limit" "$swap" "$group"
[ "$limit" = 1073741824 ] && [ "$swap" = 0 ] && [ "$group" = 0 ]
allocator='BEGIN {
  deadline = systime() + 12; bytes = 0
  for (i = 1; i <= blocks; i++) {
    if (systime() >= deadline) exit 31
    data[i] = sprintf("%032768d", i)
    bytes += length(data[i])
  }
  for (i = 1; i <= blocks; i++) {
    if (length(data[i]) != 32768 || substr(data[i],32764)+0 != i) exit 32
  }
  print name "=" bytes
}'
awk -v blocks=1024 -v name=MEM_POSITIVE_BYTES "$allocator"
snapshot() {
  while read -r key value; do
    case "$key" in
      max) printf 'MEM_%s_MAX=%s\n' "$1" "$value" ;;
      oom) printf 'MEM_%s_OOM=%s\n' "$1" "$value" ;;
      oom_kill) printf 'MEM_%s_KILL=%s\n' "$1" "$value" ;;
    esac
  done < /sys/fs/cgroup/memory.events.local
}
snapshot BEFORE
awk -v blocks=49152 -v name=MEM_HEAVY_COMPLETED_BYTES "$allocator" &
child=$!
printf 'MEM_LOAD_STARTED=PASS\n'
set +e
wait "$child"
status=$?
set -e
snapshot AFTER
printf 'MEM_HEAVY_EXIT=%s\n' "$status"
awk -v blocks=1024 -v name=MEM_RECOVERY_BYTES "$allocator"
printf 'AJ_RES_MEMORY=PASS\n'
'''


def validate_memory(observations):
    values = parse_observations("\n".join(f"{key}={value}" for key, value in observations.items()), EXPECTED)
    before = {key.lower(): int(values[f"MEM_BEFORE_{key}"]) for key in ("MAX", "OOM", "KILL")}
    after = {key.lower(): int(values[f"MEM_AFTER_{key}"]) for key in ("MAX", "OOM", "KILL")}
    deltas = {key: after[key] - before[key] for key in before}
    if any(value <= 0 for value in deltas.values()):
        raise ValueError("Exit137 sem eventos locais completos de OOM; inconclusivo, não PASS.")
    return {"id": "AJ-RES-MEMORY", "conclusion": "PASS_MEMORY_ONLY", "before": before, "after": after, "deltas": deltas,
            "positiveBytes": int(values["MEM_POSITIVE_BYTES"]), "recoveryBytes": int(values["MEM_RECOVERY_BYTES"]),
            "heavyExit": 137, "nominalAllocationCeilingBytes": 1610612736,
            "limitation": "Linux CI; sem prova de PIDs, DockerDesktop ou remediação dos advisories do core"}


def run_memory(image, workspace, marker):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Excesso controlado somente no CI Linux hospedado.")
    worker = _worker(image, marker, workspace, SCRIPT, EXPECTED, timeout=30, allow_child_oom=True)
    return {**validate_memory(worker["observations"]), "worker": worker,
            "positive": "Alocações únicas32MiB com materialização/readback antes/depois",
            "expected": "OOM local mata alocador; observador PID1 sobrevive, coleta eventos e recupera",
            "executedBy": "controlador CI confiável"}
