"""Detector selection uses validation only; test evaluation is locked and cached."""
import numpy as np
import pyarrow.parquet as pq
import torch
from functools import lru_cache
from scipy.special import expit

from mapa_rhythm.cnn import CausalCnn
from mapa_syn.io import write_json, read_json, sha256
from .common import ROOT, trial, progress,candidate_dir
from .learning import prepare_arrays


def monitoring_unavailable(present, phone_on, usage_ok, network_ok, observed, age_bins=0,interactions=0):
    from .runtime_policy import classify
    return classify(0.,1.,present=present,phone_on=phone_on,usage_ok=usage_ok,network_ok=network_ok,
                    observed=observed,heartbeat_age_bins=age_bins,interactions=interactions)=='MONITORING_UNAVAILABLE'


def predict(split,name):
    folder=ROOT/'models'/name
    path=folder/f'{split}-scores.npy'
    if path.exists(): return np.load(path)
    config=read_json(folder/'complete.json')
    version=config.get('data_version','legacy')
    a=dict(np.load(prepare_arrays(split,version,config.get('dataset_version','v1')),allow_pickle=False))
    ck=torch.load(folder/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
    from .forest import load_network
    net=load_network(ck);net.eval()
    device='cuda' if torch.cuda.is_available() else 'cpu';net.to(device)
    scores=[]
    with torch.no_grad():
        for i in range(0,len(a['x']),256):
            logits,_=net(torch.from_numpy(a['x'][i:i+256]).to(device))
            column=0 if config.get('detector_score_kind')=='cumulative_next_bin_hazard' else 2
            scores.append(logits[:,:,column].cpu().numpy())
    result=expit(np.concatenate(scores));np.save(path,result)
    if config.get('detector_score_kind')=='cumulative_next_bin_hazard':
        output=np.zeros_like(result)
        for day in range(len(result)):
            survival=1.
            for t in range(96):
                if 28<=t<88 and a['x'][day,t,2]>.5 and a['x'][day,t,0]<.5:
                    if t>0 and a['x'][day,t-1,2]>.5:survival*=1-float(result[day,t-1])
                    output[day,t]=1-survival
                else:survival=1.
        result=output;np.save(path,result)
    return result


@lru_cache(maxsize=2)
def evaluation_inputs(split,name):
    config=read_json(ROOT/'models'/name/'complete.json')
    version=config.get('data_version','legacy')
    dataset_version=config.get('dataset_version','v1')
    path=prepare_arrays(split,version,dataset_version)
    a=dict(np.load(path,allow_pickle=False))
    a['data_version']=version
    a['alert_policy']=read_json(ROOT/'models'/name/'complete.json').get('alert_policy','stateless_diagnostic')
    groups=read_json(path.with_suffix('.groups.json'))
    scores=predict(split,name)
    metadata={}
    for subset in ['base','episodes']:
        frame=pq.read_table(candidate_dir(dataset_version)/f'{split}-{subset}.parquet').to_pandas()
        for key,df in frame.groupby(['pid','episode_id','day_index'],sort=False):
            metadata[(str(key[0]),str(key[1]),int(key[2]))]=df
    return a,groups,scores,metadata


@lru_cache(maxsize=2)
def metric_rows(split,name):
    a,groups,scores,metadata=evaluation_inputs(split,name)
    rows=[];daymask=(np.arange(96)>=28)&(np.arange(96)<88)
    for g in groups:
        df=metadata[(g['pid'],g['episode_id'],g['day'])];slots=df.t.to_numpy()%96
        available=np.zeros(96,bool);tags=np.zeros(96,bool);window=np.zeros(96,bool);counts=np.full(96,-1)
        available[slots]=np.logical_and.reduce([df[c].to_numpy(bool) for c in ['present','phone_on','usage_ok','network_ok','observed']])
        tags[slots]=df.life_tag.to_numpy(bool);window[slots]=df.window.to_numpy(bool);counts[slots]=df.interactions
        streak=0;silence=np.zeros(96)
        for k in range(96):
            streak=streak+1 if available[k] and daymask[k] and counts[k]==0 else 0
            silence[k]=streak
        rows.append(dict(available=available,tags=tags,window=window,counts=counts,silence=silence,
                    expected=str(df.expected.iloc[0]),scenario=int(df.scenario.iloc[0]),
                    severity=str(df.severity.iloc[0]),source=str(df.source.iloc[0])))
    return rows


def evaluate(split,name,threshold):
    a,groups,scores,metadata=evaluation_inputs(split,name)
    rows=metric_rows(split,name)
    methods=['4h_rule','statistics_only','MaPa-Rhythm-Base']
    totals={m:dict(false_alerts=0,exposure_person_weeks=0.,positives={s:[0,0] for s in ['mild','clear','severe']},by_source={},by_scenario={str(i):[0,0] for i in range(1,11)},people={}) for m in methods}
    stale_as_fresh=0
    limits={}; day_limits={}
    for i,g in enumerate(groups):
        row=rows[i]
        available,tags,window,current_counts=[row[k] for k in ['available','tags','window','counts']]
        daymask=(np.arange(96)>=28)&(np.arange(96)<88)
        eligible=available&daymask&~tags
        eligible &= current_counts==0
        silence=np.expm1(a['x'][i,:,3]*np.log1p(192))
        # Baselines use only a continuously observed, healthy daytime streak.
        # Do not count missing night or source gaps as known non-use.
        if a.get('data_version','legacy')!='legacy':
            silence=row['silence']
        priorlimit=day_limits.get((g['pid'],g['day']),limits.get(g['pid'],16.))
        if not g['episode_id']:
            day_limits[(g['pid'],g['day'])]=priorlimit
        detectors={'4h_rule':silence>=16,'statistics_only':silence>=priorlimit,'MaPa-Rhythm-Base':scores[i]>=threshold}
        eid=g['episode_id'];expect=row['expected'];scenario=row['scenario'];severity=row['severity'];source=row['source']
        for method,alert in detectors.items():
            alert=alert&eligible
            if a.get('alert_policy')=='latched_until_sign_of_life':
                from .runtime_policy import latch_alerts
                alert=np.asarray(latch_alerts(alert,eligible),bool)
            stats=totals[method]
            person=stats['people'].setdefault(g['pid'],dict(false_alerts=0,exposure_person_weeks=0.))
            source_stats=stats['by_source'].setdefault(source,dict(false_alerts=0,exposure_person_weeks=0.,caught=0,positive=0))
            if not eid:
                events=int(np.sum(alert&~np.r_[False,alert[:-1]]))
                exposure=float(np.sum(available&daymask)/(60*7))
                for obj in [stats,person,source_stats]:
                    obj['false_alerts']+=events;obj['exposure_person_weeks']+=exposure
                stats.setdefault('untouched_alert_state_bins',0)
                stats['untouched_alert_state_bins']+=int(alert.sum())
            elif expect in ['unusual_inactivity','monitoring_unavailable','routine_confirmation']:
                caught=(bool((alert&window).any()) if expect=='unusual_inactivity' else
                        bool((~available&window).any()) if expect=='monitoring_unavailable' else False)
                stats['positives'][severity][0]+=int(caught);stats['positives'][severity][1]+=1
                family='behaviour' if expect=='unusual_inactivity' else 'observation' if expect=='monitoring_unavailable' else 'routine_confirmation'
                grouped=stats.setdefault('positives_by_family',{}).setdefault(family,{s:[0,0] for s in ['mild','clear','severe']})
                grouped[severity][0]+=int(caught);grouped[severity][1]+=1
                stats['by_scenario'][str(scenario)][0]+=int(caught);stats['by_scenario'][str(scenario)][1]+=1
                source_stats['caught']+=int(caught);source_stats['positive']+=1
            elif eid and expect=='none':
                # Report hard-negative episode false alerts separately from
                # untouched-day exposure; they never enter the denominator.
                stats.setdefault('hard_negative_episodes',0);stats.setdefault('hard_negative_false_alerts',0)
                stats['hard_negative_episodes']+=1;stats['hard_negative_false_alerts']+=int((alert&window).any())
                neg=stats.setdefault('hard_negative_by_scenario',{}).setdefault(str(scenario),[0,0])
                neg[0]+=int((alert&window).any());neg[1]+=1
        if not eid:
            # Baseline's personal gap scale is causal: only prior untouched
            # days update it, never the current episode or a later day.
            quiet=silence[available&daymask]
            if len(quiet):
                q=float(np.quantile(quiet,.97))
                limits[g['pid']]=max(4.,.9*priorlimit+.1*q)
    for stats in totals.values():
        stats['false_alerts_per_person_week']=stats['false_alerts']/max(stats['exposure_person_weeks'],1e-9)
        stats['untouched_alert_state_hours_per_person_week']=stats.get('untouched_alert_state_bins',0)*.25/max(stats['exposure_person_weeks'],1e-9)
        for group in stats['by_source'].values():
            group['false_alerts_per_person_week']=group['false_alerts']/max(group['exposure_person_weeks'],1e-9)
        stats['catch_rate_by_severity']={s:(v[0]/v[1] if v[1] else None) for s,v in stats['positives'].items()}
        # Person bootstrap keeps repeated days from pretending to be people.
        rng=np.random.default_rng(20261101);p=list(stats['people'].values());rates=[]
        for _ in range(1000):
            sampled=[p[j] for j in rng.integers(len(p),size=len(p))]
            den=sum(v['exposure_person_weeks'] for v in sampled)
            if den:rates.append(sum(v['false_alerts'] for v in sampled)/den)
        stats['false_alerts_95pct_person_bootstrap']=np.quantile(rates,[.025,.975]).tolist()
    model=totals['MaPa-Rhythm-Base']
    c4=True
    pooled=True
    for baseline in ['4h_rule','statistics_only']:
        b=totals[baseline]
        c4 &= model['false_alerts_per_person_week']<b['false_alerts_per_person_week']
        pooled &= model['false_alerts_per_person_week']<b['false_alerts_per_person_week']
        pooled &= sum(v[0] for v in model['positives'].values())>=sum(v[0] for v in b['positives'].values())
        for severity in ['mild','clear','severe']:
            x,y=model['catch_rate_by_severity'][severity],b['catch_rate_by_severity'][severity]
            if x is not None and y is not None:c4 &= x>=y
    return dict(split=split,threshold=threshold,methods=totals,C4=bool(c4),C4_by_severity=bool(c4),C4_pooled_diagnostic=bool(pooled),C5=stale_as_fresh==0,stale_as_fresh=stale_as_fresh,
                false_alert_definition='Contiguous alert starts on untouched strict-observed daytime bins per 420 observed daytime bins; unknown time is not exposure.',
                catch_definition='At least one correct alert inside a positive labelled episode window; severity reported separately; observation guard shared by all detectors.',
                routine_shift='Seven-day routine-confirmation targets are included and reported with zero catches: current detector heads do not issue confirmation prompts. Adding equal zero-catch targets to every method changes no pairwise C4 catch inequality.')


def select(name='cnn-v1'):
    folder=ROOT/'models'/name
    target=folder/'selection.json'
    if target.exists():return read_json(target)
    # Scores and thresholds are selected only on validation. No test arrays,
    # scores or result files are opened by this function.
    choices=[]
    grid=[.01,.03,.05,.07,.1,.2,.3,.4,.5,.6,.7,.8,.9,.95,.98]
    if read_json(folder/'complete.json').get('detector_score_kind')=='cumulative_next_bin_hazard':grid += [.99,.995,.999,.9999,.99999]
    for threshold in grid:
        outcome=evaluate('val',name,threshold)
        choices.append(outcome)
        print('Validation threshold '+str(threshold)+' C4='+str(outcome['C4']),flush=True)
    passing=[r for r in choices if r['C4']]
    chosen=min(passing,key=lambda r:r['methods']['MaPa-Rhythm-Base']['false_alerts_per_person_week']) if passing else min(choices,key=lambda r:sum(abs((r['methods']['MaPa-Rhythm-Base']['catch_rate_by_severity'][s] or 0)-(r['methods']['4h_rule']['catch_rate_by_severity'][s] or 0)) for s in ['mild','clear','severe']))
    write_json(folder/'validation-trials.json',choices)
    for r in choices:trial('detector-validation',name+'-'+str(r['threshold']),'PASS' if r['C4'] else 'FAIL',str({m:(d['false_alerts_per_person_week'],d['catch_rate_by_severity']) for m,d in r['methods'].items()}))
    write_json(target,dict(name=name,threshold=chosen['threshold'],validation_C4=chosen['C4'],test_read=False,checkpoint_sha256=sha256(folder/'mapa-rhythm-base.pt')))
    progress('Validation detector threshold selection completed for '+name+'; C4='+str(chosen['C4'])+'. All thresholds, including failures, recorded. Test definitions unchanged.')
    return read_json(target)


def final_test(name='cnn-v1'):
    folder=ROOT/'models'/name;target=folder/'test-results.json'
    if target.exists():return read_json(target)
    locked=read_json(folder/'selection.json')
    assert locked['checkpoint_sha256']==sha256(folder/'mapa-rhythm-base.pt')
    if not locked['validation_C4']:raise RuntimeError('Improve on validation before opening test')
    result=evaluate('test',name,locked['threshold'])
    write_json(target,result)
    trial('detector-test',name,'PASS' if result['C4'] and result['C5'] else 'FAIL',str(result))
    progress('Locked test evaluation completed: C4='+str(result['C4'])+', C5='+str(result['C5'])+'. No test-driven data adjustment is permitted.')
    return result


if __name__=='__main__': print(select())
