"""Prova parcial TCP/UDP entre namespaces próprios, exclusivamente CI."""

from datetime import datetime, timezone
import hashlib
import json
import os
import re
import shlex
import subprocess
import time

from containment import _validate_restrictions, _worker, parse_observations, successful_exit, validate_worker
from source_ci import LABEL, ROOT, assert_never_started, docker

NS = {"NET_DEVICE": None, "NET_INODE": None}
NS_SCRIPT = r'''printf 'NET_DEVICE=%s\nNET_INODE=%s\n' "$(stat -L -c %d /proc/self/ns/net)" "$(stat -L -c %i /proc/self/ns/net)"'''


def validate_shared(client, fixture, identity, marker, fixture_id, fixture_marker, image, workspace):
    configuration = _validate_shared_owned(client, fixture, identity, marker, fixture_id, fixture_marker, image, workspace)
    if not fixture["State"]["Running"] or not fixture["State"]["StartedAt"] or fixture.get("RestartCount", 0):
        raise ValueError("Cliente diagnóstico exige fixture própria viva sem reinício.")
    return configuration


def _validate_shared_owned(client, fixture, identity, marker, fixture_id, fixture_marker, image, workspace):
    validate_worker(fixture, fixture_id, fixture_marker, image, workspace)
    if identity == fixture_id:
        raise ValueError("Cliente exige identidade própria distinta da fixture.")
    return _validate_restrictions(client, identity, marker, image, workspace, "container:" + fixture_id)


def validate_exchange(before, negative, after, fixture):
    namespace = lambda item: (item["NET_DEVICE"], item["NET_INODE"])
    for positive in (before, after):
        parse_observations("\n".join(f"{k}={v}" for k, v in positive.items()), {**NS, "IO_RESULT": "1", "IO_KIND": "ECHO"})
        if namespace(positive) != namespace(fixture):
            raise ValueError("Positivo não compartilha namespace da fixture viva.")
    parse_observations("\n".join(f"{k}={v}" for k, v in negative.items()), {**NS, "IO_RESULT": "-1", "IO_KIND": "REFUSED"})
    if namespace(negative) == namespace(fixture):
        raise ValueError("Negativo não está em namespace separado.")
    return True


def _create(image, marker, workspace, script, network="none"):
    if network != "none" and not re.fullmatch(r"container:[a-f0-9]{64}", network):
        raise ValueError("Modo diagnóstico não aprovado.")
    identity = docker("create", "--name", marker, "--label", f"{LABEL}={marker}", "--user", "10001:10001",
        "--network", network, "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
        "--cpus", "1", "--memory", "1g", "--memory-swap", "1g", "--pids-limit", "128",
        "--ipc", "private", "--cgroupns", "private", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=256m",
        "--mount", f"type=bind,source={workspace},target=/workspace,bind-propagation=rprivate",
        "--entrypoint", "/usr/bin/sh", image, "-c", script, timeout=10)
    if not re.fullmatch(r"[a-f0-9]{64}", identity):
        raise ValueError("ID diagnóstico inválido.")
    return identity


def _inspect(identity):
    return json.loads(docker("container", "inspect", identity, timeout=5))[0]


def _digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def client_script(protocol, token, positive):
    if protocol not in ("tcp", "udp") or not re.fullmatch(r"[a-z0-9-]+", token):
        raise ValueError("Protocolo/nonce fora da fixture.")
    program = r'''BEGIN {
      s="/inet4/" proto "/0/127.0.0.1/34567"
      PROCINFO[s,"NONFATAL"]=1; PROCINFO[s,"READ_TIMEOUT"]=1000
      ERRNO=""; print token |& s; error=ERRNO
      if (error == "") { result=(s |& getline reply); error=ERRNO } else result=-1
      if (positive) {
        if (result != 1 || reply != token || error != "") exit 31
        print "IO_RESULT=1\nIO_KIND=ECHO"
      } else {
        if (result != -1 || error != "Connection refused") exit 32
        print "IO_RESULT=-1\nIO_KIND=REFUSED"
      }
      close(s)
    }'''
    return "set -eu\nawk -v proto=" + protocol + " -v token=" + shlex.quote(token) + " -v positive=" + str(int(positive)) + " " + shlex.quote(program) + "\n" + NS_SCRIPT


