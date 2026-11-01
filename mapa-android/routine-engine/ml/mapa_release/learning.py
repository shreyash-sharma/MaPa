"""Causal CNN release trainer; model selection sees validation, never test."""
import json
import numpy as np
import pyarrow.parquet as pq
import torch
from torch import nn

from mapa_rhythm.cnn import CausalCnn
from mapa_rhythm.features import featurize, Profile, survival_targets
from mapa_syn.io import write_json, read_json, sha256
from .common import ROOT, SEED, freeze_protocol, trial, progress,candidate_dir


def batches_from_parquet(path):
    """Canonical files are written per day or parent, preserving pid/t order."""
    pf=pq.ParquetFile(path)
    for batch in pf.iter_batches(batch_size=11520):
        yield batch.to_pandas()


def features_for_day(counts, observed, day, dow, profile, last_active=None, moved=None,
                     charging=None, battery=None):
    cc=np.where(observed,counts,-1).astype(int)
    charge=np.zeros(96,dtype=np.int8) if charging is None else charging
    batt=np.full(96,80,dtype=np.int8) if battery is None else battery
    x=featurize(cc,charge,batt,dow,
                lambda _:profile,last_active_init=last_active,moved=moved)
    return x


def daily_summary(cc, observed, moved, charging, battery, dow):
    """Nine causal daily statistics used by the 30-day slow lane."""
    slots=np.arange(96); daytime=(slots>=24)&(slots<96)
    use=(cc>0)&observed
    active=use|(moved==1)
    where=np.flatnonzero(use)
    first=float(where[0]/96) if len(where) else 1.0
    last=float(where[-1]/96) if len(where) else 1.0
    longest=run=0
    for b in range(24,96):
        if observed[b] and not active[b]: run+=1; longest=max(longest,run)
        else: run=0
    bursts=int(np.sum(use & ~np.r_[False,use[:-1]]))
    obs_day=observed&daytime
    charge_share=float(np.mean(charging[obs_day])) if obs_day.any() else 0.0
    bvals=battery[obs_day]
    bvals=bvals[np.isfinite(bvals)]
    battery_min=float(np.min(bvals))/100.0 if len(bvals) else 0.0
    movement_known=obs_day&(moved>=0)
    movement_share=float(np.mean(moved[movement_known]==1)) if movement_known.any() else 0.0
    return np.asarray([first,last,longest/72.0,bursts/24.0,obs_day.sum()/72.0,
                       charge_share,battery_min,movement_share,float(dow)/6.0],dtype=np.float32)


