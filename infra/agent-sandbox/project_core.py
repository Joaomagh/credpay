"""Projeção explícita dos comandos e suporte ELF; nunca executa candidatos."""

import re
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
from collections import deque

from inventory_source import INJECTED, TOOLS, _path, _resolve, inventory, inventory_files


def license_references(content):
    return {name.rstrip(b".") for name in re.findall(rb"/usr/share/common-licenses/([A-Za-z0-9+.-]+)", content)}


def audit_core(archive, manifest):
    actual = inventory_files(archive)
    expected = {entry["path"]: entry for entry in manifest["files"]}
    files = {entry["path"]: entry for entry in actual["entries"] if entry["type"] != "directory" and entry["path"] not in INJECTED}
    if set(files) != set(expected) | {"bin"}:
        raise ValueError("Export final diverge da projeção: arquivos extras/ausentes.")
    for path, wanted in expected.items():
        observed = files[path]
        if observed["type"] != "file" or any(observed[key] != wanted[key] for key in ("sha256", "mode", "uid", "gid")) or observed["setuid"] or observed["setgid"] or observed["fileCapability"]:
            raise ValueError(f"Arquivo final diverge da projeção: {path}")
        executable = wanted["role"] in ("command", "loader")
        if wanted["mode"] != ("0555" if executable else "0444"):
            raise ValueError("Modo de componente diverge da baseline.")
    if files["bin"]["type"] != "symlink" or files["bin"]["target"] != "usr/bin":
        raise ValueError("Alias de shell diverge da baseline.")
    commands = {entry["path"] for entry in expected.values() if entry["role"] == "command"}
    if commands != {f"usr/bin/{tool}" for tool in TOOLS} or any(not value["present"] for value in actual["tools"].values()):
        raise ValueError("Comandos finais divergem da baseline.")
    if actual["status"] is not None:
        raise ValueError("Banco dpkg não representa projeção parcial.")
    directories = {entry["path"]: entry for entry in actual["entries"] if entry["type"] == "directory"}
    wanted_directories = {entry["path"]: entry for entry in manifest.get("directories", [])}
    if set(directories) != set(wanted_directories):
        raise ValueError("Diretório final diverge da projeção: extras/ausentes.")
    for path, wanted in wanted_directories.items():
        if any(directories[path][key] != wanted[key] for key in ("mode", "uid", "gid")):
            raise ValueError(f"Diretório final diverge da projeção: {path}")
    return {"scope": "CORE_BUILD_NOT_CONTAINMENT_ACCEPTANCE", "commands": sorted(commands), "entries": actual["entries"]}


def parse_elf(text):
    if not all(re.search(pattern, text, re.MULTILINE) for pattern in
               (r"^\s*Class:\s+ELF64\s*$", r"^\s*Data:\s+2's complement, little endian\s*$",
                r"^\s*Machine:\s+Advanced Micro Devices X86-64\s*$")):
        raise ValueError("ELF não corresponde a linux/amd64.")
    if re.search(r"\((?:RPATH|RUNPATH)\)", text):
        raise ValueError("ELF com caminho de busca adicional não aprovado.")
    needed = re.findall(r"\(NEEDED\)[^\n]*\[([^\]]+)\]", text)
    if any(not re.fullmatch(r"[A-Za-z0-9_+.-]+", name) or name.startswith(".") for name in needed):
        raise ValueError("Nome de biblioteca inválido.")
    interpreters = re.findall(r"\[Requesting program interpreter: ([^\]]+)\]", text)
    if len(interpreters) > 1 or (interpreters and interpreters[0] != "/lib64/ld-linux-x86-64.so.2"):
        raise ValueError("Loader não aprovado.")
    return {"interpreter": interpreters[0][1:] if interpreters else None, "needed": sorted(set(needed))}


