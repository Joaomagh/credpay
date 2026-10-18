"""Inventário de vulnerabilidades dos componentes parciais, no controlador CI."""

from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
from urllib.parse import quote
from urllib.request import urlopen

VERSION = "0.75.0"
ARCHIVE_SHA256 = "c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f"
SCANNER_BINARY_BYTES = 172765344
SEVERITIES = ("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL")


def split_version(value):
    if not isinstance(value, str) or not re.fullmatch(r"(?:[0-9]+:)?[0-9][A-Za-z0-9.+~:-]*", value):
        raise ValueError("Versão Debian inválida.")
    epoch, version = value.split(":", 1) if ":" in value else ("0", value)
    upstream, separator, release = version.rpartition("-")
    if not separator:
        upstream, release = version, ""
    if not re.fullmatch(r"[0-9][A-Za-z0-9.+~:-]*", upstream) or separator and not re.fullmatch(r"[A-Za-z0-9+.~]+", release):
        raise ValueError("Revisão Debian inválida.")
    return int(epoch), upstream, release


def make_noble_sbom(packages):
    return _make_sbom(packages, "ubuntu", "24.04")


def make_sbom(packages):
    return _make_sbom(packages, "debian", "13")


def _make_sbom(packages, family, version):
    if not isinstance(packages, list) or not 0 < len(packages) <= 128:
        raise ValueError("Componentes parciais ausentes ou excedidos.")
    os_ref = f"os:{family}:{version}"
    components = [{"type": "operating-system", "bom-ref": os_ref, "name": family, "version": version}]
    names, refs = set(), []
    for package in sorted(packages, key=lambda item: item["name"]):
        for key in ("name", "sourceName"):
            if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", package.get(key, "")):
                raise ValueError("Identidade Debian inválida.")
        if package["name"] in names or package.get("architecture") not in ("amd64", "all"):
            raise ValueError("Componente duplicado ou arquitetura inválida.")
        names.add(package["name"])
        split_version(package.get("version"))
        epoch, upstream, release = split_version(package.get("sourceVersion"))
        ref = "binary:" + package["name"]
        refs.append(ref)
        properties = {"SrcName": package["sourceName"], "SrcEpoch": str(epoch), "SrcVersion": upstream, "SrcRelease": release}
        components.append({"type": "library", "bom-ref": ref, "name": package["name"], "version": package["version"],
            "purl": f"pkg:deb/{family}/{quote(package['name'], safe='')}@{quote(package['version'], safe='')}?arch={package['architecture']}&distro={family}-{version}",
            "properties": [{"name": "aquasecurity:trivy:" + key, "value": value} for key, value in properties.items()]})
    return {"bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1, "components": components,
            "dependencies": [{"ref": os_ref, "dependsOn": refs}]}


def validate_noble_report(raw, scanner, packages):
    return _validate_report(raw, scanner, packages, "ubuntu", "24.04")


def validate_report(raw, scanner, packages):
    return _validate_report(raw, scanner, packages, "debian", "13")


def _validate_report(raw, scanner, packages, family, version):
    _make_sbom(packages, family, version)
    if scanner.get("Version") != VERSION:
        raise ValueError("Versão do scanner divergente.")
    database = scanner.get("VulnerabilityDB", {})
    if database.get("Version") != 2:
        raise ValueError("Metadados da base ausentes.")
    try:
        updated, downloaded, next_update = (datetime.fromisoformat(database[key].replace("Z", "+00:00"))
                                            for key in ("UpdatedAt", "DownloadedAt", "NextUpdate"))
        now = datetime.now(timezone.utc)
        if not updated <= downloaded <= now + timedelta(minutes=5) or not updated < next_update or next_update < now:
            raise ValueError("Base temporalmente inconsistente ou vencida.")
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("Metadados temporais da base inválidos.") from error
    if raw.get("SchemaVersion") != 2 or raw.get("Metadata", {}).get("OS", {}).get("Family") != family or raw.get("Metadata", {}).get("OS", {}).get("Name") != version:
        raise ValueError("Identidade OS ausente ou divergente.")
    results = raw.get("Results", [])
    if len(results) != 1 or results[0].get("Class") != "os-pkgs" or results[0].get("Type") != family:
        raise ValueError("Cobertura Debian ausente ou divergente.")
    actual = results[0].get("Packages", [])
    expected = {item["name"]: item for item in packages}
    if len(actual) != len(expected) or len({item.get("Name") for item in actual}) != len(actual):
        raise ValueError("Cobertura de componentes incompleta ou duplicada.")
    for item in actual:
        target = expected.get(item.get("Name"))
        if target is None or item.get("Version") != target["version"] or item.get("Arch") != target["architecture"]:
            raise ValueError("Identidade binária divergente no scan.")
        source = (item.get("SrcEpoch", 0), item.get("SrcVersion", ""), item.get("SrcRelease", ""))
        if item.get("SrcName") != target["sourceName"] or source != split_version(target["sourceVersion"]):
            raise ValueError("Identidade fonte divergente no scan.")
    counts, vulnerabilities = dict.fromkeys(SEVERITIES, 0), []
    for finding in results[0].get("Vulnerabilities", []) or []:
        target = expected.get(finding.get("PkgName"))
        severity = finding.get("Severity")
        if target is None or finding.get("InstalledVersion") != target["version"] or severity not in counts or not finding.get("VulnerabilityID"):
            raise ValueError("Achado inválido ou sem vínculo com componente.")
        selected = {}
        for key in ("VulnerabilityID", "PkgName", "InstalledVersion", "FixedVersion", "Severity", "Title", "Status", "PrimaryURL"):
            if key in finding:
                if not isinstance(finding[key], str) or len(finding[key]) > 4096:
                    raise ValueError("Campo de achado inválido.")
                selected[key] = finding[key]
        counts[severity] += 1
        vulnerabilities.append(selected)
    return {"scope": "PARTIAL_COMPONENT_ADVISORIES_NOT_FULL_IMAGE_ACCEPTANCE", "os": {"family": family, "version": version},
            "packageCount": len(actual), "packages": packages, "scanner": {"Version": VERSION,
                "VulnerabilityDB": {key: database[key] for key in ("Version", "UpdatedAt", "DownloadedAt", "NextUpdate")}},
            "severityCounts": counts, "vulnerabilities": vulnerabilities,
            "baseline": "REQUIRES_REMEDIATION_OR_EXPLICIT_DISPOSITION" if counts["HIGH"] or counts["CRITICAL"] else "NO_HIGH_CRITICAL_IN_THIS_COMPONENT_SCAN"}