def prepare_arrays(split,version='legacy',dataset_version='v1'):
    if dataset_version not in ['v1','final-v2'] and split in ['val','test']:
        for kind in ['base','episodes']:
            assert sha256(candidate_dir(dataset_version)/f'{split}-{kind}.parquet')==sha256(candidate_dir()/f'{split}-{kind}.parquet')
        return prepare_arrays(split,version,'v1')
    suffix='' if dataset_version=='v1' else '-'+dataset_version
    path=ROOT/(('learning' if version=='legacy' else 'learning-'+version)+suffix)/f'{split}.npz'; path.parent.mkdir(exist_ok=True)
    if path.exists(): return path
    if version=='causal-survival':
        from .feature_builder import add_survival_features
        import shutil
        base=prepare_arrays(split,'causal-rate',dataset_version)
        arrays=dict(np.load(base,allow_pickle=False))
        arrays['x']=add_survival_features(arrays['x'])
        np.savez_compressed(path,**arrays)
        shutil.copy2(base.with_suffix('.groups.json'),path.with_suffix('.groups.json'))
        trial('features',split+'-'+version,'COMPLETE','Expected missed activity from the causal prior-day hourly profile; targets, masks and raw records unchanged.')
        return path
    if version=='causal-rate':
        from .feature_builder import add_rate_features
        import shutil
        base=prepare_arrays(split,'causal-v3',dataset_version)
        arrays=dict(np.load(base,allow_pickle=False))
        arrays['x']=add_rate_features(arrays['x'])
        np.savez_compressed(path,**arrays)
        shutil.copy2(base.with_suffix('.groups.json'),path.with_suffix('.groups.json'))
        trial('features',split+'-'+version,'COMPLETE','Causal day-total and pre-gap rate features; all targets, masks, person groups and raw data unchanged.')
        return path
    parts=[]
    final_frame=None
    final_episode_mask=None
    if dataset_version == 'final-v2':
        final_frame=pq.read_table(ROOT/'final-v2'/f'{split}.parquet').to_pandas()
        final_episode_mask=final_frame['episode_id'].notna() & (final_frame['episode_id'].astype(str)!='')
    for subset in ['base','episodes']:
        if dataset_version == 'final-v2':
            frame=final_frame[final_episode_mask if subset=='episodes' else ~final_episode_mask].copy()
        else:
            frame=pq.read_table(candidate_dir(dataset_version)/f'{split}-{subset}.parquet').to_pandas()
        groupcols=['pid','episode_id','day_index']
        # Build causal profiles from untouched days only. Episode context
        # cannot update its own reference, and future days are never read.
        if subset=='base':
            history={}; quiet_history={}
            for pid,pf in frame.groupby('pid',sort=False):
                running_sum=np.zeros((3,96)); running_n=np.zeros((3,96))
                history[pid]={};quiet_history[pid]={}; completed=[]
                for day,df in pf.groupby('day_index',sort=True):
                    allrate=(running_sum.sum(0)+1)/(running_n.sum(0)+4)
                    rate=(running_sum+4*allrate)/(running_n+4)
                    history[pid][int(day)]=Profile(np.clip(rate,.002,.998),len(history[pid]))
                    quantiles=[.97,.995] if version=='causal-q97' else [.90,.995]
                    quiet_history[pid][int(day)]=np.quantile(completed,quantiles) if len(completed)>=5 else np.array([16.,32.])
                    dt=1 if int(df.dow.iloc[0])==5 else 2 if int(df.dow.iloc[0])==6 else 0
                    slots=df.t.to_numpy()%96; known=df.observed.to_numpy(bool)
                    running_sum[dt,slots[known]]+=(df.interactions.to_numpy()[known]>0)
                    running_n[dt,slots[known]]+=1
                    if version!='legacy':
                        from mapa_syn.generator.hsmm import runs
                        hc=np.full(96,-1);ho=np.zeros(96,bool)
                        hc[slots]=df.interactions;ho[slots]=df.observed
                        records=runs(hc,ho)
                        if len(records):completed.extend(records[(records[:,0]==0)&(records[:,2]==0),1].tolist())
            profile_history=history
        xs=[]; ys=[]; masks=[]; real=[]; tags=[]; summaries=[]; groups=[]
        for (pid,eid,day),df in frame.groupby(groupcols,sort=False,observed=True):
            day=int(day); slots=df.t.to_numpy()%96
            cc=np.full(96,-1); oo=np.zeros(96,bool)
            cc[slots]=df.interactions; oo[slots]=df.observed
            prof=profile_history[pid][day]
            moved=np.full(96,-1,dtype=np.int8)
            moved[slots]=np.where(df.moved.isna(),-1,df.moved.fillna(-1).to_numpy()).astype(np.int8)
            charging=np.zeros(96,dtype=np.float32); charging[slots]=df.charging.fillna(False).to_numpy(dtype=np.float32)
            battery=np.full(96,100,dtype=np.float32); battery[slots]=df.battery.fillna(80).to_numpy(dtype=np.float32)
            x=features_for_day(cc,oo,day,int(df.dow.iloc[0]),prof,moved=moved,
                               charging=charging,battery=battery)
            if version!='legacy':
                x[:,16:18]=np.log1p(quiet_history[pid][day])/np.log1p(192)
            # Availability is checked outside the neural net; unobserved
            # values are never fed to it as verified quiet.
            y=np.zeros((96,17),np.float32); m=np.zeros((96,17),bool)
            hy,hm=survival_targets(cc,moved=moved)
            y[:,:16]=hy; m[:,:16]=hm
            daymask=(np.arange(96)>=24)&oo
            m[:,16]=daymask
            if eid:
                win=np.zeros(96,bool); win[slots]=df.window
                expect=df.expected.iloc[0]
                if expect=='unusual_inactivity':
                    # Scenarios 1–3 are the behaviour family.  Scenario 2 is a
                    # delayed-morning injection and is a valid behaviour target
                    # when its injected quiet window crosses the declared limit.
                    allowed_behaviour_injections = {
                        1: 'daytime_inactivity',
                        2: 'delayed_morning',
                        3: 'phone_left_home',
                    }
                    injected = str(df.injected.iloc[0])
                    scenario = int(df.scenario.iloc[0])
                    if dataset_version=='final-v2' and allowed_behaviour_injections.get(scenario) != injected:
                        raise ValueError(f'Unusual training target lacks an injected behaviour label: {pid}/{eid}/{day}')
                    y[:,16]=win.astype(float)
                    # Burn-in until at least one hour of injected quiet;
                    # evaluate against the declared episode expectation.
                    idx=np.flatnonzero(win)
                    m[idx[:4],16]=False
                if expect in ['monitoring_unavailable','routine_confirmation'] or bool(df.life_tag.any()):
                    m[:,16]=False
            xs.append(x);ys.append(y);masks.append(m)
            summaries.append(daily_summary(cc,oo,moved,charging,battery,int(df.dow.iloc[0])))
            real.append(df.source.iloc[0]!='derived');tags.append(bool(df.life_tag.any()))
            groups.append(dict(pid=pid,episode_id=eid,day=day,dow=int(df.dow.iloc[0]),source=df.source.iloc[0]))
        parts.append(dict(x=np.array(xs,np.float32),y=np.array(ys),mask=np.array(masks),real=np.array(real),tag=np.array(tags),daily_summary=np.array(summaries),groups=groups))
    result={key:np.concatenate([p[key] for p in parts]) for key in ['x','y','mask','real','tag','daily_summary']}
    result['episode']=np.r_[np.zeros(len(parts[0]['x']),bool),np.ones(len(parts[1]['x']),bool)]
    np.savez_compressed(path,**result)
    write_json(path.with_suffix('.groups.json'),sum([p['groups'] for p in parts],[]))
    trial('features',split+'-'+version,'COMPLETE',str({k:list(v.shape) for k,v in result.items()}))
    return path


