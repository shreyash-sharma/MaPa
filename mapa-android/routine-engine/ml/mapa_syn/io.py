import csv
import gzip
import hashlib
import io
import json
from pathlib import Path


def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_csv(path, rows, fields):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as raw:
        stream = gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0) if path.suffix == ".gz" else raw
        with io.TextIOWrapper(stream, encoding="utf-8", newline="") as out:
            writer = csv.DictWriter(out, fieldnames=fields, lineterminator="\n", extrasaction="raise")
            writer.writeheader()
            writer.writerows(rows)


def read_csv(path):
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8", newline="") as f:
        yield from csv.DictReader(f)


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write(json.dumps(row, sort_keys=True, allow_nan=False) + "\n")


def read_jsonl(path):
    with Path(path).open(encoding="utf-8") as f:
        for line in f:
            if line.strip():
                yield json.loads(line)
