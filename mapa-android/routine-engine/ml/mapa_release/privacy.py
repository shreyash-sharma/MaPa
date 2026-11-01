"""Evaluation-only exact-copy check against all known real-day vectors."""
import numpy as np
from mapa_syn.generator.data import directory,load_person
from mapa_syn.generator.fidelity import privacy_distances
from mapa_syn.io import read_json,write_json,sha256
from .common import ROOT,trial,progress


def audit():
    params=ROOT/'derived/params.json';arrays=ROOT/'derived/arrays.npz'
    before=(sha256(params),sha256(arrays))
    roles=read_json(directory()/'roles-v013.json')
    days=[];reserved=0
    for row in roles['people']:
        person=load_person(row)
        c,o=person['counts'].reshape(-1,96),person['observed'].reshape(-1,96)
        days.append(np.where(o,c,-1)[o.any(1)])
        reserved+=row['partition']=='real_check'
    real=np.concatenate(days)
    with np.load(arrays,allow_pickle=False) as z:derived=np.where(z['observed'],z['counts'],-1).reshape(-1,96)
    with np.load(directory()/'hand_reference.npz',allow_pickle=False) as z:hand=np.where(z['observed'],z['counts'],-1).reshape(-1,96)
    results={}
    for name,data in [('derived',derived),('synth_hand',hand)]:
        distances=privacy_distances(data,real)
        results[name]=dict(exact_copies=int((distances==0).sum()),minimum_distance=float(distances.min()),days=len(data))
    assert before==(sha256(params),sha256(arrays))
    result=dict(results=results,real_days=len(real),real_people=len(roles['people']),
                real_check_people_read_for_evaluation=reserved,real_check_read_for_fitting=0,
                generator_unchanged=True,derived_unchanged=True,fitting_performed=False,
                interpretation='All-available-real evaluation; exact encoded-day copy check only, not an anonymity guarantee.')
    write_json(ROOT/'all-real-privacy-check.json',result)
    trial('privacy-evaluation','all-real-days','PASS' if results['derived']['exact_copies']==0 else 'FAIL',str(result))
    progress('Evaluated exact-copy privacy against all known real days, including reserved people for evaluation only. No fitting or parameter changes occurred. '+str(result))
    return result


if __name__=='__main__':print(audit())
