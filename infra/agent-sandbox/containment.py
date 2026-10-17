"""Negativos controlados do core; usa somente fixtures próprias no CI."""

import json
import hashlib
import os
import platform
import re
import shlex
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from source_ci import LABEL, ROOT, assert_never_started, docker

FIRST_SCRIPT = r'''set -eu
nonce=@NONCE@
outside=@OUTSIDE@
grep -qx baseline /workspace/canary.txt
if grep -Fq "$nonce" "$outside" 2>/tmp/outside.err; then exit 41; else outside_status=$?; fi
grep -q 'No such file or directory' /tmp/outside.err
if grep -Fq "$nonce" /workspace/outside-link 2>/tmp/link.err; then exit 42; else link_status=$?; fi
grep -q 'No such file or directory' /tmp/link.err
printf 'changed\n' >> /workspace/canary.txt
printf 'created\n' > /workspace/created.txt
[ "$(stat -c %u /workspace/created.txt)" = 10001 ]
printf 'AJ_FS_01=PASS\n'
[ "$(stat -c %u /readonly-probe)" = 10001 ]
[ "$(stat -c %a /readonly-probe)" = 755 ]
if (printf forbidden > /readonly-probe/blocked) 2>/tmp/root.err; then exit 43; else root_status=$?; fi
grep -q 'Read-only file system' /tmp/root.err
awk '$5 == "/" && $6 ~ /(^|,)ro(,|$)/ {ok=1} END {if (!ok) exit 1}' /proc/self/mountinfo
awk '$5 == "/tmp" && / - tmpfs / && /noexec/ && /nosuid/ && /nodev/ && /size=262144k/ {ok=1} END {if (!ok) exit 1}' /proc/self/mountinfo
cp /usr/bin/env /tmp/noexec-probe
[ "$(stat -c %a /tmp/noexec-probe)" = 555 ]
[ "$(sha256sum /usr/bin/env | awk '{print $1}')" = "$(sha256sum /tmp/noexec-probe | awk '{print $1}')" ]
set +e
/tmp/noexec-probe > /tmp/noexec.out 2>/tmp/noexec.err
probe_status=$?
set -e
[ "$probe_status" = 126 ]
grep -q 'Permission denied' /tmp/noexec.err
/usr/bin/env > /tmp/environment
printf 'temporary\n' > /tmp/lifecycle-canary
printf 'AJ_FS_02=PASS\n'
if grep -q '^CREDPAY_AJ_FAKE_SENTINEL=' /tmp/environment; then exit 44; fi
if grep -Fq "$nonce" /tmp/environment; then exit 45; fi
awk -F= '$1 !~ /^(HOME|LANG|LC_ALL|PATH|HOSTNAME|PWD)$/ {exit 1}' /tmp/environment
printf 'AJ_SEC_01=PASS\n'
[ "$(id -u)" = 10001 ]
[ "$(id -g)" = 10001 ]
awk '/^Cap(Inh|Prm|Eff|Bnd|Amb):/ {if ($2 !~ /^0+$/) exit 1; count++} /^NoNewPrivs:/ {nnp=$2} END {if (count != 5 || nnp != 1) exit 1}' /proc/self/status
mkdir /tmp/privilege-target
if mount -i -t tmpfs none /tmp/privilege-target 2>/tmp/privilege.err; then exit 46; else mount_status=$?; fi
grep -Eqi 'permission denied|must be superuser|operation not permitted' /tmp/privilege.err
printf 'AJ_PRIV_01=PASS\n'
printf 'MEMORY_MAX=%s\n' "$(awk '{print $1}' /sys/fs/cgroup/memory.max)"
printf 'SWAP_MAX=%s\n' "$(awk '{print $1}' /sys/fs/cgroup/memory.swap.max)"
printf 'PIDS_MAX=%s\n' "$(awk '{print $1}' /sys/fs/cgroup/pids.max)"
printf 'CPU_MAX=%s\n' "$(awk '{print $1 " " $2}' /sys/fs/cgroup/cpu.max)"
printf 'PID_NAMESPACE=%s\n' "$(stat -L -c %i /proc/self/ns/pid)"
printf 'PID_NAMESPACE_DEVICE=%s\n' "$(stat -L -c %d /proc/self/ns/pid)"
printf 'OUTSIDE_READ_EXIT=%s\nLINK_READ_EXIT=%s\nROOT_WRITE_EXIT=%s\nTMP_EXEC_EXIT=%s\nPRIV_MOUNT_EXIT=%s\n' "$outside_status" "$link_status" "$root_status" "$probe_status" "$mount_status"
awk 'BEGIN {while (1) {}}' &
probe_child=$!
kill -0 "$probe_child"
printf 'CHILD_STARTED=PASS\nCHILD_PID=%s\n' "$probe_child"
printf 'OBSERVATIONS_READY=PASS\n'
while [ ! -e /workspace/release ]; do :; done
'''