def train(channels=16, epochs=5, name='cnn-v1',positive_weight=4.,data_version='legacy',personal_prior=False,episode_mil=False,endpoint_loss=False,dataset_version='v1',masked_replay=False,hard_positive_fraction=1.,dilations=None,quiet_spell_loss=False,negative_base_only=False):
    freeze_protocol()
    torch.manual_seed(SEED); np.random.seed(SEED)
    torch.set_num_threads(4)
    folder=ROOT/'models'/name;folder.mkdir(parents=True,exist_ok=True)
    if (folder/'complete.json').exists(): return read_json(folder/'complete.json')
    from pathlib import Path
    import shutil
    shutil.copy2(Path(__file__),folder/'training_source.py')
    # Deliberately never prepare or open test arrays in this command.
    arrays={s:dict(np.load(prepare_arrays(s,data_version,dataset_version),allow_pickle=False)) for s in ['train','val']}
    device='cuda' if torch.cuda.is_available() else 'cpu'
    net=(CausalCnn(channels) if dilations is None else CausalCnn(channels,dilations=dilations)).to(device)
    optimizer=torch.optim.AdamW(net.parameters(),lr=.001,weight_decay=.0001)
    rng=np.random.default_rng(SEED)
    log=[]
    for phase in ['real_pretrain','derived_episodes']:
        anchor=None
        if phase=='derived_episodes' and personal_prior:
            # Explicit, disclosed expert initialization: compare continuous
            # observed quiet to the person's causal historical 90th percentile.
            # The CNN then learns residual adjustments; no test scores enter.
            with torch.no_grad():
                net.head.weight[2].zero_();net.head.bias[2]=0.
                net.head.weight[2,channels+3]=32.
                net.head.weight[2,channels+16]=-32.
            anchor=net.head.weight[2].detach().clone()
        a=arrays['train']
        selected=np.flatnonzero((a['real']&~a['episode']) if phase=='real_pretrain' else (~a['tag']))
        if not len(selected): raise RuntimeError('No training rows')
        trial('model',name+'-'+phase,'STARTED',f'{len(selected)} days; real_check excluded by frozen data roles')
        for epoch in range(epochs):
            net.train(); loss_sum=den=0
            for start in range(0,len(selected),128):
                if start==0: order=rng.permutation(selected)
                idx=order[start:start+128]
                x=torch.from_numpy(a['x'][idx].copy()).to(device)
                y=torch.from_numpy(a['y'][idx]).to(device)
                m=torch.from_numpy(a['mask'][idx].copy()).to(device)
                if phase=='real_pretrain':
                    m[:,:,2:]=False
                    hidden=(torch.rand_like(m[:,:,1],dtype=torch.float32)<.15)&m[:,:,1]
                    # Mask current-bin use, count and observed feature. The
                    # silence feature is also hidden to avoid target leakage.
                    x[:,:,:4]=torch.where(hidden[:,:,None],torch.zeros_like(x[:,:,:4]),x[:,:,:4])
                    if data_version in ['causal-rate','causal-survival']:
                        x[:,:,9:11]=torch.where(hidden[:,:,None],torch.zeros_like(x[:,:,9:11]),x[:,:,9:11])
                    if data_version=='causal-survival':x[:,:,17]=torch.where(hidden,torch.zeros_like(x[:,:,17]),x[:,:,17])
                    m[:,:,1]=hidden
                else:
                    m[:,:,1]=False
                    if endpoint_loss or episode_mil:
                        m[:,:,2] |= y[:,:,2].bool() & (x[:,:,2]>.5) & (x[:,:,0]<.5)
                logits,_=net(x)
                risk_loss=None
                if phase!='real_pretrain' and endpoint_loss:
                    eligible=m[:,:,2] & (x[:,:,0]<.5)
                    positive=(y[:,:,2].bool() & eligible).any(1)
                    pieces=[]
                    if positive.any():
                        ticks=torch.arange(96,device=device)[None,:].expand(len(idx),-1)
                        end=ticks.masked_fill(~(eligible & y[:,:,2].bool()),-1).amax(1)
                        z=logits[torch.arange(len(idx),device=device)[positive],end[positive],2]
                        positive_losses=nn.functional.binary_cross_entropy_with_logits(z,torch.ones_like(z),reduction='none')
                        hardest=max(1,int(np.ceil(len(positive_losses)*hard_positive_fraction)))
                        pieces.append(positive_losses.topk(hardest).values.mean())
                    negative=eligible & ~positive[:,None]
                    if negative_base_only:negative &= ~torch.from_numpy(a['episode'][idx]).to(device)[:,None]
                    if negative.any():
                        z=logits[:,:,2][negative]
                        if quiet_spell_loss:
                            starts=negative & ~torch.cat([torch.zeros(len(idx),1,device=device,dtype=torch.bool),negative[:,:-1]],1)
                            groups=torch.cumsum(starts.long(),1)+torch.arange(len(idx),device=device)[:,None]*97
                            pooled=torch.full((len(idx)*97,),-float('inf'),device=device)
                            pooled.scatter_reduce_(0,groups[negative],z,reduce='amax',include_self=True)
                            z=pooled[torch.isfinite(pooled)]
                        pieces.append(nn.functional.binary_cross_entropy_with_logits(z,torch.zeros_like(z)))
                    if pieces:risk_loss=sum(pieces)/len(pieces)
                    m[:,:,2]=False
                if phase!='real_pretrain' and episode_mil:
                    riskmask=m[:,:,2] & (x[:,:,0]<.5)
                    positive=(y[:,:,2].bool() & riskmask).any(1)
                    pooled_mask=torch.where(positive[:,None],riskmask & y[:,:,2].bool(),riskmask)
                    valid=pooled_mask.any(1)
                    pooled=logits[:,:,2].masked_fill(~pooled_mask,-30.).amax(1)
                    if valid.any():
                        rw=torch.where(positive[valid],torch.tensor(positive_weight,device=device),torch.tensor(1.,device=device))
                        risk_loss=(nn.functional.binary_cross_entropy_with_logits(pooled[valid],positive[valid].float(),reduction='none')*rw).sum()/rw.sum()
                    m[:,:,2]=False
                weights=torch.ones_like(y)
                if phase!='real_pretrain': weights[:,:,2]=torch.where(y[:,:,2]>0,torch.tensor(positive_weight,device=device),torch.tensor(1.,device=device))
                loss=(nn.functional.binary_cross_entropy_with_logits(logits,y,reduction='none')*m*weights).sum()/(m*weights).sum().clamp_min(1)
                if risk_loss is not None:loss=loss+risk_loss
                if phase!='real_pretrain' and masked_replay:
                    mask=torch.from_numpy(a['mask'][idx,:,1]).to(device)&(torch.rand_like(y[:,:,1])<.15)
                    if mask.any():
                        replay=x.clone()
                        replay[:,:,:4]=torch.where(mask[:,:,None],torch.zeros_like(replay[:,:,:4]),replay[:,:,:4])
                        if data_version in ['causal-rate','causal-survival']:replay[:,:,9:11]=torch.where(mask[:,:,None],torch.zeros_like(replay[:,:,9:11]),replay[:,:,9:11])
                        if data_version=='causal-survival':replay[:,:,17]=torch.where(mask,torch.zeros_like(replay[:,:,17]),replay[:,:,17])
                        reconstructed,_=net(replay)
                        loss=loss+.25*nn.functional.binary_cross_entropy_with_logits(reconstructed[:,:,1][mask],y[:,:,1][mask])
                if anchor is not None:loss=loss+.01*torch.square(net.head.weight[2]-anchor).sum()
                optimizer.zero_grad();loss.backward();torch.nn.utils.clip_grad_norm_(net.parameters(),1);optimizer.step()
                loss_sum+=float(loss.detach())*len(idx);den+=len(idx)
            row=dict(phase=phase,epoch=epoch,loss=loss_sum/max(den,1))
            log.append(row);print(json.dumps(row),flush=True)
        checkpoint=dict(state_dict={k:v.detach().cpu() for k,v in net.state_dict().items()},channels=channels,seed=SEED,
                        output_contract={'0':'next_bin_active_logit','1':'masked_current_bin_active_logit','2':'injected_unusual_inactivity_logit','3-15':'reserved; not trained'},
                        protocol_sha256=sha256(ROOT/'protocol.json'))
        checkpoint['data_version']=data_version
        checkpoint['personal_quiet_prior']=personal_prior
        checkpoint['episode_mil']=episode_mil
        checkpoint['endpoint_loss']=endpoint_loss
        checkpoint['dataset_version']=dataset_version
        checkpoint['masked_replay']=masked_replay
        checkpoint['hard_positive_fraction']=hard_positive_fraction
        checkpoint['dilations']=list(net.dilations)
        torch.save(checkpoint,folder/f'{phase}.pt')
        trial('model',name+'-'+phase,'COMPLETE',f'{epochs} epochs; checkpoint saved')
    torch.save(checkpoint,folder/'mapa-rhythm-base.pt')
    result=dict(name=name,channels=channels,dilations=list(net.dilations),epochs_per_phase=epochs,positive_weight=positive_weight,data_version=data_version,dataset_version=dataset_version,personal_quiet_prior=personal_prior,episode_mil=episode_mil,endpoint_loss=endpoint_loss,masked_replay=masked_replay,hard_positive_fraction=hard_positive_fraction,quiet_spell_loss=quiet_spell_loss,negative_base_only=negative_base_only,device=device,training=log,
                training_source_sha256=sha256(folder/'training_source.py'),
                checkpoint_sha256=sha256(folder/'mapa-rhythm-base.pt'),test_read=False,
                training_source='candidate train only',validation_source='candidate val only')
    write_json(folder/'complete.json',result)
    progress('Completed real masked-bin/next-bin pretraining and derived/episode fine-tuning trial '+name+'. Test remains unopened by training. Validation detector evaluation is next.')
    return result


if __name__=='__main__': print(train())