def plan(commands, resolve, inspect):
    if not commands or not set(commands).issubset(TOOLS):
        raise ValueError("Comando não aprovado na projeção.")
    pending = deque((f"usr/bin/{name}", source, "command") for name, source in sorted(commands.items()))
    selected = {}
    while pending:
        target, requested, role = pending.popleft()
        source = resolve(requested)
        if not source:
            raise ValueError(f"Biblioteca ausente ou comando sem origem: {requested}")
        if target in selected:
            if selected[target]["source"] != source:
                raise ValueError("Origens conflitantes para a projeção.")
            continue
        if len(selected) >= 128:
            raise ValueError("Fechamento ELF excedeu limite.")
        info = inspect(source)
        selected[target] = {"path": target, "source": source, "role": role}
        for name in info["needed"]:
            library = f"usr/lib/x86_64-linux-gnu/{name}"
            pending.append((library, library, "library"))
        if info["interpreter"]:
            pending.append((info["interpreter"], info["interpreter"], "loader"))
    return sorted(selected.values(), key=lambda item: item["path"])


class Source:
    def __init__(self, archive):
        self.data = inventory(archive)
        self.entries = {entry["path"]: entry for entry in self.data["entries"]}
        self.stream = tarfile.open(archive, "r:")
        self.members = {_path(member.name): member for member in self.stream}

    def close(self):
        self.stream.close()

    def resolve(self, path):
        return _resolve(path, self.entries, executable=False)

    def read(self, path):
        entry = self.entries[path]
        if entry["type"] != "file" or entry.get("injectedByDaemon"):
            raise ValueError("Conteúdo da origem não permitido.")
        data = self.stream.extractfile(self.members[path]).read()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Arquivo da origem mudou.")
        return data


