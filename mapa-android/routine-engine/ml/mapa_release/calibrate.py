"""Find the validation operating-point bound that retains baseline catch.

This calibrates a detector, not generated data. Test inputs are never opened.
"""
import numpy as np
from .common import ROOT,trial,progress
from mapa_syn.io import read_json,write_json
from .evaluate import evaluation_inputs,evaluate


def matched_catch(name):
    folder=ROOT/'models'/name;target=folder/'matched-catch-validation.json'
    if target.exists():return read_json(target)
    initial=read_json(folder/'validation-trials.json')[0]
    a,groups,scores,metadata=evaluation_inputs('val',name)
    by_severity={s:[] for s in ['mild','clear','severe']}
    observation={s:0 for s in by_severity}
    for i,g in enumerate(groups):
        if not g['episode_id']:continue
        df=metadata[(g['pid'],g['episode_id'],g['day'])]
        severity=str(df.severity.iloc[0]);expected=str(df.expected.iloc[0])
        if expected not in ['unusual_inactivity','monitoring_unavailable']:continue
        slots=df.t.to_numpy()%96
        available=np.logical_and.reduce([df[c].to_numpy(bool) for c in ['observed','present','phone_on','usage_ok','network_ok']])
        win=df.window.to_numpy(bool)
        if expected=='monitoring_unavailable':
            observation[severity]+=int((~available&win).any());continue
        eligible=available&win&(df.interactions.to_numpy()==0)&~df.life_tag.to_numpy(bool)&(slots>=28)&(slots<88)
        by_severity[severity].append(float(scores[i,slots[eligible]].max()) if eligible.any() else 0.)
    bounds={}
    for severity,values in by_severity.items():
        need=max(initial['methods'][b]['positives'][severity][0] for b in ['4h_rule','statistics_only'])-observation[severity]
        if need>len(values):raise RuntimeError('Model cannot meet the fixed catch requirement')
        bounds[severity]=sorted(values,reverse=True)[need-1] if need>0 else 1.
    # A small numerical margin also protects deployment float differences.
    threshold=max(0.,min(bounds.values())-1e-6)
    result=evaluate('val',name,threshold)
    result['calibration']=dict(method='Largest validation threshold preserving required caught counts for every severity, minus 1e-6 numeric margin',bounds=bounds,test_read=False)
    write_json(target,result)
    trial('matched-catch-validation',name,'PASS' if result['C4'] else 'FAIL',str({m:(r['false_alerts_per_person_week'],r['catch_rate_by_severity']) for m,r in result['methods'].items()}))
    if result['C4']:
        old=read_json(folder/'selection.json');write_json(folder/'selection-before-matched-catch.json',old)
        old.update(threshold=threshold,validation_C4=True,calibration='matched-catch-validation.json')
        write_json(folder/'selection.json',old)
    progress('Exact matched-catch validation operating point for '+name+': C4='+str(result['C4'])+'. The catch requirement was preserved; only the validation threshold was calibrated.')
    return result


if __name__=='__main__':
    import sys
    print(matched_catch(sys.argv[1]))
