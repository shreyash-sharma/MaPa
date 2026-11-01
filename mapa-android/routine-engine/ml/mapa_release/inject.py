"""Deterministic, person-disjoint disruptions; absence is never base quiet."""
import json
import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

from mapa_syn.generator.data import load_person
from mapa_syn.io import read_json, write_json, sha256
from .common import ROOT, SEED, freeze_protocol, trial, progress
from .build import SCHEMA, day_table, build_base

NAMES = {1:'daytime_inactivity',2:'delayed_morning',3:'phone_left_home',4:'normal_long_silence',5:'routine_shift',6:'life_tag_day',7:'network_outage',8:'app_killed',9:'battery_dead',10:'permission_revoked'}


def gap_limit(counts, obs, day, quantile):
    from mapa_syn.generator.hsmm import runs
    history = runs(counts[:day].reshape(-1),obs[:day].reshape(-1))
    quiet = history[(history[:,0]==0)&(history[:,2]==0),1] if len(history) else []
    return float(np.quantile(quiet,quantile)) if len(quiet)>=5 else None


def inject_day(person, counts, obs, day, split, scenario, severity, start, length, expected):
    table=day_table(person,day,counts[day],obs[day],split,'episodes')
    values=table.to_pydict(); n=96
    if person['source']!='derived':
        for k in np.flatnonzero(~obs[day]):values['provenance'][k]='injected'
    window=np.zeros(n,bool); window[start:start+length]=True
    eid=f"{person['pid']}-s{scenario:02d}-{severity}-d{day}"
    values.update(episode_id=[eid]*n,scenario=[scenario]*n,severity=[severity]*n,
                  injected=[NAMES[scenario]]*n,expected=[expected]*n,window=window.tolist())
    # An injected row carries provenance=injected, while source and
    # observation_basis retain its untouched parent origin.
    for k in np.flatnonzero(window): values['provenance'][k]='injected'
    if scenario in [1,3,6]:
        for k in np.flatnonzero(window):
            values['interactions'][k]=0; values['moved'][k]=0
    elif scenario==2:
        before=np.array(values['interactions'][start:start+length])
        for k in np.flatnonzero(window): values['interactions'][k]=0
        # Delayed use occurs immediately after the delay only when recorded.
        if start+length<96 and obs[day,start+length]:
            values['interactions'][start+length]+=int(np.maximum(before,0).sum())
            values['provenance'][start+length]='injected'
    elif scenario==5:
        # Entire seven-day shifted blocks are assembled by build_episodes.
        shifted=np.roll(counts[day],8)
        for k in np.flatnonzero(window): values['interactions'][k]=int(shifted[k])
    if scenario==6:
        values['life_tag']=[True]*n
    if scenario==7:
        for k in np.flatnonzero(window): values['network_ok'][k]=False
    if scenario in [8,9]:
        for k in np.flatnonzero(window):
            values['present'][k]=False; values['observed'][k]=False
            values['phone_on'][k]=False; values['network_ok'][k]=False
            values['interactions'][k]=-1
            if scenario==9: values['battery'][k]=0
        if scenario==9:
            for k in range(max(0,start-8),start):
                if obs[day,k]:
                    values['battery'][k]=int(round(100*(start-k)/8))
                    values['provenance'][k]='injected'
    if scenario==10:
        for k in np.flatnonzero(window):
            values['usage_ok'][k]=False; values['observed'][k]=False; values['interactions'][k]=-1
    result=pa.Table.from_pydict(values,schema=SCHEMA)
    meta=dict(episode_id=eid,pid=person['pid'],source=person['source'],split=split,scenario=scenario,
              provenance='injected',
              severity=severity,injected=NAMES[scenario],expected=expected,day_index=day,start_slot=start,
              length_bins=length,observation_basis=values['observation_basis'][0],seed=SEED,
              quiet_qualification='inferred, not verified non-use' if person['source']!='derived' else 'model-derived')
    return result,meta


