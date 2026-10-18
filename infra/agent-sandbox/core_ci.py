"""Construção offline e smoke do core no controlador confiável do CI."""

from datetime import datetime, timezone
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.request import urlopen

sys.path.insert(0, str(Path(__file__).resolve().parent))
from inventory_source import source_baseline
from project_core import audit_core, project
from containment import run_controls
from scan_core import run_scan
from cpu_controls import run_cpu
from memory_controls import run_memory
from pids_controls import run_pids
from network_controls import run_network
from source_ci import LABEL, ROOT, assert_never_started, docker, inspect_container

SMOKE = r'''set -eu
[ "$(id -u)" = 10001 ]
[ "$(id -g)" = 10001 ]
env > /tmp/environment
grep -qx 'LC_ALL=C' /tmp/environment
mkdir /tmp/diagnostic
cp /etc/passwd /tmp/diagnostic/first
mv /tmp/diagnostic/first /tmp/diagnostic/second
find /tmp/diagnostic -name second | grep -qx /tmp/diagnostic/second
[ "$(stat -c %u /tmp/diagnostic/second)" = 10001 ]
printf 'b\na\n' | sort > /tmp/sorted
[ "$(head -n 1 /tmp/sorted)" = a ]
[ "$(tail -n 1 /tmp/sorted)" = b ]
[ "$(wc -l < /tmp/sorted)" = 2 ]
[ "$(sed -n '1p' /tmp/sorted)" = a ]
[ "$(awk 'BEGIN {if (PROCINFO["version"] != "5.2.1") exit 1; print 20+22}')" = 42 ]
diff /etc/passwd /tmp/diagnostic/second
printf '/etc/passwd\n' | xargs grep -q 10001
mount | grep -q ' /tmp '
ps -p 1 -o uid= | awk '$1 != 10001 {exit 1} END {if (NR != 1) exit 1}'
sha256sum /etc/passwd > /tmp/hash
grep -Eq '^[0-9a-f]{64}  /etc/passwd$' /tmp/hash
printf 'SMOKE_CORE_OK\n'
'''


def validate_packages(packages, noble_candidate=False):
    if type(noble_candidate) is not bool:
        raise ValueError("Perfil de pacotes inválido.")
    expected = {"gawk", "libmpfr6", "libreadline8t64", "libsigsegv2"}
    if not noble_candidate:
        expected |= {"procps", "libproc2-0"}
    if not isinstance(packages, list) or len(packages) != len(expected) or {entry["name"] for entry in packages} != expected:
        raise ValueError("Pacotes divergem da baseline.")
    for entry in packages:
        if entry["architecture"] != "amd64" or not re.fullmatch(r"[a-f0-9]{64}", entry["sha256"]) or type(entry["size"]) is not int or not 0 < entry["size"] <= 2 * 1024 * 1024:
            raise ValueError("Identidade de pacote inválida.")
        path = entry["path"]
        if not re.fullmatch(r"pool/main/[A-Za-z0-9+._~/-]+", path) or ".." in path.split("/") or not path.endswith("_amd64.deb"):
            raise ValueError("Caminho de pacote inválido.")
        if not re.fullmatch(r"[0-9][A-Za-z0-9.+:~_-]*", entry["version"]):
            raise ValueError("Versão de pacote inválida.")
        if noble_candidate and entry["name"] == "gawk" and entry["version"] != "1:5.2.1-2ubuntu0.1":
            raise ValueError("GNUawk diverge do backport oficial fixado.")
    return True


def package_baseline(noble_candidate=False):
    if type(noble_candidate) is not bool:
        raise ValueError("Perfil de pacotes inválido.")
    name = "packages.noble.lock.json" if noble_candidate else "packages.lock.json"
    packages = json.loads((Path(__file__).parent / name).read_text(encoding="utf-8-sig"))["packages"]
    validate_packages(packages, noble_candidate)
    return packages, "https://archive.ubuntu.com/ubuntu/" if noble_candidate else "https://deb.debian.org/debian/"


def download_packages(folder, noble_candidate=False):
    packages, origin = package_baseline(noble_candidate)
    for entry in packages:
        path = entry["path"]
        target = folder / Path(path).name
        with urlopen(origin + path, timeout=60) as response:
            content = response.read(entry["size"] + 1)
        if len(content) != entry["size"] or hashlib.sha256(content).hexdigest() != entry["sha256"]:
            raise ValueError(f"Tamanho/hash de pacote divergente: {entry['name']}")
        target.write_bytes(content)
        target.chmod(0o600)
    (folder / "expected.tsv").write_text("".join(f"{entry['name']}\t{entry['version']}\tamd64\t{Path(entry['path']).name}\n" for entry in packages), encoding="utf-8")
    return packages


