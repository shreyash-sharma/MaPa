import argparse
import json
import traceback
from datetime import datetime, timezone

from .paths import data_root


def main():
    parser = argparse.ArgumentParser(description="MaPa-Syn v0.1 staged pipeline")
    parser.add_argument("stage", choices=["acquire", "convert", "audit", "heartbeat", "prepare-synthetic", "heartbeat-status", "genprepare", "genfit", "gensample", "genretry", "split", "gaps", "inject", "export", "baselines", "trace", "evaluate", "critic", "validate", "release"])
    parser.add_argument("--source", choices=["s3", "ubiqlog", "pielot", "lsapp"])
    parser.add_argument("--split", choices=["train", "validate", "test"])
    parser.add_argument("--config")
    parser.add_argument("--round", type=int, default=1)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--parents", type=int, default=200)
    parser.add_argument("--days", type=int, default=120)
    parser.add_argument("--seed", type=int, default=20261031)
    args = parser.parse_args()
    root = data_root()
    logs = root / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    log = logs / f"{stamp}-{args.stage}.log"
    try:
        if args.stage == "acquire":
            from .acquire import acquire
            result = acquire(args.source)
        else:
            from .pipeline import run
            result = run(args)
        output = json.dumps(result, indent=2, default=str)
        log.write_text(output + "\n", encoding="utf-8")
        print(output)
        return 1 if isinstance(result, dict) and result.get("gate") == "FAIL" else 0
    except Exception:
        output = traceback.format_exc()
        log.write_text(output, encoding="utf-8")
        print(output)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
