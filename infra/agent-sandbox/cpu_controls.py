"""Prova limitada da quota de CPU no cgroup próprio do worker CI."""

import os

from containment import _worker, parse_observations

EXPECTED = {"CPU_MAX": "100000 100000", "CPU_POSITIVE": "PASS", "CPU_RECOVERY": "PASS", "AJ_RES_CPU": "PASS",
            "LOAD1_WORK": None, "LOAD2_WORK": None,
            **{f"CPU_{phase}_{counter}": int for phase in ("BEFORE", "AFTER") for counter in ("PERIODS", "THROTTLED", "USEC")}}

SCRIPT = r'''set -eu
[ "$(awk 'BEGIN {print sqrt(1764)}')" = 42 ]
printf 'CPU_POSITIVE=PASS\nCPU_MAX=%s\n' "$(awk '{print $1 " " $2}' /sys/fs/cgroup/cpu.max)"
snapshot() {
  awk -v phase="$1" '$1 == "nr_periods" {print "CPU_" phase "_PERIODS=" $2}
    $1 == "nr_throttled" {print "CPU_" phase "_THROTTLED=" $2}
    $1 == "throttled_usec" {print "CPU_" phase "_USEC=" $2}' /sys/fs/cgroup/cpu.stat
}
snapshot BEFORE
work='BEGIN {
  deadline = systime() + 4; blocks = 0; value = 0
  while (systime() < deadline && blocks < 20000) {
    for (i = 0; i < 10000; i++) value += sqrt(i)
    blocks++
  }
  if (blocks < 1 || value <= 0) exit 1
  print name "=" blocks
}'
awk -v name=LOAD1_WORK "$work" &
first=$!
awk -v name=LOAD2_WORK "$work" &
second=$!
wait "$first"
wait "$second"
snapshot AFTER
[ "$(awk 'BEGIN {print 20+22}')" = 42 ]
printf 'CPU_RECOVERY=PASS\nAJ_RES_CPU=PASS\n'
'''


def validate_cpu(observations):
    values = parse_observations("\n".join(f"{key}={value}" for key, value in observations.items()), EXPECTED)
    before = {key.lower(): int(values[f"CPU_BEFORE_{key}"]) for key in ("PERIODS", "THROTTLED", "USEC")}
    after = {key.lower(): int(values[f"CPU_AFTER_{key}"]) for key in ("PERIODS", "THROTTLED", "USEC")}
    deltas = {key: after[key] - before[key] for key in before}
    if any(value <= 0 for value in deltas.values()):
        raise ValueError("CPU sem progresso dos contadores e throttling próprio; inconclusivo, não PASS.")
    return {"id": "AJ-RES-CPU", "conclusion": "PASS_CPU_ONLY", "before": before, "after": after, "deltas": deltas,
            "workBlocks": [int(values["LOAD1_WORK"]), int(values["LOAD2_WORK"])], "cpuMax": values["CPU_MAX"],
            "limitation": "Linux CI; não comprova memória/PID, DockerDesktop ou aceite da baseline com CVEs abertos"}


def run_cpu(image, workspace, marker):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Excesso controlado somente no CI Linux hospedado.")
    # Reutiliza validação efetiva, fixture própria e cleanup do protocolo revisado.
    worker = _worker(image, marker, workspace, SCRIPT, EXPECTED, timeout=15)
    return {**validate_cpu(worker["observations"]), "worker": worker,
            "expected": "Dois cálculos progridem e a quota própria gera throttling real; operação pequena recupera",
            "positive": "sqrt(1764)=42, progresso de ambos, 20+22=42 após recolher filhos",
            "executedBy": "controlador CI confiável"}
