"""Standalone loader for a frozen MaPa-Rhythm final/ folder."""
import hashlib
import json
from pathlib import Path
import pyarrow.parquet as pq


def file_hash(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()


def load_splits(folder=None,verify=True):
    folder=Path(folder) if folder else Path(__file__).resolve().parent
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    if verify:
        for name,digest in manifest['sha256'].items():
            if file_hash(folder/name)!=digest:raise ValueError('SHA-256 mismatch: '+name)
    splits={s:pq.read_table(folder/f'{s}.parquet') for s in ['train','val','test']}
    seen={}
    for split,table in splits.items():
        if len(table)!=manifest['splits'][split]['rows']:raise ValueError('Row count mismatch')
        pids=set(table['pid'].to_pylist())
        if len(pids)!=manifest['splits'][split]['people']:raise ValueError('Person count mismatch')
        for pid in pids:
            if pid in seen:raise ValueError('Person in two splits: '+pid)
            seen[pid]=split
    return splits


if __name__=='__main__':
    print({k:len(v) for k,v in load_splits().items()})