def create(image, marker, command):
    identity = docker("create", "--name", marker, "--label", f"{LABEL}={marker}",
                      "--user", "10001:10001", "--network", "none", "--read-only", "--cap-drop", "ALL",
                      "--security-opt", "no-new-privileges:true", "--cpus", "1", "--memory", "1g",
                      "--memory-swap", "1g", "--pids-limit", "128", "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=256m",
                      "--entrypoint", "/usr/bin/sh", image, "-c", command)
    if not re.fullmatch(r"[a-f0-9]{64}", identity):
        raise ValueError("Identidade de container inválida.")
    return identity


def export_stopped(image, marker, destination):
    identity = create(image, marker, "exit 0")
    try:
        assert_never_started(inspect_container(identity, marker, image))
        docker("export", "--output", str(destination), identity)
        assert_never_started(inspect_container(identity, marker, image))
    finally:
        inspect_container(identity, marker, image)
        docker("rm", "--force", identity)


def build(context, source, marker):
    docker("build", "--network=none", "--build-arg", f"SOURCE_IMAGE={source}", "--label", f"io.credpay.sandbox-build={marker}", "--tag", marker, str(context))
    data = json.loads(docker("image", "inspect", marker))[0]
    if data["Os"] != "linux" or data["Architecture"] != "amd64" or data["Config"].get("Volumes"):
        raise ValueError("Plataforma/volumes de imagem inválidos.")
    return data


