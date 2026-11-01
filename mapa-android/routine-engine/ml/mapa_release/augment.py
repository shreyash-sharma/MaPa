"""Training-only augmentation, with fixed seeds and an objective no-op check.

This builder does not import detectors, model fitting, or detector results.
"""
import json
import shutil
import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq
from .common import ROOT,freeze_protocol,trial,progress,candidate_dir
from .build import SCHEMA
from .inject import person_episodes
from mapa_syn.generator.data import load_person
from mapa_syn.io import read_json,write_json,sha256

SEEDS=[20261111,20261112,20261113]


def build():
    protocol=freeze_protocol();source=candidate_dir();folder=candidate_dir('v2');folder.mkdir(exist_ok=True)
    marker=folder/'augmentation.json'
    if marker.exists():return read_json(marker)
    write_json(ROOT/'augmentation-plan.json',dict(seeds=SEEDS,split='train only',
               generator_changed=False,validation_test_changed=False,
               integrity_check='Inactivity/phone-left-home windows must contain at least one positive count to remove.',
               selection='Uniform deterministic source-window draws; no detector scores are read. Real sources use two extra seeds; derived parents use three.'))
    trial('augmentation','training-v2','STARTED','Fixed new seeds; train people only; no detector/result imports; validation/test copied byte-for-byte.')
    for split,kind in [('train','base'),('val','base'),('test','base'),('val','episodes'),('test','episodes')]:
        shutil.copy2(source/f'{split}-{kind}.parquet',folder/f'{split}-{kind}.parquet')
    writer=pq.ParquetWriter(folder/'train-episodes.partial.parquet',SCHEMA,compression='zstd')
    metadata=[json.loads(line) for line in (source/'episodes.jsonl').read_text(encoding='utf-8').splitlines()]
    added=[];buffer=[];rows=0
    def consume(person,c,o,seed):
        nonlocal rows
        for table,meta in person_episodes(person,c,o,'train',seed=seed,require_count_change=True):
            eid=meta['episode_id']+f'-a{seed}'
            table=table.set_column(table.schema.get_field_index('episode_id'),'episode_id',pa.array([eid]*len(table)))
            meta.update(episode_id=eid,seed=seed,augmentation='training-v2')
            if meta['scenario'] in [1,3] and meta['expected']=='unusual_inactivity':
                d=meta['day_index'];s=meta['start_slot'];n=meta['length_bins']
                assert (c[d,s:s+n]>0).any()
            metadata.append(meta);added.append(meta);buffer.append(table);rows+=len(table)
            if len(buffer)>=100:
                writer.write_table(pa.concat_tables(buffer));buffer.clear()
    allowed={p['pid'] for p in protocol['people'] if p['split']=='train'}
    opened=[]
    try:
        for batch in pq.ParquetFile(source/'train-episodes.parquet').iter_batches():writer.write_batch(batch)
        for row in protocol['people']:
            if row['split']!='train':continue
            assert row['pid'] not in protocol['reserved_real_check']
            p=load_person(row);opened.append(p['pid'])
            for seed in SEEDS[:2]:consume(p,p['counts'].reshape(-1,96),p['observed'].reshape(-1,96),seed)
        with np.load(ROOT/'derived/arrays.npz',allow_pickle=False) as z:
            c,o,dow=z['counts'],z['observed'],z['start_dow']
        for i in protocol['derived_splits']['train']:
            p=dict(pid=f'derived-{i:04d}',source='derived',start_dow=int(dow[i]))
            for seed in SEEDS:consume(p,c[i],o[i],seed)
        if buffer:writer.write_table(pa.concat_tables(buffer))
    finally:writer.close()
    assert set(opened)<=allowed
    (folder/'train-episodes.partial.parquet').rename(folder/'train-episodes.parquet')
    with (folder/'episodes.jsonl').open('w',encoding='utf-8') as stream:
        for row in metadata:stream.write(json.dumps(row,allow_nan=False)+'\n')
    for split in ['val','test']:
        for kind in ['base','episodes']:
            assert sha256(folder/f'{split}-{kind}.parquet')==sha256(source/f'{split}-{kind}.parquet')
    outcome=dict(dataset_version='v2',seeds=SEEDS,extra_episode_days=len(added),extra_rows=rows,
                 real_people_opened=opened,reserved_real_check_opened=[],validation_test_unchanged=True,
                 sha256={p.name:sha256(p) for p in folder.glob('*.parquet')},
                 added_positive_behaviour=sum(m['expected']=='unusual_inactivity' for m in added))
    write_json(marker,outcome)
    summary=read_json(source/'episodes-manifest.json');summary['augmentation']=outcome
    for meta in added:summary['counts']['train'][str(meta['scenario'])]['derived' if meta['source']=='derived' else 'real']+=1
    summary['episodes']=len(metadata);summary['sha256']={p.name:sha256(p) for p in folder.glob('*-episodes.parquet')}
    write_json(folder/'episodes-manifest.json',summary)
    trial('augmentation','training-v2','COMPLETE',str(outcome))
    progress('Training-only v2 augmentation complete: '+str(len(added))+' additional episode-days, with validation/test Parquet inputs byte-identical. Original final/ remains unchanged.')
    return outcome


if __name__=='__main__':print(build())
