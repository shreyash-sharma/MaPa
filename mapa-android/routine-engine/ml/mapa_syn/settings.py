"""Effective policy: docs/mapa-syn/DESIGN.md v0.1.1 overrides older briefs."""
from pathlib import Path
import yaml

from .io import sha256
from .paths import PROJECT, data_root

DEFAULT_CONFIG = Path(__file__).parent / "config/v0.1.yaml"
SPEC = PROJECT / "docs/mapa-syn/DESIGN.md"
CLAIM = "Behaviour results are on synthetic parents. A real check on up to 11 UbiqLog people uses inferred observation."
ANOMALIES = {f"ubiqlog-p{n:03d}" for n in [15, 22, 24, 26, 30]}
GROUPS = ["train", "validate", "test", "real_check", "s3_check"]


def load(path=None):
    path = Path(path) if path else DEFAULT_CONFIG
    cfg = yaml.safe_load(path.read_text(encoding="utf-8"))
    if "Spec change v0.1.1" not in SPEC.read_text(encoding="utf-8"):
        raise RuntimeError("Required v0.1.1 specification is missing")
    if cfg["split_shares"] != [.7, .15, .15] or cfg["warmup_days"] != 14:
        raise ValueError("Configuration contradicts fixed split/warmup policy")
    cfg.update(_config_sha256=sha256(path), _spec_sha256=sha256(SPEC), _spec_revision="0.1.1")
    return cfg


def source_dir(source):
    root = data_root() / "converted" / source
    return root if source == "synthetic" else root / "heartbeat_inferred"
