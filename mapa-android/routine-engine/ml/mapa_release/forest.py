"""A numerical boosted-tree alert head around the real-pretrained CNN.

The portable head uses causal inputs and, optionally, learned causal CNN
representations. Episode metadata is never an inference input.
"""
import numpy as np
import torch
from torch import nn


class ForestHead(nn.Module):
    def __init__(self,base,leaves,depth,prior_weight=0.,input_features=18):
        super().__init__();self.base=base
        self.prior_weight=prior_weight
        self.input_features=input_features
        self.register_buffer('features',torch.zeros(leaves,depth,dtype=torch.long))
        self.register_buffer('thresholds',torch.zeros(leaves,depth))
        self.register_buffer('left',torch.ones(leaves,depth,dtype=torch.bool))
        self.register_buffer('valid',torch.zeros(leaves,depth,dtype=torch.bool))
        self.register_buffer('values',torch.zeros(leaves))
        self.register_buffer('baseline',torch.tensor(0.))

    def forward(self,z):
        original=self.base(z)
        raw=z[...,-self.input_features:].reshape(-1,self.input_features)
        selected=raw[:,self.features.reshape(-1)].reshape(-1,*self.features.shape)
        less=selected<=self.thresholds
        match=torch.where(self.left,less,~less)|~self.valid
        score=(match.float().prod(-1)*self.values).sum(-1)+self.baseline
        if self.prior_weight:
            score=(1-self.prior_weight)*score+self.prior_weight*32*(raw[:,-15]-raw[:,-2])
        score=score.reshape(*z.shape[:-1],1)
        return torch.cat([original[...,:2],score,original[...,3:]],dim=-1)


def load_network(checkpoint):
    from mapa_rhythm.cnn import CausalCnn
    net=CausalCnn(checkpoint['channels'],dilations=checkpoint.get('dilations',(1,2,4,8,16,32)))
    spec=checkpoint.get('forest_spec')
    if spec:net.head=ForestHead(net.head,spec['leaves'],spec['depth'],spec.get('prior_weight',0.),spec.get('input_features',18))
    net.load_state_dict(checkpoint['state_dict'])
    return net


def convert(model,base):
    paths=[]
    for predictors in model._predictors:
        nodes=predictors[0].nodes
        def walk(index,path):
            node=nodes[index]
            if node['is_leaf']:
                paths.append((path,float(node['value'])));return
            split=(int(node['feature_idx']),float(node['num_threshold']))
            walk(int(node['left']),path+[(split,True)])
            walk(int(node['right']),path+[(split,False)])
        walk(0,[])
    depth=max(len(p) for p,_ in paths)
    head=ForestHead(base,len(paths),depth,input_features=model.n_features_in_)
    head.baseline.fill_(float(model._baseline_prediction[0,0]))
    for i,(path,value) in enumerate(paths):
        head.values[i]=value
        for j,((feature,threshold),left) in enumerate(path):
            # Preserve <= routing for every float32 input at midpoint ties.
            t=np.float32(threshold)
            if float(t)>threshold:t=np.nextafter(t,np.float32(-np.inf))
            head.features[i,j]=feature;head.thresholds[i,j]=float(t)
            head.left[i,j]=left;head.valid[i,j]=True
    return head,dict(leaves=len(paths),depth=depth,input_features=model.n_features_in_)