def _run_protocol(protocol, image, workspace, marker):
    started = datetime.now(timezone.utc).isoformat()
    prefix = marker + "-synthetic"
    release = workspace / (marker + "-release")
    if release.exists():
        raise ValueError("Flag própria já existe.")
    program = r'''BEGIN {
      s="/inet4/" proto "/34567/0/0"; PROCINFO[s,"NONFATAL"]=1; PROCINFO[s,"READ_TIMEOUT"]=15000
      for (i=0;i<2;i++) {
        expected=prefix (i==0 ? "-before" : "-after")
        if ((s |& getline request) != 1 || request != expected) exit 41
        ERRNO=""; print request |& s
        if (ERRNO != "" || close(s) != 0) exit 42
      }
    }'''
    server_script = "set -eu\n" + NS_SCRIPT + "\nawk -v proto=" + protocol + " -v prefix=" + shlex.quote(prefix) + " " + shlex.quote(program)
    server_script += "\nwhile [ ! -e " + shlex.quote("/workspace/" + release.name) + " ]; do :; done\n"
    fixture_marker = marker + "-server"
    fixture_id = _create(image, fixture_marker, workspace, server_script)
    released = False
    initial_started = None

    def fixture(live=True):
        data = _inspect(fixture_id)
        configuration = validate_worker(data, fixture_id, fixture_marker, image, workspace)
        if live and (not data["State"]["Running"] or data.get("RestartCount", 0) or initial_started is not None and data["State"]["StartedAt"] != initial_started):
            raise ValueError("Fixture terminou/reiniciou durante protocolo.")
        return data, configuration

    def endpoint():
        state = "0A" if protocol == "tcp" else "07"
        probe = "awk " + shlex.quote('$2 ~ /:8707$/ && $4 == "' + state + '" {found=1} END {if(!found)exit 4;print "ENDPOINT_READY=PASS"}') + " /proc/net/" + protocol
        for _ in range(20):
            fixture()
            result = subprocess.run(["docker", "exec", "--user", "10001:10001", fixture_id, "sh", "-c", probe],
                                    cwd=ROOT, capture_output=True, text=True, timeout=3)
            if result.returncode == 0 and result.stdout.strip() == "ENDPOINT_READY=PASS":
                return {"command": probe, "commandSha256": _digest(probe), "exitCode": 0, "observed": "ENDPOINT_READY=PASS"}
            if result.returncode != 4:
                raise ValueError("Probe de endpoint falhou operacionalmente; não é recusa de rede.")
            time.sleep(0.05)
        raise ValueError("Endpoint não comprovado no prazo; inconclusivo.")

    def positive(suffix):
        current, _ = fixture()
        script = client_script(protocol, prefix + suffix, True)
        name = marker + suffix
        identity = _create(image, name, workspace, script, "container:" + fixture_id)
        try:
            before = _inspect(identity)
            configuration = validate_shared(before, current, identity, name, fixture_id, fixture_marker, image, workspace)
            assert_never_started(before)
            result = subprocess.run(["docker", "start", "--attach", identity], cwd=ROOT, capture_output=True, text=True, timeout=5)
            current, _ = fixture()
            after = _inspect(identity)
            validate_shared(after, current, identity, name, fixture_id, fixture_marker, image, workspace)
            if not successful_exit(after["State"], result.returncode):
                raise ValueError("Intercâmbio positivo falhou; não comprova cliente funcional.")
            observations = parse_observations(result.stdout, {**NS, "IO_RESULT": "1", "IO_KIND": "ECHO"})
            return {"command": script, "commandSha256": _digest(script), "configuration": configuration,
                    "configurationSha256": _digest(json.dumps(configuration, sort_keys=True)), "exitCode": 0, "observations": observations}
        finally:
            current, _ = fixture(False)
            _validate_shared_owned(_inspect(identity), current, identity, name, fixture_id, fixture_marker, image, workspace)
            docker("rm", "--force", identity, timeout=10)

    try:
        before, configuration = fixture(False)
        assert_never_started(before)
        docker("start", fixture_id, timeout=5)
        initial_started = fixture()[0]["State"]["StartedAt"]
        endpoint()
        fixture_ns = parse_observations(docker("logs", fixture_id, timeout=2), NS)
        first = positive("-before")
        ready_before = endpoint()
        negative = _worker(image, marker + "-negative", workspace, client_script(protocol, prefix + "-before", False),
                           {**NS, "IO_RESULT": "-1", "IO_KIND": "REFUSED"}, timeout=5)
        ready_after = endpoint()
        last = positive("-after")
        fixture()
        validate_exchange(first["observations"], negative["observations"], last["observations"], fixture_ns)
        descriptor = os.open(release, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
        released = True
        if docker("wait", fixture_id, timeout=5) != "0":
            raise ValueError("Fixture não terminou normalmente.")
        return {"protocol": protocol, "conclusion": "PASS_NAMESPACE_ONLY", "startedUtc": started,
                "finishedUtc": datetime.now(timezone.utc).isoformat(), "fixtureId": fixture_id, "fixtureNamespace": fixture_ns,
                "fixtureConfiguration": configuration, "fixtureCommand": server_script, "fixtureCommandSha256": _digest(server_script),
                "before": first, "negative": negative, "after": last, "endpointBefore": ready_before, "endpointAfter": ready_after}
    finally:
        fixture(False)
        docker("rm", "--force", fixture_id, timeout=10)
        if released:
            release.unlink()


def run_network(image, workspace, marker):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Diagnóstico somente no CI Linux hospedado.")
    workspace = workspace.resolve()
    if not workspace.is_relative_to((ROOT / ".local/agent-sandbox").resolve()) or workspace.name != "workspace" or not re.fullmatch(r"credpay-core-[0-9]+-[0-9]+-network", marker):
        raise ValueError("Fixture/marcador fora da execução própria.")
    return {"scope": "PARTIAL_NAMESPACE_NOT_EGRESS_OR_AGENT_ACCEPTANCE",
            "protocols": [_run_protocol(protocol, image, workspace, marker + "-" + protocol) for protocol in ("tcp", "udp")],
            "pending": ["DNS", "LAN", "metadados", "internet/egress completo", "DockerDesktop", "advisories", "contenção do agente"]}
