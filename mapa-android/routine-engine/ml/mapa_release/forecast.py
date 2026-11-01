"""Validation-only forecasting diagnostics, separate from injected catch rates."""
import numpy as np
import torch
from torch.nn import functional as F
from mapa_rhythm.cnn import CausalCnn
from mapa_syn.io import read_json,write_json
from .common import ROOT,trial
from .learning import prepare_arrays


def report(name):
    folder=ROOT/'models'/name;target=folder/'validation-forecast.json'
    if target.exists():return read_json(target)
    config=read_json(folder/'complete.json')
    a=dict(np.load(prepare_arrays('val',config.get('data_version','legacy')),allow_pickle=False))
    device='cuda' if torch.cuda.is_available() else 'cpu';results={}
    for stage in ['real_pretrain','mapa-rhythm-base']:
        path=folder/(stage+'.pt')
        if not path.exists():continue
        ck=torch.load(path,map_location='cpu',weights_only=False)
        from .forest import load_network
        net=load_network(ck).to(device);net.eval()
        results[stage]={}
        for source in ['real','derived']:
            selected=np.flatnonzero(~a['episode'] & (a['real'] if source=='real' else ~a['real']))
            losses={task:[0.,0] for task in ['next_bin','masked_bin']}
            rng=np.random.default_rng(20261101)
            with torch.no_grad():
                for start in range(0,len(selected),128):
                    idx=selected[start:start+128]
                    y=torch.from_numpy(a['y'][idx]).to(device)
                    x=torch.from_numpy(a['x'][idx].copy()).to(device)
                    logits,_=net(x)
                    m=torch.from_numpy(a['mask'][idx,:,0]).to(device)
                    losses['next_bin'][0]+=float(F.binary_cross_entropy_with_logits(logits[:,:,0],y[:,:,0],reduction='none')[m].sum())
                    losses['next_bin'][1]+=int(m.sum())
                    hidden=(rng.random(a['mask'][idx,:,1].shape)<.15)&a['mask'][idx,:,1]
                    m=torch.from_numpy(hidden).to(device)
                    x[:,:,:4]=torch.where(m[:,:,None],torch.zeros_like(x[:,:,:4]),x[:,:,:4])
                    if config.get('data_version') in ['causal-rate','causal-survival']:
                        x[:,:,9:11]=torch.where(m[:,:,None],torch.zeros_like(x[:,:,9:11]),x[:,:,9:11])
                    if config.get('data_version')=='causal-survival':x[:,:,17]=torch.where(m,torch.zeros_like(x[:,:,17]),x[:,:,17])
                    logits,_=net(x)
                    losses['masked_bin'][0]+=float(F.binary_cross_entropy_with_logits(logits[:,:,1],y[:,:,1],reduction='none')[m].sum())
                    losses['masked_bin'][1]+=int(m.sum())
            results[stage][source]={task:dict(n=v[1],logloss=v[0]/v[1] if v[1] else None) for task,v in losses.items()}
    write_json(target,results);trial('forecast-validation',name,'REPORTED',str(results))
    return results
