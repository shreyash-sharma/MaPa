"""Run the site's translation API locally, the same way it runs on Modal.

    python server/dev_server.py                  # stand-in model: accents vowels, for testing the pipeline
    BASETEN_API_KEY=... python server/dev_server.py --real
    python server/dev_server.py --bundle --fake  # write stand-in bundles to public/i18n/ (tests only)

Serves out/ too when it exists, so http://localhost:8787 behaves like the deployed site.
"""

from __future__ import annotations

import argparse
import json
import sys
import threading
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))

import i18n_core  # noqa: E402
from stores import LocalStore  # noqa: E402
from web import load_catalog, make_app, run_job  # noqa: E402


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--real", action="store_true", help="call Muse Spark Open on Baseten (needs BASETEN_API_KEY)")
    parser.add_argument("--bundle", action="store_true", help="write the bundled languages to public/i18n/ and exit")
    parser.add_argument("--fake", action="store_true", help="with --bundle: use the stand-in model")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args()

    catalog = load_catalog(ROOT / "public" / "i18n" / "en.json")
    call = i18n_core.fake_call if (args.fake or not args.real) else i18n_core.baseten_call()
    store = LocalStore(ROOT / ".i18n-cache")

    if args.bundle:
        for code in i18n_core.LANGUAGES["bundled"]:
            data = run_job(store, catalog, code, call)
            data["source"] = "bundled"
            (ROOT / "public" / "i18n" / f"{code}.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
            print(f"{code}: {data['stats']['translated']}/{data['stats']['total']}")
        return

    def start_job(lang: str) -> None:
        threading.Thread(target=run_job, args=(store, catalog, lang, call), daemon=True).start()

    import uvicorn

    app = make_app(
        catalog=catalog,
        store=store,
        start_job=start_job,
        site_dir=ROOT / "out",
        cors=["http://localhost:3000", "http://127.0.0.1:3000"],
    )
    print(f"model: {'Muse Spark Open on Baseten' if call is not i18n_core.fake_call else 'stand-in (accents vowels)'}")
    uvicorn.run(app, host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
