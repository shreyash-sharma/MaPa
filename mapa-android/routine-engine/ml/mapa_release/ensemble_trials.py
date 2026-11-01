"""Predeclared validation-only calibration of tree and quiet-tail evidence."""
import numpy as np
import torch
from scipy.special import expit
from .common import ROOT,trial,progress
from .forest import load_network
from .learning import prepare_arrays
from .evaluate import select,final_test
from .calibrate import matched_catch
from mapa_syn.io import read_json,write_json,sha256

WEIGHTS=[.1,.25,.5,.75,.9]


def run():
    source=ROOT/'models/cnn-v13-forest';config=read_json(source/'complete.json')
    write_json(ROOT/'ensemble-plan.json',dict(weights=WEIGHTS,source='cnn-v13-forest',
               model_change='Weighted tree logit and explicit 32*(continuous-quiet minus historical-p90) prior, shared latch policy',
               data_change=False,test_definitions_changed=False,selection='Validate each fixed weight; test only validation C4 passes; stop on first passing test.'))
    a=dict(np.load(prepare_arrays('val',config['data_version']),allow_pickle=False))
    rawpath=source/'val-tree-logits.npy'
    if rawpath.exists():raw=np.load(rawpath)
    else:
        device='cuda' if torch.cuda.is_available() else 'cpu'
        ck=torch.load(source/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
        net=load_network(ck).to(device).eval();x=a['x'].reshape(-1,18);parts=[]
        with torch.no_grad():
            for start in range(0,len(x),4096):
                z=torch.cat([torch.zeros(len(x[start:start+4096]),config['channels'],device=device),torch.from_numpy(x[start:start+4096]).to(device)],1)
                parts.append(net.head(z)[:,2].cpu().numpy())
        raw=np.concatenate(parts).reshape(a['x'].shape[:2]);np.save(rawpath,raw)
    prior=32*(a['x'][:,:,3]-a['x'][:,:,16])
    for i,weight in enumerate(WEIGHTS):
        name=f'cnn-v18-ensemble-{i}';folder=ROOT/'models'/name;folder.mkdir(exist_ok=True)
        if not (folder/'complete.json').exists():
            ck=torch.load(source/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
            ck['forest_spec']=dict(ck['forest_spec'],prior_weight=weight)
            torch.save(ck,folder/'mapa-rhythm-base.pt')
            description=f'Hybrid tree and personal quiet-tail logits, prior weight {weight}; neural forecasting retained. No claim of neural alert benefit.'
            meta=dict(config,name=name,source_model='cnn-v13-forest',forest_spec=ck['forest_spec'],
                      alert_policy='latched_until_sign_of_life',alert_head=description,personal_quiet_prior=True,
                      checkpoint_sha256=sha256(folder/'mapa-rhythm-base.pt'))
            write_json(folder/'complete.json',meta)
            np.save(folder/'val-scores.npy',expit((1-weight)*raw+weight*prior))
            trial('ensemble',name,'COMPLETE',description+'; validation-only weight calibration; no data changes.')
        selection=select(name)
        if not selection['validation_C4']:
            matched_catch(name);selection=read_json(folder/'selection.json')
        print(name+' validation_C4='+str(selection['validation_C4']),flush=True)
        if selection['validation_C4']:
            result=final_test(name)
            print(name+' test_C4='+str(result['C4']),flush=True)
            if result['C4'] and result['C5']:
                write_json(ROOT/'ready-model.json',dict(name=name,selection=str(folder/'selection.json'),results=str(folder/'test-results.json')))
                progress('The fixed ensemble candidate '+name+' passed validation and locked model-test C4/C5. Export and completion audit remain.')
                return name
    return None


if __name__=='__main__':print(run())