def main(noble_candidate=False):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Construção somente no runner Linux hospedado e descartável.")
    run, attempt = os.environ.get("GITHUB_RUN_ID", ""), os.environ.get("GITHUB_RUN_ATTEMPT", "")
    if not re.fullmatch(r"[0-9]+", run) or not re.fullmatch(r"[0-9]+", attempt):
        raise ValueError("Identidade do job inválida.")
    baseline = source_baseline(noble_candidate)
    source_marker = f"credpay-noble-source-{run}-{attempt}" if noble_candidate else f"credpay-source-{run}-{attempt}"
    source_folder = ROOT / ".local" / "agent-sandbox" / source_marker
    if not source_folder.resolve().is_relative_to(ROOT):
        raise ValueError("Pasta de origem fora do workspace.")
    origin = json.loads((source_folder / "reports/source-inventory.json").read_text())
    if origin["scope"] != "SOURCE_ONLY_NOT_CORE_ACCEPTANCE" or origin["commit"] != os.environ.get("GITHUB_SHA"):
        raise ValueError("Inventário de origem não corresponde ao checkout.")
    if origin["sourceImage"] != baseline["SANDBOX_SOURCE_IMAGE"] or origin["platformDigest"] != baseline["SANDBOX_SOURCE_PLATFORM_DIGEST"]:
        raise ValueError("Inventário diverge da baseline.")
    if noble_candidate and origin.get("osRelease") != {"id": "ubuntu", "versionId": "24.04"}:
        raise ValueError("Origem candidata não comprova releaseUbuntu24.04.")
    with (source_folder / "source.tar").open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != origin["archiveSha256"]:
            raise ValueError("Export de origem mudou.")
    folder = source_folder / "core"
    folder.mkdir(exist_ok=False)
    if shutil.which("readelf") is None:
        raise ValueError("readelf ausente no controlador confiável; nenhuma instalação automática.")
    inspector = subprocess.run(["readelf", "--version"], capture_output=True, text=True, timeout=10, check=True).stdout.splitlines()[0]
    candidate = folder / "candidate"
    final = folder / "final"
    candidate.mkdir()
    final.mkdir()
    packages = download_packages(candidate, noble_candidate)
    (candidate / ".dockerignore").write_text("*\n!Dockerfile\n!*.deb\n!expected.tsv\n", encoding="utf-8")
    shutil.copyfile(Path(__file__).parent / "Dockerfile.candidate", candidate / "Dockerfile")
    built = []
    try:
        candidate_tag = f"credpay-core-candidate-{run}-{attempt}"
        candidate_image = build(candidate, origin["sourceImage"], candidate_tag)
        built.append(candidate_tag)
        export_stopped(candidate_image["Id"], candidate_tag + "-export", folder / "candidate.tar")
        projection = project(folder / "candidate.tar", final, packages)
        (final / ".dockerignore").write_text("*\n!Dockerfile\n!rootfs.tar\n", encoding="utf-8")
        shutil.copyfile(Path(__file__).parent / "Dockerfile", final / "Dockerfile")
        final_tag = f"credpay-core-{run}-{attempt}"
        final_image = build(final, origin["sourceImage"], final_tag)
        built.append(final_tag)
        config = final_image["Config"]
        if config["User"] != "10001:10001" or config["WorkingDir"] != "/workspace" or config["Entrypoint"] != ["/usr/bin/sh"] or set(config["Env"]) != {"PATH=/usr/bin", "HOME=/tmp", "LANG=C", "LC_ALL=C"}:
            raise ValueError("Configuração final diverge da baseline.")
        export_stopped(final_image["Id"], final_tag + "-export", folder / "final.tar")
        actual = audit_core(folder / "final.tar", projection)
        marker = final_tag + "-smoke"
        identity = create(final_image["Id"], marker, SMOKE)
        try:
            assert_never_started(inspect_container(identity, marker, final_image["Id"]))
            output = docker("start", "--attach", identity)
            state = inspect_container(identity, marker, final_image["Id"])["State"]
            if state["Status"] != "exited" or state["ExitCode"] != 0 or state["OOMKilled"] or output != "SMOKE_CORE_OK":
                raise ValueError("Smoke do core não comprovou os vinte comandos.")
        finally:
            inspect_container(identity, marker, final_image["Id"])
            docker("rm", "--force", identity)
        containment = run_controls(final_image["Id"], folder, final_tag + "-containment", origin["commit"])
        resource_cpu = run_cpu(final_image["Id"], ROOT / containment["fixtureRelative"] / "workspace", final_tag + "-cpu")
        resource_memory = run_memory(final_image["Id"], ROOT / containment["fixtureRelative"] / "workspace", final_tag + "-memory")
        resource_pids = run_pids(final_image["Id"], ROOT / containment["fixtureRelative"] / "workspace", final_tag + "-pids")
        network = run_network(final_image["Id"], ROOT / containment["fixtureRelative"] / "workspace", final_tag + "-network")
        component_scan = run_scan(projection, folder, final_image["Id"], origin["commit"], noble_candidate)
        report = {"scope": "CORE_BUILD_NOT_CONTAINMENT_ACCEPTANCE", "commit": origin["commit"],
                  "createdUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                  "sourceImage": origin["sourceImage"], "candidateImageId": candidate_image["Id"], "imageId": final_image["Id"],
                  "smoke": "PASS_20_COMMANDS", "inspector": inspector, "projection": projection, "actual": actual,
                  "containment": containment, "resourceCpu": resource_cpu, "resourceMemory": resource_memory,
                  "resourcePids": resource_pids, "network": network, "componentScan": component_scan}
        reports = folder / "reports"
        reports.mkdir()
        (reports / "core-inventory.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        (reports / "SUMMARY.md").write_text(f"# Construção e primeiros controles do core\n\nCommit: {origin['commit']}\n\n20 comandos, {len(projection['packages'])} componentes DEB parciais, {len(projection['files'])} arquivos selecionados.\n\nExport final corresponde ao manifesto e smoke passou. Seis controles FS/SEC/PRIV/LIFE/SCOPE passaram no LinuxCI com fixtures próprias. Inventário de advisories dos componentes parciais em componentScan, com cobertura/achados/limites; aceite separado. AJ-HOST/NET/RES/TOOLS completos e compatibilidade DockerDesktop continuam pendentes. Não aceita contenção do agente. Sem instalação íntegra dpkg; origem/arquivos preservados na proveniência.\n", encoding="utf-8")
        print(f"CORE_BUILD_ONLY: 20 comandos; {len(projection['files'])} arquivos; smoke e export exato PASS; negativos AJ-* pendentes.")
        print("CORE_CONTAINMENT_PARTIAL: AJ-FS-01/02, AJ-SEC-01, AJ-PRIV-01, AJ-LIFE-01 e AJ-SCOPE-01 PASS no LinuxCI; demais pendentes.")
    finally:
        for tag in reversed(built):
            image = json.loads(docker("image", "inspect", tag))[0]
            if image["Config"]["Labels"].get("io.credpay.sandbox-build") != tag:
                raise ValueError("Imagem de cleanup não pertence ao job.")
            docker("image", "rm", tag)
        print("Cleanup das imagens próprias concluído.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--noble-candidate", action="store_true", help="Construir e avaliar somente candidataNoble, sem promoção automática.")
    main(parser.parse_args().noble_candidate)
