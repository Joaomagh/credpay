"""Interfaces ACL observadas estaticamente no export próprio; nenhum ELF executado."""

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile

from inventory_source import _path
from project_core import parse_elf
from source_ci import ROOT

LEGACY = {"acl_get_file", "acl_set_file", "acl_extended_file", "acl_delete_def_file"}
LOOKUP = {"dlopen", "dlsym", "dlvsym"}
ROLES = {"command", "library", "loader"}
FLAGS = ["--file-header", "--program-headers", "--dynamic", "--dyn-syms", "--wide"]


def parse_acl_elf(text):
    if not isinstance(text, str) or len(text) > 2 * 1024 * 1024:
        raise ValueError("Saída readelf ausente ou excedida.")
    elf = parse_elf(text)
    tables = re.findall(r"^Symbol table '\.dynsym' contains ([0-9]+) entries:$", text, re.MULTILINE)
    if len(tables) != 1 or not 0 < int(tables[0]) <= 100_000:
        raise ValueError("Tabela dinâmica ausente/duplicada ou inválida.")
    rows = [line for line in text.splitlines() if re.match(r"^\s*[0-9]+:", line)]
    if len(rows) != int(tables[0]):
        raise ValueError("Tabela dinâmica incompleta.")
    acl, lookup, legacy, at, fd = [], [], [], [], []
    pattern = re.compile(r"^\s*([0-9]+):\s+([0-9a-fA-F]+)\s+(?:[0-9]+|0x[0-9a-fA-F]+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(UND|ABS|COM|[0-9]+)(?:\s+(\S+)(?:\s+\([0-9]+\))?)?\s*$")
    for index, row in enumerate(rows):
        match = pattern.fullmatch(row)
        if not match or int(match[1]) != index:
            raise ValueError("Linha dinâmica inválida/duplicada.")
        if match[3] not in {"NOTYPE", "OBJECT", "FUNC", "SECTION", "FILE", "COMMON", "TLS", "IFUNC"} or match[4] not in {"LOCAL", "GLOBAL", "WEAK", "UNIQUE"} or match[5] not in {"DEFAULT", "INTERNAL", "HIDDEN", "PROTECTED"}:
            raise ValueError("Tipo/binding/visibilidade de símbolo não reconhecido.")
        raw = match[7]
        if raw is None:
            if index != 0 or match.group(3, 4, 5) != ("NOTYPE", "LOCAL", "DEFAULT") or match[6] != "UND":
                raise ValueError("Símbolo dinâmico sem nome.")
            continue
        symbol = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_.$]*)(?:@{1,2}([A-Za-z0-9_.-]+))?", raw)
        if symbol is None:
            raise ValueError("Nome/versão do símbolo inválidos.")
        name, version = symbol.groups()
        undefined = match[6] == "UND"
        if name.startswith("acl_") or name.startswith("__acl_"):
            acl.append({"name": name, "version": version, "raw": raw, "undefined": undefined,
                        "kind": match[3], "binding": match[4], "visibility": match[5], "section": match[6]})
            if undefined and name in LEGACY:
                legacy.append(raw)
            if undefined and name.endswith("_at"):
                at.append(raw)
            if undefined and name.endswith("_fd"):
                fd.append(raw)
        if undefined and name in LOOKUP:
            lookup.append(raw)
    return {**elf, "dynamicSymbolCount": len(rows), "aclSymbols": acl,
            "legacyPathnameImports": sorted(legacy), "atImports": sorted(at), "fdImports": sorted(fd),
            "dynamicLookupImports": sorted(lookup)}