SECOND_SCRIPT = r'''set -eu
grep -qx changed /workspace/canary.txt
grep -qx created /workspace/created.txt
[ ! -e /tmp/lifecycle-canary ]
[ ! -e /tmp/noexec-probe ]
printf 'LIFECYCLE_RECREATED=PASS\nPID_NAMESPACE=%s\n' "$(stat -L -c %i /proc/self/ns/pid)"
printf 'PID_NAMESPACE_DEVICE=%s\n' "$(stat -L -c %d /proc/self/ns/pid)"
'''

FIRST_EXPECTED = {"AJ_FS_01": "PASS", "AJ_FS_02": "PASS", "AJ_SEC_01": "PASS", "AJ_PRIV_01": "PASS",
                  "MEMORY_MAX": "1073741824", "SWAP_MAX": "0", "PIDS_MAX": "128", "CPU_MAX": "100000 100000",
                  "PID_NAMESPACE": None, "CHILD_STARTED": "PASS", "CHILD_PID": None,
                  "PID_NAMESPACE_DEVICE": None, "OBSERVATIONS_READY": "PASS",
                  "OUTSIDE_READ_EXIT": "2", "LINK_READ_EXIT": "2", "ROOT_WRITE_EXIT": None, "TMP_EXEC_EXIT": "126", "PRIV_MOUNT_EXIT": None}


def parse_observations(text, expected):
    result = {}
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if not separator or key not in expected or key in result:
            raise ValueError("Observação inesperada/duplicada ou inválida.")
        rule = expected[key]
        valid = re.fullmatch(r"(?:0|[1-9][0-9]{0,19})", value) if rule is int else (
            re.fullmatch(r"[1-9][0-9]{0,19}", value) if rule is None else value == rule)
        if not valid:
            raise ValueError("Observação não comprovou o resultado esperado.")
        result[key] = value
    if set(result) != set(expected):
        raise ValueError("Observações incompletas.")
    return result


def validate_namespace_pair(first, second, simultaneous):
    if simultaneous is not True:
        raise ValueError("Comparação exige namespaces simultaneamente vivos.")
    if (first["PID_NAMESPACE_DEVICE"], first["PID_NAMESPACE"]) == (second["PID_NAMESPACE_DEVICE"], second["PID_NAMESPACE"]):
        raise ValueError("Namespace de processos não foi distinguido.")
    return True


def validate_worker(data, identity, marker, image, workspace):
    return _validate_restrictions(data, identity, marker, image, workspace, "none")


