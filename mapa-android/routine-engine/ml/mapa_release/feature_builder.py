"""Standalone causal-v3 input builder. History contains untouched PRIOR days."""
import numpy as np
from mapa_rhythm.features import Profile,featurize


def completed_quiet(counts,observed):
    counts=np.asarray(counts);observed=np.asarray(observed,bool)
    slots=np.arange(96)
    mask=observed&(counts>=0)&(slots>=28)&(slots<88)
    edges=np.diff(np.r_[False,mask,False].astype(np.int8));out=[]
    for begin,end in zip(np.flatnonzero(edges==1),np.flatnonzero(edges==-1)):
        active=counts[begin:end]>0
        bounds=np.r_[0,np.flatnonzero(active[1:]!=active[:-1])+1,end-begin]
        for a,b in zip(bounds[:-1],bounds[1:]):
            if not active[a] and a>0 and b<end-begin:out.append(int(b-a))
    return out


def build_day(counts,observed,dow,history,quantiles=(.90,.995)):
    """history: ordered (counts[96], observed[96], weekday) prior untouched days.

    For an episode, use its parent history before the episode's day, never
    the injected variant or later days. No training or fitting happens here.
    """
    sums=np.zeros((3,96));ns=np.zeros((3,96));quiet=[]
    for c,o,d in history:
        c=np.asarray(c);o=np.asarray(o,bool)
        dt=1 if d==5 else 2 if d==6 else 0
        sums[dt,o]+=c[o]>0;ns[dt,o]+=1
        quiet.extend(completed_quiet(c,o))
    allrate=(sums.sum(0)+1)/(ns.sum(0)+4)
    rate=np.clip((sums+4*allrate)/(ns+4),.002,.998)
    profile=Profile(rate,len(history))
    c=np.where(observed,counts,-1)
    x=featurize(c,np.r_[np.ones(28),np.zeros(60),np.ones(8)],np.full(96,80),int(dow),lambda _:profile)
    streak=0
    for slot in range(96):
        streak=streak+1 if observed[slot] and c[slot]==0 and 28<=slot<88 else 0
        x[slot,3]=np.log1p(min(streak,192))/np.log1p(192)
    q=np.quantile(quiet,quantiles) if len(quiet)>=5 else np.array([16.,32.])
    x[:,16:18]=np.log1p(q)/np.log1p(192)
    return x


def add_rate_features(features):
    """Replace constant daytime charge/battery channels with causal rate context."""
    single=features.ndim==2
    x=features[None].copy() if single else features.copy()
    slots=np.arange(96)[None,:]
    known=x[:,:,2]>.5;day=(slots>=28)&(slots<88)
    count=np.rint(np.expm1(x[:,:,1]*np.log(21)))
    cumulative=np.cumsum(count*known*day,axis=1)
    exposure=np.cumsum(known*day,axis=1)
    quiet=np.rint(np.expm1(x[:,:,3]*np.log1p(192))).astype(int)
    before=np.clip(slots-quiet,0,95)
    prior_count=np.take_along_axis(cumulative,before,axis=1)
    prior_exposure=np.take_along_axis(exposure,before,axis=1)
    x[:,:,9]=np.minimum(np.log1p(cumulative)/np.log1p(200),1)
    rate=prior_count/np.maximum(prior_exposure,1)
    x[:,:,10]=np.minimum(np.log1p(rate)/np.log(21),1)
    return x[0] if single else x


def add_survival_features(features):
    """Causal expected activity missed within the current observed quiet spell.

    The hourly profile is fixed before the day. Its one-step-ahead rate
    from the previous tick estimates use in the current tick; no realised
    future use enters this accumulation.
    """
    single=features.ndim==2
    x=features[None].copy() if single else features.copy()
    expected=np.zeros(len(x),dtype=np.float32)
    for slot in range(96):
        quiet=(x[:,slot,2]>.5)&(x[:,slot,0]<.5)&(28<=slot<88)
        expected=np.where(quiet,expected+x[:,max(slot-1,0),11],0)
        x[:,slot,17]=np.log1p(expected)/np.log1p(60)
    return x[0] if single else x
