from .io import read_json, sha256, write_json
from .paths import version_root


def update(cfg, paths=(), **fields):
    root = version_root()
    path = root / "injection_manifest.json"
    manifest = read_json(path) if path.exists() else {"outputs": {}}
    for key in ["_config_sha256", "_spec_sha256", "_spec_revision"]:
        name = key.lstrip("_")
        if name in manifest and manifest[name] != cfg[key]:
            raise RuntimeError(f"Frozen policy changed: {name}")
        manifest[name] = cfg[key]
    manifest.update(fields)
    for p in paths:
        manifest["outputs"][p.relative_to(root).as_posix()] = sha256(p)
    write_json(path, manifest)
    return manifest


def check_inputs():
    from .paths import data_root
    manifest = read_json(version_root() / "injection_manifest.json")
    for name, digest in manifest["inputs"].items():
        if sha256(data_root() / name) != digest:
            raise RuntimeError(f"Frozen source changed: {name}")
    return manifest
