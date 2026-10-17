"""Prova limitada de recusa de PIDs no cgroup próprio do worker CI."""

import os

from containment import _worker, parse_observations

EXPECTED = {"PIDS_LIMIT": "128", "PIDS_BASELINE": "1", "PIDS_PEAK": "128", "PIDS_HELD": None,
            "PIDS_BEFORE_EVENTS": int, "PIDS_AFTER_EVENTS": int, "PIDS_RECOVERED": "1", "PIDS_ACTOR_EXIT": int,
            "PIDS_POSITIVE": "PASS", "PIDS_RECOVERY": "PASS", "AJ_RES_PIDS": "PASS"}

SCRIPT = r'''set -eu
printf 'token\n' | sh -c 'read -r token; [ "$token" = token ]; printf "PIDS_POSITIVE=PASS\n"'
read -r limit < /sys/fs/cgroup/pids.max
read -r baseline < /sys/fs/cgroup/pids.current
printf 'PIDS_LIMIT=%s\nPIDS_BASELINE=%s\n' "$limit" "$baseline"
[ "$limit" = 128 ] && [ "$baseline" = 1 ] && [ ! -e /tmp/pid-release ]
read_events() {
  event=
  while read -r key value; do
    case "$key" in max) event=$value ;; esac
  done < /sys/fs/cgroup/pids.events.local
  [ -n "$event" ]
}
read_events
before=$event
printf 'PIDS_BEFORE_EVENTS=%s\n' "$before"
(while [ ! -e /tmp/pid-release ]; do :; done) | sh -c '
  exec 3<&0
  i=0
  while [ "$i" -lt 160 ]; do
    sh -c "read -r token || :" <&3 &
    i=$((i+1))
  done
  wait
' &
actor=$!
tries=0
while [ "$tries" -lt 100000 ]; do
  read_events
  [ "$event" -gt "$before" ] && break
  tries=$((tries+1))
done
[ "$event" -gt "$before" ]
read -r peak < /sys/fs/cgroup/pids.peak
read -r held < /sys/fs/cgroup/pids.current
printf 'PIDS_AFTER_EVENTS=%s\nPIDS_PEAK=%s\nPIDS_HELD=%s\n' "$event" "$peak" "$held"
printf 'release\n' > /tmp/pid-release
set +e
wait "$actor"
status=$?
set -e
printf 'PIDS_ACTOR_EXIT=%s\n' "$status"
tries=0
while [ "$tries" -lt 100000 ]; do
  jobs > /dev/null
  read -r current < /sys/fs/cgroup/pids.current
  [ "$current" = "$baseline" ] && break
  tries=$((tries+1))
done
[ "$current" = "$baseline" ]
printf 'PIDS_RECOVERED=%s\n' "$current"
printf 'token\n' | sh -c 'read -r token; [ "$token" = token ]; printf "PIDS_RECOVERY=PASS\n"'
printf 'AJ_RES_PIDS=PASS\n'
'''


def validate_pids(observations):
    values = parse_observations("\n".join(f"{key}={value}" for key, value in observations.items()), EXPECTED)
    delta = int(values["PIDS_AFTER_EVENTS"]) - int(values["PIDS_BEFORE_EVENTS"])
    held = int(values["PIDS_HELD"])
    if delta <= 0 or not 126 <= held <= 128:
        raise ValueError("Recusa local/população retida não comprovadas; inconclusivo, não PASS.")
    return {"id": "AJ-RES-PIDS", "conclusion": "PASS_PIDS_ONLY", "refusalDelta": delta,
            "baseline": 1, "peak": 128, "held": held, "recovered": 1, "actorExit": int(values["PIDS_ACTOR_EXIT"]),
            "attemptCeiling": 160, "limitation": "Linux CI/dash; sem prova DockerDesktop, contenção do agente ou remediação de CVEs"}


def run_pids(image, workspace, marker):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Excesso controlado somente no CI Linux hospedado.")
    worker = _worker(image, marker, workspace, SCRIPT, EXPECTED, timeout=30)
    return {**validate_pids(worker["observations"]), "worker": worker,
            "positive": "Pipe/read e shell novos antes/depois; população retorna ao baseline",
            "expected": "Limite128 gera evento local e pico128 com filhos retidos, liberados por EOF",
            "executedBy": "controlador CI confiável"}
