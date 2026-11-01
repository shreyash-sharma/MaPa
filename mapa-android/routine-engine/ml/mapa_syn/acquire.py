"""Public, licence-verified downloads. Never extracts or modifies raw inputs."""
import json
import urllib.request
from datetime import datetime, timezone

from .io import sha256, write_json
from .paths import data_root

CC = "https://creativecommons.org/licenses/by/4.0/"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "MaPa-Syn/0.1 research"})
    with urllib.request.urlopen(request, timeout=120) as response:
        if "login" in response.url.lower():
            raise RuntimeError("Download requires login; stop for user input")
        return response.read()


def acquire(source):
    if source not in {"s3", "ubiqlog"}:
        raise RuntimeError("External source permission is not confirmed; acquisition disabled")
    directory = data_root() / "raw" / source
    directory.mkdir(parents=True, exist_ok=True)
    metadata_url = ("https://api.figshare.com/v2/articles/14410229" if source == "s3" else
                    "https://archive.ics.uci.edu/dataset/369/ubiqlog+smartphone+lifelogging")
    metadata = fetch(metadata_url)
    if source == "s3":
        info = json.loads(metadata)
        if info["license"]["name"] != "CC BY 4.0" or info["license"]["url"].rstrip("/") != CC.rstrip("/"):
            raise RuntimeError("S3 licence is unclear; stop")
        files = [(item["name"], item["download_url"], item["size"]) for item in info["files"]]
    else:
        page = metadata.decode("utf-8")
        if "creativecommons.org/licenses/by/4.0" not in page:
            raise RuntimeError("UbiqLog licence is unclear; stop")
        files = [("ubiqlog.zip", "https://archive.ics.uci.edu/static/public/369/ubiqlog+smartphone+lifelogging.zip", None)]
    evidence = directory / ("source-metadata.json" if source == "s3" else "source-page.html")
    if not evidence.exists():
        evidence.write_bytes(metadata)
    licence = directory / "LICENCE.txt"
    if not licence.exists():
        licence.write_bytes(fetch(CC + "legalcode.txt"))
    records = []
    for name, url, size in files:
        if "/" in name or "\\" in name:
            raise ValueError("Unsafe source filename")
        target = directory / name
        if not target.exists():
            temporary = directory / (name + ".partial")
            request = urllib.request.Request(url, headers={"User-Agent": "MaPa-Syn/0.1 research"})
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as out:
                if "login" in response.url.lower():
                    raise RuntimeError("Login required; stop")
                while chunk := response.read(1024 * 1024):
                    out.write(chunk)
            if size is not None and temporary.stat().st_size != size:
                raise RuntimeError("Download size differs from official metadata")
            temporary.rename(target)
        if size is not None and target.stat().st_size != size:
            raise RuntimeError("Raw input size mismatch")
        records.append({"name": name, "url": url, "bytes": target.stat().st_size, "sha256": sha256(target)})
    record = {"source": source, "metadata_url": metadata_url, "licence": "CC BY 4.0", "licence_url": CC,
              "acquired_utc": datetime.now(timezone.utc).isoformat(), "files": records}
    manifest = directory / "SOURCE.json"
    if manifest.exists():
        previous = json.loads(manifest.read_text(encoding="utf-8"))
        if previous["files"] != records:
            raise RuntimeError("Raw acquisition manifest changed")
    else:
        write_json(manifest, record)
    source_md = directory / "SOURCE.md"
    if not source_md.exists():
        source_md.write_text("# " + source + "\n\n" + json.dumps(record, indent=2) + "\n", encoding="utf-8")
    write_json(directory / "SHA256.json", {f.name: sha256(f) for f in sorted(directory.iterdir())
               if f.is_file() and f.name != "SHA256.json" and not f.name.endswith(".partial")})
    return records