def run_scan(projection, folder, image, commit, noble_candidate=False):
    if type(noble_candidate) is not bool:
        raise ValueError("Perfil de scan inválido.")
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Scanner somente no controlador Linux CI hospedado.")
    root = Path(__file__).resolve().parents[2]
    folder = Path(folder).resolve() / "component-scan"
    if not folder.is_relative_to((root / ".local/agent-sandbox").resolve()):
        raise ValueError("Scanner fora do diretório próprio.")
    folder.mkdir(exist_ok=False)
    sbom = (make_noble_sbom if noble_candidate else make_sbom)(projection["packages"])
    content = (json.dumps(sbom, indent=2) + "\n").encode()
    sbom_path = folder / "components.cdx.json"
    sbom_path.write_bytes(content)
    archive_url = f"https://github.com/aquasecurity/trivy/releases/download/v{VERSION}/trivy_{VERSION}_Linux-64bit.tar.gz"
    maximum = 128 * 1024 * 1024
    with urlopen(archive_url, timeout=60) as response:
        archive = response.read(maximum + 1)
    if len(archive) > maximum or hashlib.sha256(archive).hexdigest() != ARCHIVE_SHA256:
        raise ValueError("Arquivo do scanner diverge da baseline.")
    binary = folder / "trivy"
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as stream:
        matches = [member for member in stream.getmembers() if member.name == "trivy"]
        if len(matches) != 1 or not matches[0].isfile() or matches[0].size != SCANNER_BINARY_BYTES:
            selected = [(member.size, member.isfile()) for member in matches]
            raise ValueError(f"Binário do scanner inválido: membros trivy={len(matches)}; tamanho/regular={selected}; esperado={SCANNER_BINARY_BYTES}.")
        binary.write_bytes(stream.extractfile(matches[0]).read())
    binary.chmod(0o700)
    config, ignore, cache = folder / "empty-config.yaml", folder / "empty-ignore", folder / "cache"
    config.write_text("{}\n", encoding="utf-8")
    ignore.write_text("", encoding="utf-8")
    environment = {"PATH": "/usr/bin:/bin", "HOME": str(folder), "LANG": "C", "LC_ALL": "C"}

    def invoke(arguments, timeout):
        result = subprocess.run([str(binary), *arguments], cwd=folder, env=environment,
                                capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise ValueError("Scanner falhou operacionalmente; relatório não é válido; saída bruta omitida.")
        return result

    started = datetime.now(timezone.utc).isoformat()
    invoke(["image", "--config", str(config), "--cache-dir", str(cache), "--download-db-only", "--timeout", "5m", "--no-progress"], 330)
    raw_path = folder / "raw.json"
    command = ["sbom", "--config", str(config), "--cache-dir", str(cache), "--scanners", "vuln", "--list-all-pkgs",
               "--format", "json", "--output", str(raw_path), "--ignorefile", str(ignore), "--exit-code", "0",
               "--skip-db-update", "--timeout", "5m", str(sbom_path)]
    result = invoke(command, 330)
    scanner = json.loads(invoke(["--cache-dir", str(cache), "version", "--format", "json"], 20).stdout)
    if raw_path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("Relatório excedeu limite.")
    raw = json.loads(raw_path.read_text(encoding="utf-8"))
    selected = (validate_noble_report if noble_candidate else validate_report)(raw, scanner, projection["packages"])
    database_bytes = (cache / "db/metadata.json").read_bytes()
    if json.loads(database_bytes) != scanner["VulnerabilityDB"]:
        raise ValueError("Metadados da base divergem do scanner.")
    selected.update({"commit": commit, "imageId": image, "startedUtc": started, "finishedUtc": datetime.now(timezone.utc).isoformat(),
        "sbomSha256": hashlib.sha256(content).hexdigest(), "databaseMetadataSha256": hashlib.sha256(database_bytes).hexdigest(),
        "scannerArchiveSha256": ARCHIVE_SHA256, "command": command, "exitCode": 0,
        "warnings": ["Third-party SBOM may lead to inaccurate vulnerability detection"] if "Third-party SBOM" in result.stderr else [],
        "limitations": ["SBOM próprio de componentes parciais; não equivale a inventário completo da imagem ou a prova de exploração",
                        "Camadas inferiores, ferramentas do controlador, firmware/kernel e conteúdo futuro do workspace não cobertos",
                        "Sem supressões/ignore-unfixed; HIGH/CRITICAL bloqueiam aceite da baseline, não a publicação do inventário"]})
    print(f"CORE_COMPONENT_SCAN: {selected['packageCount']} componentes; HIGH={selected['severityCounts']['HIGH']}; CRITICAL={selected['severityCounts']['CRITICAL']}; aceite separado.")
    return selected
