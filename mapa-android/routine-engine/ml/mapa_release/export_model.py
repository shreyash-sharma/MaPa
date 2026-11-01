"""Use the repository's existing CNN ExecuTorch exporter, then runtime parity."""
import argparse
import json
from pathlib import Path
import numpy as np
import torch
from mapa_rhythm.cnn import CausalCnn, CnnStep
from mapa_rhythm.export_executorch import export


def run(folder,learning):
    torch.set_num_threads(1)
    ck=torch.load(folder/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
    from .forest import load_network
    net=load_network(ck);net.eval()
    destination=folder/'mapa-rhythm-base.pte'
    export(net,destination,xnnpack=True)
    from executorch.runtime import Runtime
    program=Runtime.get().load_program(str(destination));method=program.load_method('forward')
    step=CnnStep(net).eval()
    with np.load(learning/'val.npz',allow_pickle=False) as z:
        x=z['x'];real=z['real'];episode=z['episode']
    rng=np.random.default_rng(20261101);indices=[]
    for mask in [real&~episode,~real&~episode,episode]:
        candidates=np.flatnonzero(mask)
        indices.extend(rng.choice(candidates,min(4,len(candidates)),replace=False).tolist())
    indices.extend([int(np.argmax(x[:,:,3].max(1))),int(np.argmax(x[:,:,1].max(1)))])
    indices=list(dict.fromkeys(indices));examples=x[indices]
    maxz=maxh=maxfull=0.
    with torch.no_grad():
        for day in examples:
            hp=torch.zeros(1,step.state_size);he=hp.clone()
            full,_=net(torch.tensor(day[None],dtype=torch.float32))
            for t,features in enumerate(day):
                xt=torch.tensor(features[None],dtype=torch.float32)
                zp,hp=step(xt,hp);ze,he=method.execute([xt,he])
                maxz=max(maxz,float((zp-ze).abs().max()));maxh=max(maxh,float((hp-he).abs().max()))
                maxfull=max(maxfull,float((zp-full[:,t]).abs().max()))
    result=dict(PASS=maxz<1e-4 and maxh<1e-4 and maxfull<1e-4,max_abs_logit_error=maxz,max_abs_state_error=maxh,
                max_abs_stream_vs_full_day_error=maxfull,validation_day_indices=indices,
                daily_state_reset=True,ticks=len(examples)*96,state_floats=step.state_size,pte_bytes=destination.stat().st_size,
                torch_version=torch.__version__,backend='xnnpack',exporter='mapa_rhythm.export_executorch.export',
                input_contract='x[1,18], state[1,state_floats]',output_contract=ck['output_contract'])
    (folder/'export-report.json').write_text(json.dumps(result,indent=2)+'\n')
    assert result['PASS'],result
    print(json.dumps(result))


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('learning',type=Path)
    a=ap.parse_args();run(a.folder,a.learning)