def fit(source_name='cnn-v12-rate-endpoint',name='cnn-v13-forest',*,monotonic_quiet=False,max_depth=3,max_iter=200,spell_mining_rounds=0,neural_features=False,positive_sampling='endpoint',severity_weights=None):
    import joblib
    from sklearn.ensemble import HistGradientBoostingClassifier
    from threadpoolctl import threadpool_limits
    from .common import ROOT,SEED,trial,progress
    from .learning import prepare_arrays
    from mapa_syn.io import read_json,write_json,sha256
    folder=ROOT/'models'/name;folder.mkdir(exist_ok=True)
    if (folder/'complete.json').exists():return read_json(folder/'complete.json')
    source=ROOT/'models'/source_name
    config=read_json(source/'complete.json')
    a=dict(np.load(prepare_arrays('train',config['data_version'],config.get('dataset_version','v1')),allow_pickle=False))
    eligible=(a['mask'][:,:,2]|a['y'][:,:,2].astype(bool))&(a['x'][:,:,0]<.5)&(a['x'][:,:,2]>.5)
    positive=(a['y'][:,:,2].astype(bool)&eligible).any(1)
    ends=np.where(a['y'][:,:,2].astype(bool)&eligible,np.arange(96)[None,:],-1).max(1)
    pos=np.flatnonzero(positive)*96+ends[positive]
    if positive_sampling=='window':
        pos=np.flatnonzero((a['y'][:,:,2].astype(bool)&eligible&
                           (a['x'][:,:,3]>=np.log1p(4)/np.log1p(192))).reshape(-1))
    elif positive_sampling!='endpoint':raise ValueError(positive_sampling)
    neg=np.flatnonzero((eligible&~positive[:,None]).reshape(-1))
    rng=np.random.default_rng(SEED);neg=rng.choice(neg,min(200000,len(neg)),replace=False)
    indices=np.r_[pos,neg]
    x=a['x'].reshape(-1,18)[indices];y=np.r_[np.ones(len(pos)),np.zeros(len(neg))]
    sample_weight=None
    if severity_weights:
        assert not spell_mining_rounds
        import pyarrow.parquet as pq
        from .common import candidate_dir
        frame=pq.read_table(candidate_dir(config.get('dataset_version','v1'))/'train-episodes.parquet',columns=['episode_id','severity']).to_pandas()
        severity=frame.groupby('episode_id',sort=False).severity.first().to_dict()
        groups=read_json(prepare_arrays('train',config['data_version'],config.get('dataset_version','v1')).with_suffix('.groups.json'))
        positive_weight=np.array([severity_weights[severity[groups[int(i)//96]['episode_id']]] for i in pos],float)
        positive_weight/=positive_weight.mean()
        sample_weight=np.r_[positive_weight,np.ones(len(neg))]
    if neural_features:
        assert not spell_mining_rounds,'Embedding mining needs its own declared protocol'
        ck=torch.load(source/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
        device='cuda' if torch.cuda.is_available() else 'cpu'
        encoder=load_network(ck).to(device).eval()
        captured=[]
        hook=encoder.head.register_forward_pre_hook(lambda module,args:captured.append(args[0].detach()))
        embedded=np.empty((len(indices),config['channels']+18),np.float32)
        with torch.no_grad():
            for start in range(0,len(a['x']),128):
                captured.clear();encoder(torch.from_numpy(a['x'][start:start+128]).to(device))
                chosen=np.flatnonzero((indices>=start*96)&(indices<(start+128)*96))
                if len(chosen):embedded[chosen]=captured[0].reshape(-1,config['channels']+18)[torch.from_numpy(indices[chosen]-start*96).to(device)].cpu().numpy()
        hook.remove();del encoder,captured
        x=embedded
    trial('model',name,'STARTED',f'Boosted alert head: {len(pos)} positive {positive_sampling} inputs and {len(neg)} training-only healthy quiet inputs. No validation/test fitting.')
    constraints=[0]*x.shape[1]
    if monotonic_quiet:constraints[x.shape[1]-15]=1
    teacher=HistGradientBoostingClassifier(max_iter=max_iter,max_leaf_nodes=2**max_depth,max_depth=max_depth,
             min_samples_leaf=20,l2_regularization=1.,learning_rate=.05,class_weight='balanced',
             early_stopping=False,random_state=SEED,monotonic_cst=constraints)
    with threadpool_limits(limits=4):teacher.fit(x,y,sample_weight=sample_weight)
    mining=[]
    if spell_mining_rounds:
        # One notification is counted per quiet spell, rather than per bin.
        # Mine only training negatives, balancing spells instead of letting
        # long quiet intervals dominate the negative objective.
        negative=eligible&~positive[:,None]
        starts=negative&~np.c_[np.zeros(len(negative),bool),negative[:,:-1]]
        group_ids=(np.cumsum(starts,axis=1)+np.arange(len(negative))[:,None]*97).reshape(-1)[neg]
        for iteration in range(spell_mining_rounds):
            with threadpool_limits(limits=4):risk=teacher.predict_proba(a['x'].reshape(-1,18)[neg])[:,1]
            order=np.argsort(-risk,kind='stable')
            _,first=np.unique(group_ids[order],return_index=True)
            hard=neg[order[first]]
            indices=np.r_[pos,hard]
            x=a['x'].reshape(-1,18)[indices];y=np.r_[np.ones(len(pos)),np.zeros(len(hard))]
            with threadpool_limits(limits=4):teacher.fit(x,y)
            mining.append(dict(round=iteration+1,training_negative_spells=len(hard)))
    joblib.dump(teacher,folder/'teacher.joblib')
    ck=torch.load(source/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
    net=load_network(ck);net.head,spec=convert(teacher,net.head)
    ck.update(state_dict=net.state_dict(),forest_spec=spec,
              alert_head=('Boosted trees fitted to training causal CNN representations and raw inputs.' if neural_features else 'Boosted trees fitted to training causal inputs; CNN retains real masked/next-bin pretraining and derived/episode training.'),
              output_contract={'0':'next_bin_active_logit','1':'masked_current_bin_active_logit','2':'boosted_tree_unusual_inactivity_logit','3-15':'reserved'})
    torch.save(ck,folder/'mapa-rhythm-base.pt')
    # Verify the portable tensor head against the teacher independently of C4.
    check=x[rng.choice(len(x),min(len(x),10000),replace=False)]
    with torch.no_grad():
        z=torch.from_numpy(check) if neural_features else torch.cat([torch.zeros(len(check),config['channels']),torch.from_numpy(check)],1)
        portable=torch.sigmoid(net.head(z)[:,2]).numpy()
    with threadpool_limits(limits=4):expected=teacher.predict_proba(check)[:,1]
    error=float(np.max(np.abs(portable-expected)))
    assert error<1e-5,error
    result=dict(config,name=name,alert_head=ck['alert_head'],forest_spec=spec,portable_model_ready=True,
                parent_checkpoint_sha256=sha256(source/'mapa-rhythm-base.pt'),checkpoint_sha256=sha256(folder/'mapa-rhythm-base.pt'),
                teacher_sha256=sha256(folder/'teacher.joblib'),teacher_tensor_max_probability_error=error,
                supervised_positive_inputs=len(pos),positive_sampling=positive_sampling,supervised_negative_inputs=int((y==0).sum()),negative_candidate_pool=len(neg),
                forest_hyperparameters=teacher.get_params(),severity_weights=severity_weights,monotonic_quiet=monotonic_quiet,neural_features=neural_features,spell_mining=mining,test_read=False)
    write_json(folder/'complete.json',result)
    trial('model',name,'COMPLETE',f'Portable numerical forest head fitted on train only; parity error {error}')
    progress('Fitted a boosted-tree alert head around the pretrained CNN. This is an explicit hybrid, not a claim that neural alert scores alone improved. Validation is next.')
    return result


if __name__=='__main__':print(fit())
