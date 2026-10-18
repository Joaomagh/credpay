"""Inventário estático de origem: não extrai nem executa conteúdo do tar."""

import hashlib
import argparse
import json
from datetime import datetime
from pathlib import Path
import re
import tarfile

MAX_ENTRIES = 100_000
MAX_FILE = 128 * 1024 * 1024
MAX_TOTAL = 1024 * 1024 * 1024
INJECTED = {"etc/hostname", "etc/hosts", "etc/resolv.conf", ".dockerenv"}
TOOLS = "sh cp mv mkdir find stat grep sed awk sort head tail wc diff xargs id env mount ps sha256sum".split()


def _resolve(path, entries, executable=True):
    seen = set()
    for _ in range(64):
        if path in seen:
            return None
        seen.add(path)
        parts = path.split("/")
        for index in range(1, len(parts) + 1):
            prefix = "/".join(parts[:index])
            entry = entries.get(prefix)
            if entry and entry["type"] in ("symlink", "hardlink"):
                path = "/".join([entry["target"], *parts[index:]])
                break
        else:
            entry = entries.get(path)
            return path if entry and entry["type"] == "file" and (not executable or entry["executable"]) else None
    return None


def _tools(entries):
    lookup = {entry["path"]: entry for entry in entries}
    result = {}
    for tool in TOOLS:
        resolved = next((path for directory in ("usr/local/bin", "usr/bin", "bin", "usr/sbin", "sbin")
                         if (path := _resolve(f"{directory}/{tool}", lookup))), None)
        result[tool] = {"present": resolved is not None, "path": resolved}
    return result


def _text(value):
    if not isinstance(value, str) or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value):
        raise ValueError("Caminho inválido no inventário.")
    try:
        value.encode("utf-8", errors="strict")
    except UnicodeError as error:
        raise ValueError("Caminho inválido no inventário.") from error
    return value


def _path(name):
    _text(name)
    while name.startswith("./"):
        name = name[2:]
    parts = name.split("/")
    if name.startswith("/") or ".." in parts or len(name) > 4096:
        raise ValueError("Caminho inválido no inventário.")
    return "/".join(part for part in parts if part and part != ".") or "."


def _link_target(path, target, hardlink=False):
    _text(target)
    parts = [] if target.startswith("/") or hardlink else path.split("/")[:-1]
    for part in target.split("/"):
        if part == "..":
            if not parts:
                raise ValueError("Link fora da raiz virtual.")
            parts.pop()
        elif part and part != ".":
            parts.append(part)
    return "/".join(parts) or "."


def _digest(stream):
    result = hashlib.sha256()
    while chunk := stream.read(128 * 1024):
        result.update(chunk)
    return result.hexdigest()


def _packages(status, installed=True):
    result = []
    identities = set()
    for paragraph in status.split("\n\n"):
        fields = {}
        key = None
        for line in paragraph.splitlines():
            if line and line[0].isspace() and key == "Source":
                raise ValueError("Origem de pacote inválida.")
            if line and not line[0].isspace():
                if ":" not in line:
                    raise ValueError("Metadados de pacote inválidos.")
                key, value = line.split(":", 1)
                if key in fields:
                    raise ValueError("Campo de pacote duplicado.")
                fields[key] = value.strip()
        if fields:
            if installed and fields.get("Status") != "install ok installed":
                raise ValueError("Estado de pacote inválido.")
            if not re.fullmatch(r"[a-z0-9][a-z0-9+.-]*", fields.get("Package", "")) or not re.fullmatch(r"[0-9][A-Za-z0-9.+:~_-]*", fields.get("Version", "")) or fields.get("Architecture") not in ("amd64", "all"):
                raise ValueError("Identidade de pacote inválida.")
            identity = (fields["Package"], fields["Architecture"])
            if identity in identities:
                raise ValueError("Pacote duplicado.")
            identities.add(identity)
            source = re.fullmatch(r"([a-z0-9][a-z0-9+.-]*)(?: \(([0-9][A-Za-z0-9.+:~_-]*)\))?", fields.get("Source", fields["Package"]))
            if source is None:
                raise ValueError("Origem de pacote inválida.")
            result.append({"name": fields["Package"], "version": fields["Version"], "architecture": fields["Architecture"],
                           "sourceName": source[1], "sourceVersion": source[2] or fields["Version"]})
    if not result:
        raise ValueError("Inventário de pacotes vazio.")
    return sorted(result, key=lambda item: item["name"])


