from importlib import import_module


def run(args):
    if args.stage == "genretry":
        from .generator.retry import run_retry
        return run_retry()
    if args.stage == "genprepare":
        from .generator.data import prepare
        return prepare()
    if args.stage == "genfit":
        from .generator.fit import genfit
        return genfit()
    if args.stage == "gensample":
        from .generator.sample import gensample
        return gensample(args.parents, args.days, args.seed)
    if args.stage in {"split", "gaps", "inject", "export", "trace", "baselines", "evaluate", "release"}:
        from .paths import version_root
        from .io import read_json
        review = version_root() / "review_gate.json"
        if review.exists():
            gate = read_json(review)
            if str(gate.get("state", "")).startswith(("v0.1.2", "v0.1.3")) and not gate.get("injection_allowed", False):
                raise RuntimeError("Stopped after S2b: waiting for user review under the current spec")
    if args.stage == "gaps":
        from .settings import load
        from .gaps import gaps
        return gaps(load(getattr(args, "config", None)))
    if args.stage == "split":
        from .settings import load
        from .splits import split
        return split(load(getattr(args, "config", None)))
    if args.stage == "inject":
        from .paths import version_root
        from .io import read_json
        gate = version_root() / "review_gate.json"
        if not gate.exists() or not read_json(gate).get("injection_allowed", False):
            raise RuntimeError("Injection blocked: waiting for user review of the heartbeat probe and synthetic base")
    if args.stage == "heartbeat":
        from .heartbeat import probe
        return probe(args.source)
    if args.stage == "prepare-synthetic":
        from .synthetic import prepare
        return prepare(args.parents, args.days, args.seed)
    if args.stage == "heartbeat-status":
        from .heartbeat_report import report
        return report()
    if args.stage == "audit":
        from .audit import audit
        return audit()
    if args.stage == "convert":
        if args.source not in {"s3", "ubiqlog"}:
            raise RuntimeError("External source permission is unconfirmed")
        from .io import read_json, sha256
        from .paths import data_root
        raw = data_root() / "raw" / args.source
        for name, digest in read_json(raw / "SHA256.json").items():
            if sha256(raw / name) != digest:
                raise RuntimeError(f"Raw acquisition integrity mismatch: {name}")
        return import_module(f"mapa_syn.sources.{args.source}").convert()
    raise RuntimeError(f"Stage {args.stage} has not been implemented; no successful gate is claimed")