def project(archive, folder, additions):
    """Gera tar próprio; readelf lê arquivos regulares sem permissão de execução."""
    source = Source(archive)
    try:
        packages = {entry["name"]: entry for entry in source.data["packages"]}
        packages.update({entry["name"]: {"name": entry["name"], "version": entry["version"], "architecture": entry["architecture"]} for entry in additions})
        owners = {}
        for name, package in packages.items():
            candidates = (f"usr/share/credpay-candidate/{name}.list", f"var/lib/dpkg/info/{name}:amd64.list", f"var/lib/dpkg/info/{name}.list")
            listing = next((path for path in candidates if path in source.entries), None)
            if listing is None:
                raise ValueError(f"Lista de arquivos de pacote ausente: {name}")
            for line in source.read(listing).decode("utf-8", errors="strict").splitlines():
                if line:
                    owners.setdefault(_path(line.lstrip("/")), set()).add(name)
        folder = Path(folder)
        inspect_folder = folder / "elf-inputs"
        inspect_folder.mkdir(exist_ok=False)

        def inspect(path):
            data = source.read(path)
            if not data.startswith(b"\x7fELF"):
                raise ValueError(f"Componente não ELF: {path}")
            saved = inspect_folder / (hashlib.sha256(data).hexdigest() + ".elf")
            if not saved.exists():
                saved.write_bytes(data)
                saved.chmod(0o600)
            result = subprocess.run(["readelf", "--file-header", "--program-headers", "--dynamic", "--wide", str(saved)],
                                    capture_output=True, text=True, timeout=20, env={"PATH": "/usr/bin:/bin", "LC_ALL": "C"})
            if result.returncode:
                raise ValueError(f"Inspeção ELF falhou: {path}")
            return parse_elf(result.stdout)

        commands = {name: value["path"] for name, value in source.data["tools"].items() if value["present"]}
        commands["awk"] = "usr/bin/gawk"
        if set(commands) != set(TOOLS):
            raise ValueError("Ferramentas aprovadas não estão completas no candidato.")
        selected = plan(commands, source.resolve, inspect)
        files = {}
        components = set()
        manifest = []

        def add(path, content, mode, origin=None, role="configuration", package=None):
            if path in files:
                raise ValueError("Destino duplicado na projeção.")
            files[path] = (content, mode)
            manifest.append({"path": path, "sha256": hashlib.sha256(content).hexdigest(), "mode": format(mode, "04o"),
                             "uid": 0, "gid": 0, "role": role, "source": origin, "package": package})

        for entry in selected:
            path = entry["source"]
            matches = owners.get(path, set())
            if len(matches) != 1:
                raise ValueError(f"Proveniência de arquivo ambígua ou ausente: {path}")
            owner = next(iter(matches))
            components.add(owner)
            add(entry["path"], source.read(path), 0o555 if entry["role"] in ("command", "loader") else 0o444,
                path, entry["role"], owner)
        release = source.resolve("etc/os-release")
        if release is None or len(owners.get(release, set())) != 1:
            raise ValueError("Identidade Debian sem proveniência.")
        release_owner = next(iter(owners[release]))
        components.add(release_owner)
        add("etc/os-release", source.read(release), 0o444, release, "configuration", release_owner)
        for name in sorted(components):
            requested = f"usr/share/doc/{name}/copyright"
            copyright_path = source.resolve(requested)
            if copyright_path is None:
                raise ValueError(f"Copyright ausente: {name}")
            add(requested, source.read(copyright_path), 0o444, copyright_path, "copyright", name)
        licenses = set()
        for content, _ in list(files.values()):
            licenses.update(license_references(content))
        for name in sorted(licenses):
            requested = "usr/share/common-licenses/" + name.decode("ascii")
            license_path = source.resolve(requested)
            if license_path is None:
                raise ValueError(f"Licença referenciada ausente: {requested}")
            add(requested, source.read(license_path), 0o444, license_path, "license")
        add("etc/passwd", b"sandbox:x:10001:10001:Diagnostic sandbox:/tmp:/bin/sh\n", 0o444)
        add("etc/group", b"sandbox:x:10001:\n", 0o444)
        add("etc/nsswitch.conf", b"passwd: files\ngroup: files\nhosts: files dns\n", 0o444)
        # Ponto vazio para console padrão do engine; não é device nem comando.
        add("dev/console", b"", 0o444)
        provenance = {"scope": "PARTIAL_PACKAGE_COMPONENTS_NOT_DPKG_INSTALLATION", "packages": [packages[name] for name in sorted(components)], "files": sorted(manifest, key=lambda entry: entry["path"])}
        add("usr/share/credpay-sandbox/components.json", (json.dumps(provenance, indent=2) + "\n").encode(), 0o444, role="provenance")
        directories = {"tmp", "workspace", "dev", "dev/pts", "dev/shm", "proc", "sys"}
        for path in files:
            directories.update(str(parent).replace("\\", "/") for parent in Path(path).parents if str(parent) != ".")
        directory_manifest = []
        with tarfile.open(folder / "rootfs.tar", "w", format=tarfile.USTAR_FORMAT) as output:
            for path in sorted(directories, key=lambda value: (value.count("/"), value)):
                entry = tarfile.TarInfo(path)
                entry.type = tarfile.DIRTYPE
                entry.mode = 0o1777 if path == "tmp" else 0o755
                if path == "workspace":
                    entry.uid = entry.gid = 10001
                directory_manifest.append({"path": path, "mode": format(entry.mode, "04o"), "uid": entry.uid, "gid": entry.gid})
                output.addfile(entry)
            link = tarfile.TarInfo("bin")
            link.type, link.linkname, link.mode = tarfile.SYMTYPE, "usr/bin", 0o777
            output.addfile(link)
            for path, (content, mode) in sorted(files.items()):
                entry = tarfile.TarInfo(path)
                entry.mode, entry.size = mode, len(content)
                output.addfile(entry, io.BytesIO(content))
        result = {"scope": "CORE_BUILD_NOT_CONTAINMENT_ACCEPTANCE", "packages": provenance["packages"],
                  "files": sorted(manifest, key=lambda entry: entry["path"]), "commands": TOOLS, "directories": directory_manifest}
        (folder / "projection.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        return result
    finally:
        source.close()
