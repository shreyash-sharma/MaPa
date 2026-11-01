import json
from pathlib import Path
import pytest

from mapa_release.common import ROOT
from mapa_release.final_loader import load_splits,file_hash


def test_frozen_release_reloads_and_checks_every_payload_hash():
    folder=ROOT/'final'
    if not folder.exists():pytest.skip('C8 final freeze has not run; release remains incomplete')
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    anchorfile=ROOT/'C8-final.json'
    anchor=json.loads((anchorfile if anchorfile.exists() else ROOT/'C8.json').read_text(encoding='utf-8'))
    assert file_hash(folder/'manifest.json')==anchor['manifest_sha256']
    assert file_hash(ROOT/'protocol.json')==manifest['protocol_sha256']
    assert set(manifest['sha256'])=={p.name for p in folder.iterdir() if p.is_file() and p.name!='manifest.json'}
    assert all(file_hash(folder/name)==digest for name,digest in manifest['sha256'].items())
    tables=load_splits(folder)
    assert set(tables)=={'train','val','test'}
    for split,table in tables.items():
        assert len(table)==manifest['splits'][split]['rows']
        assert set(table['provenance'].to_pylist())<={'real_observed','model_derived','injected'}
    assert manifest['immutable']


def test_final_loader_rejects_payload_corruption(tmp_path):
    (tmp_path/'train.parquet').write_bytes(b'corrupt')
    (tmp_path/'manifest.json').write_text(json.dumps({'sha256':{'train.parquet':'0'*64}}))
    with pytest.raises(ValueError,match='SHA-256 mismatch'):
        load_splits(tmp_path)


def test_later_training_version_preserves_original_validation_and_test():
    anchor=json.loads((ROOT/'C8.json').read_text(encoding='utf-8'))
    folder=Path(anchor['path'])
    if folder.name=='final':pytest.skip('No later frozen dataset yet')
    assert file_hash(folder/'manifest.json')==anchor['manifest_sha256']
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    for split in ['val','test']:
        assert file_hash(folder/f'{split}.parquet')==file_hash(ROOT/'final'/f'{split}.parquet')
    assert set(manifest['sha256'])=={p.name for p in folder.iterdir() if p.is_file() and p.name!='manifest.json'}
    assert set(load_splits(folder))=={'train','val','test'}
