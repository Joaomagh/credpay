"""Controlador confiável do CI: cria/exporta origem, nunca inicia o container."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone

# -I ignora o diretório do script; adicionar somente a pasta deste código revisado.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from inventory_source import write_report

ROOT = Path(__file__).resolve().parents[2]
LABEL = "io.credpay.sandbox-source"


def docker(*arguments, timeout=180):
    result = subprocess.run(["docker", *arguments], cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Docker falhou na operação {arguments[0]}; saída integral não publicada.")
    return result.stdout.strip()


def validate_container(data, identity, marker, image):
    if data["Name"] != f"/{marker}" or data["Config"]["Labels"].get(LABEL) != marker:
        raise ValueError("Container não pertence a esta execução.")
    if data["Id"] != identity or data["Image"] != image:
        raise ValueError("Imagem ou ID do container diverge da origem.")
    if any(mount.get("Type") != "tmpfs" or mount.get("Destination") != "/tmp" for mount in data["Mounts"]):
        raise ValueError("Container de origem contém mount inesperado.")


def inspect_container(identity, marker, image):
    data = json.loads(docker("container", "inspect", identity))[0]
    validate_container(data, identity, marker, image)
    return data


def assert_never_started(data):
    state = data["State"]
    if state["Status"] != "created" or state["Running"] or state["StartedAt"] != "0001-01-01T00:00:00Z":
        raise ValueError("Container de origem foi iniciado; inventário recusado.")


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Execução somente no runner Linux hospedado e descartável do GitHub.")
    commit = os.environ.get("GITHUB_SHA", "")
    run, attempt = os.environ.get("GITHUB_RUN_ID", ""), os.environ.get("GITHUB_RUN_ATTEMPT", "")
    if not re.fullmatch(r"[a-f0-9]{40}", commit) or not re.fullmatch(r"[0-9]+", run) or not re.fullmatch(r"[0-9]+", attempt):
        raise ValueError("Identidade do job inválida.")
    marker = f"credpay-source-{run}-{attempt}"
    folder = ROOT / ".local" / "agent-sandbox" / marker
    if not folder.resolve().is_relative_to(ROOT):
        raise ValueError("Saída fora do workspace.")
    folder.mkdir(parents=True, exist_ok=False)
    baseline = dict(line.split("=", 1) for line in (Path(__file__).parent / "baseline.env").read_text().splitlines() if line and not line.startswith("#"))
    source, platform = baseline["SANDBOX_SOURCE_IMAGE"], baseline["SANDBOX_SOURCE_PLATFORM_DIGEST"]
    if not re.fullmatch(r"debian:trixie-slim@sha256:[a-f0-9]{64}", source) or not re.fullmatch(r"sha256:[a-f0-9]{64}", platform):
        raise ValueError("Baseline inválida.")
    docker("pull", "--platform=linux/amd64", source)
    image = json.loads(docker("image", "inspect", source))[0]
    docker("pull", "--platform=linux/amd64", f"debian@{platform}")
    selected = json.loads(docker("image", "inspect", f"debian@{platform}"))[0]
    if image["Id"] != selected["Id"] or image["Os"] != "linux" or image["Architecture"] != "amd64" or image["Config"].get("Volumes"):
        raise ValueError("Identidade/plataforma/volumes da origem divergem.")
    identity = None
    try:
        identity = docker("create", "--name", marker, "--label", f"{LABEL}={marker}",
                          "--user", "10001:10001", "--network", "none", "--read-only",
                          "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                          "--cpus", "1", "--memory", "1g", "--memory-swap", "1g", "--pids-limit", "128",
                          "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=256m",
                          image["Id"], "/bin/sh")
        if not re.fullmatch(r"[a-f0-9]{64}", identity):
            raise ValueError("Identidade de container inválida.")
        before = inspect_container(identity, marker, image["Id"])
        assert_never_started(before)
        docker("export", "--output", str(folder / "source.tar"), identity)
        after = inspect_container(identity, marker, image["Id"])
        assert_never_started(after)
        metadata = {"sourceImage": source, "platformDigest": platform, "imageId": image["Id"],
                    "commit": commit, "platform": "linux/amd64", "containerNeverStarted": True,
                    "createdUtc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
        (folder / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
        report = write_report(folder / "source.tar", folder / "metadata.json", folder / "reports")
        print(f"SOURCE_ONLY: {len(report['packages'])} pacotes; {len(report['entries'])} entradas; container nunca iniciado.")
    finally:
        if identity and re.fullmatch(r"[a-f0-9]{64}", identity):
            inspect_container(identity, marker, image["Id"])
            docker("rm", "--force", identity)
            print("Cleanup do único container próprio concluído.")


if __name__ == "__main__":
    main()