def _validate_restrictions(data, identity, marker, image, workspace, network):
    workspace = Path(workspace).resolve()
    own_root = (ROOT / ".local/agent-sandbox").resolve()
    if not own_root.is_relative_to(ROOT) or not workspace.is_relative_to(own_root) or workspace.name != "workspace":
        raise ValueError("Bind deve ser fixture própria do workspace.")
    config, host = data["Config"], data["HostConfig"]
    if data["Id"] != identity or data["Image"] != image or data["Name"] != "/" + marker or config["Labels"].get(LABEL) != marker:
        raise ValueError("Worker não pertence a esta execução/imagem.")
    if config["User"] != "10001:10001" or len(config["Env"]) != 4 or set(config["Env"]) != {"PATH=/usr/bin", "HOME=/tmp", "LANG=C", "LC_ALL=C"}:
        raise ValueError("Identidade/ambiente do worker diverge.")
    if host["Privileged"] is not False or host["ReadonlyRootfs"] is not True or set(host["CapDrop"] or []) != {"ALL"} or host.get("CapAdd") or host["SecurityOpt"] != ["no-new-privileges:true"]:
        raise ValueError("Privilégios/rootfs do worker divergem.")
    if host["NetworkMode"] != network or host["PidMode"] != "" or host["UTSMode"] != "" or host["IpcMode"] != "private" or host["CgroupnsMode"] != "private":
        raise ValueError("Namespaces/rede do worker divergem.")
    limits = {"NanoCpus": 1_000_000_000, "Memory": 1_073_741_824, "MemorySwap": 1_073_741_824, "PidsLimit": 128}
    if any(host[key] != expected for key, expected in limits.items()):
        raise ValueError("Limites do worker divergem.")
    if any(host.get(key) for key in ("Devices", "DeviceRequests", "DeviceCgroupRules", "VolumesFrom", "Links", "PortBindings", "PublishAllPorts")):
        raise ValueError("Devices/portas/recursos adicionais não aprovados.")
    flags = {"rw", "noexec", "nosuid", "nodev", "size=256m"}
    if set(host["Tmpfs"]) != {"/tmp"} or set(host["Tmpfs"]["/tmp"].split(",")) != flags:
        raise ValueError("Tmpfs do worker diverge.")
    binds = []
    for mount in data["Mounts"]:
        if mount["Type"] == "tmpfs" and mount["Destination"] == "/tmp":
            continue
        if mount["Type"] != "bind" or mount["Source"] != str(workspace) or mount["Destination"] != "/workspace" or mount["RW"] is not True or mount["Propagation"] != "rprivate":
            raise ValueError("Mount do worker não aprovado.")
        binds.append(mount)
    if len(binds) != 1:
        raise ValueError("Worker exige exatamente um bind próprio.")
    return {"user": config["User"], "readonly": True, "network": network, "capDrop": ["ALL"], "noNewPrivileges": True,
            "cpu": 1, "memoryBytes": limits["Memory"], "memorySwapBytes": limits["MemorySwap"], "pidsLimit": 128,
            "tmpfs": sorted(flags), "workspaceSource": workspace.relative_to(ROOT).as_posix(), "workspaceDestination": "/workspace",
            "pidNamespace": "private", "ipcNamespace": "private", "cgroupNamespace": "private", "environmentNames": ["HOME", "LANG", "LC_ALL", "PATH"]}


def _sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _inspect(identity, marker, image, workspace):
    data = json.loads(docker("container", "inspect", identity, timeout=5))[0]
    selected = validate_worker(data, identity, marker, image, workspace)
    return data, selected


def successful_exit(state, returncode, allow_child_oom=False):
    return (returncode == 0 and state["Status"] == "exited" and state["ExitCode"] == 0 and state["Pid"] == 0
            and (not state["OOMKilled"] or allow_child_oom is True))


