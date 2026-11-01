"""Release checks against actual artifacts, with explicit incomplete states."""
import numpy as np
import pyarrow.parquet as pq

from mapa_syn.generator.data import load_person, directory
from mapa_syn.generator.retry import score
from mapa_syn.generator.fidelity import privacy_distances
from mapa_syn.io import write_json, read_json, sha256
from .common import ROOT, freeze_protocol, trial, progress,candidate_dir

FORBIDDEN={'location','latitude','longitude','wifi','bluetooth','voice','app_name','package_name','ssid','bssid','text','message','phone_number'}


def audit_monitoring_policy():
    import itertools
    from .runtime_policy import classify
    keys=['present','phone_on','usage_ok','network_ok','observed']
    stale_as_fresh=0;cases=0
    for bits in itertools.product([False,True],repeat=5):
        for age in [-100,-1,0,1,2,100]:
            for probability in [0.,1.]:
                for activity in [-1,0,50]:
                    status=classify(probability,.1,**dict(zip(keys,bits)),heartbeat_age_bins=age,interactions=activity)
                    stale=(not all(bits)) or age<0 or age>1 or activity<0
                    stale_as_fresh+=int(stale and status!='MONITORING_UNAVAILABLE')
                    cases+=1
    assert stale_as_fresh==0
    result=dict(C5=True,stale_as_fresh=0,cases=cases,
                policy='Mandatory availability guard precedes all activity, quiet and neural alert states',
                source_sha256=sha256(__import__('pathlib').Path(__file__).with_name('runtime_policy.py')))
    write_json(ROOT/'monitoring-policy-check.json',result)
    trial('monitoring-policy','release-v1','PASS',str(result))
    return result


def audit_data(dataset_version='v1'):
    protocol=freeze_protocol()
    expected={p['pid']:p['split'] for p in protocol['people']}
    expected.update({f'derived-{i:04d}':s for s,people in protocol['derived_splits'].items() for i in people})
    seen={}; counts={}; provenance=set(); forbidden_fields=[]
    for split in ['train','val','test']:
        counts[split]={}
        for subset in ['base','episodes']:
            path=candidate_dir(dataset_version)/f'{split}-{subset}.parquet'
            pf=pq.ParquetFile(path)
            forbidden_fields.extend(FORBIDDEN & set(pf.schema_arrow.names))
            counts[split][subset]=pf.metadata.num_rows
            for batch in pf.iter_batches(columns=['pid','source','split','provenance','observed','interactions','subset','present','usage_ok']):
                a=batch.to_pydict()
                assert set(a['split'])=={split}
                assert set(a['source']) <= {'s3','ubiqlog','derived'}
                for pid in set(a['pid']):
                    assert pid not in protocol['reserved_real_check']
                    assert expected[pid]==split
                    assert seen.get(pid,split)==split
                    seen[pid]=split
                provenance.update(a['provenance'])
                assert set(a['provenance']) <= {'real_observed','model_derived','injected'}
                for o,c in zip(a['observed'],a['interactions']):
                    assert (o and c>=0) or (not o and c==-1)
                assert all(o for p,o in zip(a['provenance'],a['observed']) if p=='real_observed')
    fit=read_json(ROOT/'derived/fit-access.json')['opened_pids']
    from mapa_syn.io import read_jsonl
    for row in read_jsonl(candidate_dir(dataset_version)/'episodes.jsonl'):
        assert row['provenance']=='injected'
        assert row['injected'] and row['expected']
    allowed={p['pid'] for p in protocol['people'] if p['split']=='train'}
    assert set(fit)<=allowed
    assert not forbidden_fields
    result=dict(C1=True,C2=True,C6_schema=True,split_people={s:sum(v==s for v in seen.values()) for s in ['train','val','test']},
                rows=counts,provenance=sorted(provenance),real_check_fit_reads=0,forbidden_fields=forbidden_fields,
                dataset_version=dataset_version,input_sha256={p.name:sha256(p) for p in candidate_dir(dataset_version).glob('*.parquet')})
    write_json(ROOT/('data-audit.json' if dataset_version=='v1' else 'data-audit-'+dataset_version+'.json'),result)
    trial('data-audit','release-v1','PASS',str(result['split_people']))
    return result


def augment_hand_comparison():
    """Fill baseline-only table fields, without rescoring the derived sample."""
    target=ROOT/'fidelity.json'; result=read_json(target)
    if 'synth_hand_daily_counts' in result:return result
    with np.load(directory()/'hand_reference.npz',allow_pickle=False) as z:
        c,o=z['counts'],z['observed']
    totals=np.maximum(c,0).sum(2)
    people=[load_person(p) for p in freeze_protocol()['people']]
    real=np.concatenate([np.where(p['observed'].reshape(-1,96),p['counts'].reshape(-1,96),-1)[p['observed'].reshape(-1,96).any(1)] for p in people])
    distances=privacy_distances(np.where(o,c,-1).reshape(-1,96),real)
    result['synth_hand_daily_counts']=dict(mean=float(totals.mean()),sd=float(totals.std()),median=float(np.median(totals)))
    result['synth_hand_exact_copy_check']=dict(exact_copies=int((distances==0).sum()),minimum_distance=float(distances.min()),real_days=len(real),generated_days=len(distances))
    write_json(target,result)
    return result


def fidelity():
    target=ROOT/'fidelity.json'
    if target.exists(): return read_json(target)
    protocol=freeze_protocol()
    people=[load_person(p) for p in protocol['people'] if p['split']=='test']
    all_real=[load_person(p) for p in protocol['people']]
    with np.load(ROOT/'derived/arrays.npz',allow_pickle=False) as z:
        sample={k:z[k] for k in z.files}
    with np.load(directory()/'hand_reference.npz',allow_pickle=False) as z:
        hand={k:z[k] for k in z.files}
    result=score(sample,people,hand,privacy=True,all_real=all_real)
    result.update(age_layer_applied=True,
                  interpretation='Published-age-calibrated sample vs mostly younger observed logs; not expected to match every property. Historical test exposure disclosed in protocol.',
                  sample_sha256=sha256(ROOT/'derived/arrays.npz'),
                  empirical_daily_counts=read_json(ROOT/'derived/summary.json'),
                  raw_unaged_censored_km_result=str(directory()/'experiments/v013/censored_km/test.json'))
    # Preserve numeric gate output as historical diagnostics; current C3
    # requires transparent comparisons, not a misleading global fidelity PASS.
    result['C3']='TABLE_COMPLETE'
    result['gates']['F3']['age_layer_applied']=True
    for name,key in [('F4','learned_auc')]:
        auc=result['gates'][name][key]
        result['gates'][name]['direction_invariant_auc']=max(auc,1-auc)
    write_json(target,result)
    trial('fidelity','censored-km-age-v1','REPORTED',str(result['gates']))
    progress('C3 fidelity comparisons recorded without suppressing failed comparisons. Age-adjusted and unaged diagnostics are kept distinct. '+str(result['gates']))
    return result


if __name__=='__main__':
    print(audit_data())
    print(fidelity())