def map_acl_consumers(records, manifest):
    wanted = {entry["path"]: entry for entry in manifest["files"] if entry["role"] in ROLES}
    if not wanted or len(wanted) > 128 or len({record["path"] for record in records}) != len(records) or {record["path"] for record in records} != set(wanted):
        raise ValueError("Cobertura ELF ausente/duplicada/divergente.")
    by_path = {record["path"]: record for record in records}
    graph = {}
    for path, record in by_path.items():
        if _path(path) != path or any(record[key] != wanted[path][key] for key in ("sha256", "role", "package")):
            raise ValueError("Identidade do ELF diverge do manifesto.")
        needed = record["needed"]
        if any(not re.fullmatch(r"[A-Za-z0-9_+.-]+", name) or name.startswith(".") for name in needed):
            raise ValueError("Dependência ELF inválida.")
        edges = {"usr/lib/x86_64-linux-gnu/" + name for name in needed}
        if record.get("interpreter"):
            edges.add(record["interpreter"])
        if not edges.issubset(by_path):
            raise ValueError("Dependência fora da cobertura ELF.")
        graph[path] = edges
    providers = [record for record in records if record["package"] == "libacl1" and record["path"].endswith("/libacl.so.1")]
    if len(providers) != 1 or not LEGACY.issubset({symbol["name"] for symbol in providers[0]["aclSymbols"] if not symbol["undefined"] and symbol["kind"] in {"FUNC", "IFUNC"} and symbol["binding"] in {"GLOBAL", "WEAK"} and symbol["visibility"] in {"DEFAULT", "PROTECTED"}}):
        raise ValueError("Controle positivo do provedor libacl incompleto.")
    provider = providers[0]["path"]
    commands = []
    for path in sorted(key for key in wanted if wanted[key]["role"] == "command"):
        seen, pending = set(), [path]
        while pending:
            current = pending.pop()
            if current not in seen:
                seen.add(current)
                pending.extend(graph[current] - seen)
        commands.append({"path": path, "libAclInNeededClosure": provider in seen,
            "reachableAclImporters": sorted(key for key in seen if any(s["undefined"] for s in by_path[key]["aclSymbols"])),
            "observedLegacySymbols": sorted({symbol for key in seen for symbol in by_path[key]["legacyPathnameImports"]}),
            "dynamicLookupImporters": sorted(key for key in seen if by_path[key]["dynamicLookupImports"])})
    return commands


def analyze_acl(archive, manifest, folder, image, commit, inspector):
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted" or os.environ.get("RUNNER_OS") != "Linux":
        raise ValueError("Análise ACL somente no CI Linux hospedado.")
    archive, folder = Path(archive).resolve(), Path(folder).resolve()
    own = (ROOT / ".local/agent-sandbox").resolve()
    if not archive.is_relative_to(own) or not folder.is_relative_to(own) or folder.name != "acl-analysis" or not re.fullmatch(r"sha256:[a-f0-9]{64}", image) or not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Análise ACL fora da execução própria.")
    folder.mkdir(exist_ok=False)
    records, cache = [], {}
    with tarfile.open(archive, "r:") as stream:
        members = {_path(member.name): member for member in stream.getmembers()}
        for entry in sorted((x for x in manifest["files"] if x["role"] in ROLES), key=lambda x: x["path"]):
            member = members.get(entry["path"])
            if member is None or not member.isfile() or not 0 < member.size <= 128 * 1024 * 1024:
                raise ValueError("ELF ausente/inválido no export final.")
            data = stream.extractfile(member).read()
            digest = hashlib.sha256(data).hexdigest()
            if digest != entry["sha256"] or not data.startswith(b"\x7fELF"):
                raise ValueError("Hash/identidade do ELF final diverge.")
            if digest not in cache:
                saved = folder / (digest + ".elf")
                descriptor = os.open(saved, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "wb") as target:
                    target.write(data)
                arguments = ["readelf", *FLAGS, saved.relative_to(ROOT).as_posix()]
                result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True, timeout=20,
                                        env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"})
                if result.returncode or result.stderr.strip():
                    raise ValueError("Inspeção ACL falhou operacionalmente; não é ausência de consumidores.")
                cache[digest] = {**parse_acl_elf(result.stdout), "arguments": arguments,
                    "argumentsSha256": hashlib.sha256(json.dumps(arguments).encode()).hexdigest(),
                    "readelfOutputSha256": hashlib.sha256(result.stdout.encode()).hexdigest(), "exitCode": 0}
            records.append({**{key: entry[key] for key in ("path", "sha256", "role", "package")}, **cache[digest]})
    commands = map_acl_consumers(records, manifest)
    return {"scope": "ACL_DYNAMIC_INTERFACES_NOT_APPLICABILITY_ACCEPTANCE", "commit": commit, "imageId": image,
            "createdUtc": datetime.now(timezone.utc).isoformat(), "readelfVersion": inspector,
            "elfCount": len(records), "elfs": records, "commands": commands,
            "limitations": ["Imports/DT_NEEDED não provam execução do caminho nem ausência de código incorporado ou resolução dinâmica",
                            "Interfaces at/fd observadas não demonstram adoção em todos os caminhos; controles do worker não corrigem libacl",
                            "Nenhuma disposição de risco/VEX/aceite da candidata; todos os advisories preservados"]}