def _worker(image, marker, workspace, script, expected, hold=False, timeout=30, allow_child_oom=False):
    identity = docker("create", "--name", marker, "--label", f"{LABEL}={marker}", "--user", "10001:10001",
                      "--network", "none", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                      "--cpus", "1", "--memory", "1g", "--memory-swap", "1g", "--pids-limit", "128",
                      "--ipc", "private", "--cgroupns", "private", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=256m",
                      "--mount", f"type=bind,source={workspace},target=/workspace,bind-propagation=rprivate",
                      "--entrypoint", "/usr/bin/sh", image, "-c", script, timeout=10)
    if not re.fullmatch(r"[a-f0-9]{64}", identity):
        raise ValueError("Identidade de worker inválida.")
    release = workspace / "release"
    release_created = False
    live_control = None
    try:
        before, configuration = _inspect(identity, marker, image, workspace)
        assert_never_started(before)
        try:
            if hold:
                docker("start", identity, timeout=5)
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    current, _ = _inspect(identity, marker, image, workspace)
                    output = docker("logs", identity, timeout=2)
                    if current["State"]["Status"] != "running":
                        raise ValueError("Primeiro worker terminou antes do controle simultâneo.")
                    if output.endswith("OBSERVATIONS_READY=PASS"):
                        first_live = parse_observations(output, expected)
                        break
                    time.sleep(0.05)
                else:
                    raise ValueError("Primeiro worker não produziu observações no prazo de10s.")
                observer_script = "printf 'NAMESPACE_CONTROL=PASS\\nPID_NAMESPACE=%s\\nPID_NAMESPACE_DEVICE=%s\\n' \"$(stat -L -c %i /proc/self/ns/pid)\" \"$(stat -L -c %d /proc/self/ns/pid)\""
                live_control = _worker(image, marker + "-observer", workspace, observer_script,
                                       {"NAMESPACE_CONTROL": "PASS", "PID_NAMESPACE": None, "PID_NAMESPACE_DEVICE": None}, timeout=5)
                still_live, _ = _inspect(identity, marker, image, workspace)
                if not still_live["State"]["Running"] or still_live["State"]["StartedAt"] != current["State"]["StartedAt"] or still_live.get("RestartCount", 0):
                    raise ValueError("Primeiro worker não permaneceu vivo durante o controle.")
                validate_namespace_pair(first_live, live_control["observations"], True)
                descriptor = os.open(release, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                os.close(descriptor)
                release_created = True
                result = subprocess.run(["docker", "wait", identity], cwd=ROOT, capture_output=True, text=True, timeout=10)
                output = docker("logs", identity, timeout=2)
            else:
                result = subprocess.run(["docker", "start", "--attach", identity], cwd=ROOT, capture_output=True, text=True, timeout=timeout)
                output = result.stdout
        except subprocess.TimeoutExpired:
            raise RuntimeError("Operação do protocolo excedeu seu timeout externo; cleanup próprio obrigatório.") from None
        after, _ = _inspect(identity, marker, image, workspace)
        state = after["State"]
        if not successful_exit(state, result.returncode, allow_child_oom):
            markers = [line for line in output.splitlines() if re.fullmatch(r"[A-Z_0-9]+=PASS", line)]
            reason = "permission" if "Permission denied" in result.stderr else "missing" if "No such file" in result.stderr else "unclassified"
            raise ValueError(f"Worker falhou: exit={state['ExitCode']}; marcadores={markers}; classe={reason}; saída integral omitida.")
        observations = parse_observations(output, expected)
        return {"configuration": configuration, "configurationSha256": _sha(json.dumps(configuration, sort_keys=True)),
                "command": script, "commandSha256": _sha(script), "exitCode": 0, "observations": observations, "stopped": True,
                "liveNamespaceControl": live_control, "oomKilled": state["OOMKilled"]}
    finally:
        _inspect(identity, marker, image, workspace)
        docker("rm", "--force", identity, timeout=10)
        if release_created:
            release.unlink()


def run_controls(image, folder, marker, commit):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Controles reais somente no CI Linux hospedado.")
    if shutil.which("setfacl") is None or shutil.which("git") is None:
        raise ValueError("Git/setfacl ausentes no controlador; nenhuma instalação automática.")
    folder = Path(folder).resolve() / "containment"
    if not folder.is_relative_to(ROOT) or not folder.is_relative_to((ROOT / ".local/agent-sandbox").resolve()):
        raise ValueError("Fixture fora do diretório próprio.")
    folder.mkdir(mode=0o700, exist_ok=False)
    workspace = folder / "workspace"
    workspace.mkdir(mode=0o700)
    nonce = marker + "-synthetic-canary"
    outside = folder / "outside.canary"
    outside.write_text(nonce, encoding="ascii")
    canary = workspace / "canary.txt"
    canary.write_text("baseline\n", encoding="ascii")
    (workspace / "outside-link").symlink_to("../outside.canary")
    if (workspace / "outside-link").read_text(encoding="ascii") != nonce:
        raise ValueError("Controle positivo do canário externo falhou.")
    subprocess.run(["setfacl", "-m", "u:10001:rwx", str(workspace)], capture_output=True, check=True, timeout=10)
    subprocess.run(["setfacl", "-m", "u:10001:rw", str(canary)], capture_output=True, check=True, timeout=10)
    git_environment = {"PATH": os.environ["PATH"], "HOME": str(folder), "LC_ALL": "C", "GIT_CONFIG_NOSYSTEM": "1",
                       "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null", "GIT_ATTR_NOSYSTEM": "1"}

    def git(*arguments):
        return subprocess.run(["git", "-c", "core.hooksPath=/dev/null", "-c", "core.attributesFile=/dev/null",
                               "-c", "commit.gpgsign=false", "-C", str(workspace), *arguments], env=git_environment,
                              capture_output=True, text=True, check=True, timeout=15).stdout.rstrip("\n")

    git("init", "--quiet", "--template=", "--initial-branch=fixture")
    git("add", "--", "canary.txt", "outside-link")
    git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "synthetic fixture")
    if git("status", "--porcelain"):
        raise ValueError("Fixture Git não começa limpa.")
    first_script = FIRST_SCRIPT.replace("@NONCE@", shlex.quote(nonce)).replace("@OUTSIDE@", shlex.quote(str(outside)))
    sentinel = "CREDPAY_AJ_FAKE_SENTINEL"
    if sentinel in os.environ:
        raise ValueError("Sentinela exclusiva já existe no controlador.")
    os.environ[sentinel] = nonce
    try:
        first = _worker(image, marker + "-first", workspace, first_script, FIRST_EXPECTED, hold=True)
    finally:
        del os.environ[sentinel]
    if canary.read_text(encoding="ascii") != "baseline\nchanged\n" or (workspace / "created.txt").read_text(encoding="ascii") != "created\n" or (workspace / "created.txt").stat().st_uid != 10001:
        raise ValueError("Alteração/ownership reais do bind não comprovados.")
    if outside.read_text(encoding="ascii") != nonce:
        raise ValueError("Canário externo foi alterado.")
    status = git("status", "--porcelain", "--untracked-files=all")
    diff = git("diff", "--no-ext-diff", "--no-textconv", "--", "canary.txt")
    if set(status.splitlines()) != {" M canary.txt", "?? created.txt"} or "\n+changed" not in diff:
        raise ValueError("Git não detectou exatamente as alterações autorizadas.")
    second = _worker(image, marker + "-second", workspace, SECOND_SCRIPT, {"LIFECYCLE_RECREATED": "PASS", "PID_NAMESPACE": None, "PID_NAMESPACE_DEVICE": None})
    versions = {"docker": docker("version", "--format", "{{.Server.Version}}|{{.Server.APIVersion}}|{{.Server.Os}}|{{.Server.Arch}}"),
                "hostKernel": platform.release(), "hostArchitecture": platform.machine(), "python": platform.python_version(),
                "git": subprocess.run(["git", "--version"], capture_output=True, text=True, check=True, timeout=10).stdout.strip(),
                "setfacl": subprocess.run(["setfacl", "--version"], capture_output=True, text=True, check=True, timeout=10).stdout.strip(),
                "dockerDesktop": "NOT_TESTED"}
    evidence = [
        {"id": "AJ-FS-01", "expected": "Somente bind da fixture, canário externo inacessível", "observed": "Bind único validado; escrita interna PASS; path externo e symlink ENOENT; canário host preservado", "positive": "Host lê canário pelo symlink; worker altera arquivo interno"},
        {"id": "AJ-FS-02", "expected": "RootfsRO/tmpfsnoexec e descarte", "observed": "Owned10001/mode755 negou EROFS; root mountro; ELF idêntico555 em/tmp negou126/Permissiondenied; mountflags e256MiB conferidos; recriação sem temporários", "positive": "ELF original funciona; cópia/hash/bits conferidos; escrita/tmp funciona"},
        {"id": "AJ-SEC-01", "expected": "Sentinela falsa do controlador ausente", "observed": "Nome/valor ausentes; Config.Env allowlist exata; env runtime só nomes previstos", "positive": "Sentinela definida no controlador; LC_ALL/PATH funcionais no worker"},
        {"id": "AJ-PRIV-01", "expected": "UID/GID10001/capszero/NNP1/operação privilegiada negada", "observed": "id e cinco masks em/proc conferidos; NNP1; mounttmpfs em alvo válido negado por permissão", "positive": "mount lista mounts; tmpfs presente; diretório alvo criado"},
        {"id": "AJ-LIFE-01", "expected": "Bind persiste/tmp e processo efêmeros", "observed": "Filho vivo antes da saídaPID1; namespaces(device+inode) distintos em controle simultâneo; primeiro container parado/removido, recriação preserva bind e limpa/tmp", "positive": "Primeira execução criou canários; observador durante sua vida e recriação posterior verificam isolamento/persistência"},
        {"id": "AJ-SCOPE-01", "expected": "Git detecta alteração autorizada", "observed": "Status exatoMcanary.txt/??created.txt e diff+changed; fixture inicia limpa", "positive": "Commit sintético inicial e leitura host dos conteúdos alterados"}]
    for item in evidence:
        item.update({"conclusion": "PASS", "exitCodes": [0, 0], "commandReferences": ["first", "second"], "limitation": "Linux CI; nenhuma prova DockerDesktop ou contenção do agente"})
        if item["id"] == "AJ-LIFE-01":
            item["limitation"] += "; encerramento do filho inferido pelo término do namespace/PID1, sem inspeção independente no host"
    return {"scope": "PARTIAL_CORE_CONTAINMENT_NOT_AGENT_ISOLATION", "commit": commit, "imageId": image,
            "createdUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "platform": versions,
            "fixtureRelative": folder.relative_to(ROOT).as_posix(), "executedBy": "controlador CI confiável",
            "review": "Revisão do incremento/artefato registrada na PR/spec antes da integração",
            "first": first, "second": second, "evidence": evidence, "gitDiffSha256": _sha(diff),
            "pending": ["AJ-HOST-01 contato com daemon", "AJ-NET-01", "AJ-RES-01 excesso real", "AJ-TOOLS-01 instalação/download", "vulnerabilidades", "DockerDesktop"]}