def inventory_files(archive):
    entries = []
    with tarfile.open(archive, "r:") as stream:
        members = {}
        total = 0
        for member in stream:
            path = _path(member.name)
            if path in members:
                raise ValueError("Entrada duplicada no inventário.")
            if len(members) >= MAX_ENTRIES or member.size < 0 or member.size > MAX_FILE:
                raise ValueError("Limite do inventário excedido.")
            total += member.size
            if total > MAX_TOTAL:
                raise ValueError("Limite do inventário excedido.")
            members[path] = member
        status_member = members.get("var/lib/dpkg/status")
        if status_member is not None and (not status_member.isfile() or status_member.size > 16 * 1024 * 1024):
            raise ValueError("Arquivo de pacotes ausente ou inválido.")
        status = stream.extractfile(status_member).read().decode("utf-8", errors="strict") if status_member else None
        for path, member in members.items():
            kind = "file" if member.isfile() else "directory" if member.isdir() else "symlink" if member.issym() else "hardlink" if member.islnk() else "character-device" if member.ischr() else "block-device" if member.isblk() else "fifo" if member.isfifo() else None
            if kind is None:
                raise ValueError("Tipo de entrada não suportado.")
            entry = {"path": path, "type": kind, "mode": format(member.mode, "04o"),
                     "uid": member.uid, "gid": member.gid, "size": member.size,
                     "setuid": bool(member.mode & 0o4000), "setgid": bool(member.mode & 0o2000),
                     "executable": member.isfile() and bool(member.mode & 0o111),
                     "fileCapability": any(key.endswith("security.capability") for key in member.pax_headers)}
            if member.isfile():
                entry["sha256"] = None if path in INJECTED else _digest(stream.extractfile(member))
                entry["injectedByDaemon"] = path in INJECTED
            if member.issym() or member.islnk():
                entry["target"] = _link_target(path, member.linkname, member.islnk())
            entries.append(entry)
    return {"status": status, "entries": sorted(entries, key=lambda item: item["path"]), "tools": _tools(entries)}


def inventory(archive):
    result = inventory_files(archive)
    status = result.pop("status")
    if status is None:
        raise ValueError("Arquivo de pacotes ausente ou inválido.")
    result["packages"] = _packages(status)
    return result


def source_baseline(noble_candidate=False):
    if type(noble_candidate) is not bool:
        raise ValueError("Perfil de origem inválido.")
    name = "baseline.noble.env" if noble_candidate else "baseline.env"
    baseline = dict(line.split("=", 1) for line in (Path(__file__).parent / name).read_text().splitlines() if line and not line.startswith("#"))
    prefix = r"ubuntu:24\.04" if noble_candidate else "debian:trixie-slim"
    if set(baseline) != {"SANDBOX_SOURCE_IMAGE", "SANDBOX_SOURCE_PLATFORM_DIGEST"} or not re.fullmatch(prefix + r"@sha256:[a-f0-9]{64}", baseline["SANDBOX_SOURCE_IMAGE"]) or not re.fullmatch(r"sha256:[a-f0-9]{64}", baseline["SANDBOX_SOURCE_PLATFORM_DIGEST"]):
        raise ValueError("Baseline inválida.")
    return baseline


