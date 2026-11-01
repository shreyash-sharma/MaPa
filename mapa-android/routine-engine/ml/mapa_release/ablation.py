"""Reproducible personal-prior alert ablations; no claim of neural alert gain."""
import numpy as np
import torch
from .common import ROOT,trial
from mapa_syn.io import read_json,write_json,sha256


def prior_head(source_name,name,floor_bins=0):
    source=ROOT/'models'/source_name;folder=ROOT/'models'/name;folder.mkdir(exist_ok=True)
    if (folder/'complete.json').exists():return folder
    ck=torch.load(source/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
    sd=ck['state_dict'];ch=ck['channels']
    sd['head.weight'][2].zero_()
    if floor_bins:
        floor=float(np.log1p(floor_bins)/np.log1p(192))
        sd['convs.0.weight'][0].zero_();sd['convs.0.weight'][0,16,2]=1.;sd['convs.0.bias'][0]=-floor
        for layer in range(1,6):sd[f'convs.{layer}.weight'][0].zero_();sd[f'convs.{layer}.bias'][0]=0.
        sd['head.weight'][2,0]=-32.;sd['head.weight'][2,ch+3]=32.;sd['head.bias'][2]=-32.*floor
    else:
        sd['head.weight'][2,ch+3]=32.;sd['head.weight'][2,ch+16]=-32.;sd['head.bias'][2]=0.
    prior=f'max({floor_bins} bins, historical personal percentile)' if floor_bins else 'historical personal percentile'
    description=f'Explicit {prior} alert prior; neural forecasting retained. No evidence that the neural component improves alerts.'
    ck.update(personal_quiet_prior=True,alert_head=description)
    torch.save(ck,folder/'mapa-rhythm-base.pt')
    meta=read_json(source/'complete.json')
    meta.update(name=name,personal_quiet_prior=True,alert_head=description,
                parent_checkpoint_sha256=sha256(source/'mapa-rhythm-base.pt'),
                checkpoint_sha256=sha256(folder/'mapa-rhythm-base.pt'),floor_bins=floor_bins)
    write_json(folder/'complete.json',meta)
    trial('model',name,'COMPLETE',description+'; source='+source_name)
    return folder
