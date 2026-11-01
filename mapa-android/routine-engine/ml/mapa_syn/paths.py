"""All generated datasets live outside the routine-engine code tree."""
import os
from pathlib import Path

ENGINE = Path(__file__).resolve().parents[2]
PROJECT = ENGINE.parents[1]
DEFAULT_DATA = PROJECT / "data" / "MaPaData"


def data_root():
    root = Path(os.environ.get("MAPA_DATA", DEFAULT_DATA)).resolve()
    if root == ENGINE or ENGINE in root.parents:
        raise ValueError("MAPA_DATA must be outside routine-engine")
    return root


def version_root():
    return data_root() / "mapa-syn" / "v0.1"