def _noble_release(archive):
    with tarfile.open(archive, "r:*") as stream:
        member = next((item for item in stream.getmembers() if _path(item.name) == "usr/lib/os-release"), None)
        if member is None or not member.isfile() or member.size > 4096:
            raise ValueError("Release da candidata ausente ou inválido.")
        values = {}
        for line in stream.extractfile(member).read().decode("utf-8", errors="strict").splitlines():
            key, separator, value = line.partition("=")
            if key in ("ID", "VERSION_ID"):
                if not separator or key in values:
                    raise ValueError("Release da candidata ambíguo.")
                values[key] = value
        if values.get("ID") not in ('ubuntu', '"ubuntu"') or values.get("VERSION_ID") not in ('24.04', '"24.04"'):
            raise ValueError("Release da candidata diverge de Ubuntu24.04.")
    return {"id": "ubuntu", "versionId": "24.04"}


def write_noble_report(archive, metadata, output):
    return _write_report(archive, metadata, output, True)


def write_report(archive, metadata, output):
    return _write_report(archive, metadata, output, False)


def _write_report(archive, metadata, output, noble_candidate):
    root = Path(__file__).resolve().parents[2]
    archive, metadata, output = (Path(path).resolve() for path in (archive, metadata, output))
    if any(not path.is_relative_to(root) for path in (archive, metadata, output)):
        raise ValueError("Entradas e saída devem permanecer no workspace.")
    if output.exists():
        raise ValueError("Diretório de saída deve ser novo.")
    if metadata.stat().st_size > 8192:
        raise ValueError("Metadados excedem o limite.")
    data = json.loads(metadata.read_text(encoding="utf-8-sig"))
    baseline = source_baseline(noble_candidate)
    keys = {"sourceImage", "platformDigest", "imageId", "commit", "platform", "createdUtc", "containerNeverStarted"}
    if not isinstance(data, dict) or set(data) != keys:
        raise ValueError("Campos de metadados inválidos.")
    if data["sourceImage"] != baseline["SANDBOX_SOURCE_IMAGE"] or data["platformDigest"] != baseline["SANDBOX_SOURCE_PLATFORM_DIGEST"] or data["platform"] != "linux/amd64" or data["containerNeverStarted"] is not True:
        raise ValueError("Origem ou estado diverge da baseline.")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", data["imageId"]) or not re.fullmatch(r"[a-f0-9]{40}", data["commit"]) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", data["createdUtc"]):
        raise ValueError("Identidade de metadados inválida.")
    datetime.strptime(data["createdUtc"], "%Y-%m-%dT%H:%M:%SZ")
    with archive.open("rb") as stream:
        digest = _digest(stream)
    result = inventory(archive)
    if noble_candidate:
        result["osRelease"] = _noble_release(archive)
    with archive.open("rb") as stream:
        if _digest(stream) != digest:
            raise ValueError("Arquivo mudou durante o inventário.")
    result.update(data)
    result.update({"scope": "SOURCE_ONLY_NOT_CORE_ACCEPTANCE", "archiveSha256": digest})
    missing = ", ".join(name for name, value in result["tools"].items() if not value["present"]) or "nenhuma"
    summary = ("# Inventário estático da origem\n\n"
               "SOURCE ONLY — não aceita sandbox-core, contenção ou vulnerabilidades.\n\n"
               f"Commit: {data['commit']}\n\nOrigem: `{data['sourceImage']}`\n\n"
               f"Plataforma: `{data['platformDigest']}`\n\nExport SHA256: `{digest}`\n\n"
               f"Pacotes instalados: {len(result['packages'])}; entradas: {len(result['entries'])}.\n\n"
               f"Ferramentas aprovadas ausentes na origem: {missing}.\n\n"
               "Container criado e exportado sem iniciar processo. Configuração/Env e tar não publicados.\n")
    output.mkdir(parents=True, exist_ok=False)
    (output / "source-inventory.json").write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (output / "SUMMARY.md").write_text(summary, encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True)
    parser.add_argument("--metadata", required=True)
    parser.add_argument("--output", required=True)
    options = parser.parse_args()
    write_report(options.archive, options.metadata, options.output)