def person_episodes(person, counts, obs, split,seed=None,require_count_change=False):
    if seed is None:rng=np.random.default_rng(SEED + sum(person['pid'].encode()))
    else:
        import hashlib
        identity=int.from_bytes(hashlib.sha256(person['pid'].encode()).digest()[:4],'little')
        rng=np.random.default_rng(np.random.SeedSequence([seed,identity]))
    days=len(counts)
    available=np.flatnonzero(obs.any(1)&(np.arange(days)>=14))
    for scenario in range(1,11):
        for severity_index,severity in enumerate(['mild','clear','severe']):
            length=([12,20,32][severity_index] if scenario in [1,3,4,6] else [4,8,12][severity_index])
            for day in rng.permutation(available):
                day=int(day)
                if scenario==5:
                    if day+7>days or not obs[day:day+7].all(): continue
                    for shift_day in range(day,day+7):
                        table,meta=inject_day(person,counts,obs,shift_day,split,scenario,severity,0,96,'routine_confirmation')
                        meta.update(routine_block_start=day,routine_block_days=7)
                        yield table,meta
                    break
                starts=range(28,88-length+1) if scenario<=6 else range(0,96-length+1)
                candidates=[s for s in starts if obs[day,s:s+length].all()]
                if require_count_change and scenario in [1,3]:
                    candidates=[s for s in candidates if (counts[day,s:s+length]>0).any()]
                if scenario==2:
                    active=np.flatnonzero((counts[day]>0)&obs[day]&(np.arange(96)>=28)&(np.arange(96)<60))
                    candidates=[int(active[0])] if len(active) and int(active[0])+length<88 and obs[day,int(active[0]):int(active[0])+length].all() else []
                if scenario==4:
                    candidates=[s for s in candidates if (counts[day,s:s+length]==0).all()]
                if not candidates: continue
                start=int(rng.choice(candidates))
                limit=gap_limit(counts,obs,day,[.90,.97,.995][severity_index])
                expected=('monitoring_unavailable' if scenario>=7 else 'none')
                if scenario in [1,2,3] and limit is not None and length>limit: expected='unusual_inactivity'
                table,meta=inject_day(person,counts,obs,day,split,scenario,severity,start,length,expected)
                meta.update(person_gap_limit_bins=limit,percentile=[.90,.97,.995][severity_index])
                yield table,meta
                break


def build_episodes():
    protocol=freeze_protocol(); build_base()
    folder=ROOT/'candidate'; marker=folder/'episodes-manifest.json'
    if marker.exists(): return read_json(marker)
    trial('episodes','release-v1','STARTED','All ten scenarios; frozen seed; only strict source windows; no detector feedback')
    writers={s:pq.ParquetWriter(folder/f'{s}-episodes.parquet',SCHEMA,compression='zstd') for s in ['train','val','test']}
    totals={s:{str(k):{'derived':0,'real':0} for k in range(1,11)} for s in writers}
    metadata=[]
    def consume(p,c,o,split):
        for table,meta in person_episodes(p,c,o,split):
            writers[split].write_table(table)
            metadata.append(meta)
            totals[split][str(meta['scenario'])]['derived' if p['source']=='derived' else 'real']+=1
    try:
        for row in protocol['people']:
            p=load_person(row)
            consume(p,p['counts'].reshape(-1,96),p['observed'].reshape(-1,96),row['split'])
        with np.load(ROOT/'derived/arrays.npz',allow_pickle=False) as z:
            dc, do, dd = z['counts'], z['observed'], z['start_dow']
            for split,people in protocol['derived_splits'].items():
                for i in people:
                    p=dict(pid=f'derived-{i:04d}',source='derived',start_dow=int(dd[i]))
                    consume(p,dc[i],do[i],split)
    finally:
        for writer in writers.values(): writer.close()
    with (folder/'episodes.jsonl').open('w',encoding='utf-8') as stream:
        for meta in metadata: stream.write(json.dumps(meta,allow_nan=False)+'\n')
    outcome=dict(counts=totals,episodes=len(metadata),sha256={p.name:sha256(p) for p in folder.glob('*-episodes.parquet')},
                 unsupported_real='Zero cells mean no eligible strict observed window after warm-up, or no qualifying quiet/7-day block. Not filled with assumed observation.')
    write_json(marker,outcome)
    trial('episodes','release-v1','COMPLETE',str(totals))
    progress('Injected deterministic scenarios into eligible derived and real windows; real counts and unsupported cells recorded separately in candidate/episodes-manifest.json. No fitting reads real_check.')
    return outcome


if __name__=='__main__': print(build_episodes())
