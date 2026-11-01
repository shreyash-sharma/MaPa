"""Daily reference inference with mandatory availability and activity precedence."""
from pathlib import Path
import json
import numpy as np
import torch
try:
    from .forest import load_network
    from .feature_builder import build_day,add_rate_features,add_survival_features
    from .runtime_policy import classify
except ImportError:
    from forest import load_network
    from feature_builder import build_day,add_rate_features,add_survival_features
    from runtime_policy import classify


class Predictor:
    def __init__(self,folder):
        folder=Path(folder)
        ck=torch.load(folder/'mapa-rhythm-base.pt',map_location='cpu',weights_only=False)
        self.net=load_network(ck).eval()
        self.threshold=json.loads((folder/'selection.json').read_text())['threshold']
        self.config=json.loads((folder/'complete.json').read_text())

    def day(self,counts,observed,dow,history,*,present,phone_on,usage_ok,network_ok,heartbeat_age_bins,life_tag):
        version=self.config.get('data_version','legacy')
        if version=='legacy':raise ValueError('Reference serving requires corrected causal features')
        q=(.97,.995) if version=='causal-q97' else (.90,.995)
        x=build_day(np.asarray(counts),np.asarray(observed,bool),dow,history,quantiles=q)
        if version in ['causal-rate','causal-survival']:x=add_rate_features(x)
        if version=='causal-survival':x=add_survival_features(x)
        with torch.no_grad():logits,_=self.net(torch.from_numpy(x[None]))
        probability=torch.sigmoid(logits[0,:,2]).numpy()
        if self.config.get('detector_score_kind')=='cumulative_next_bin_hazard':
            p=torch.sigmoid(logits[0,:,0]).numpy();probability=np.zeros(96);survival=1.
            for t in range(96):
                if 28<=t<88 and observed[t] and counts[t]==0:
                    if t and observed[t-1]:survival*=1-float(p[t-1])
                    probability[t]=1-survival
                else:survival=1.
        states=[classify(float(probability[t]),self.threshold,present=bool(present[t]),phone_on=bool(phone_on[t]),
                 usage_ok=bool(usage_ok[t]),network_ok=bool(network_ok[t]),observed=bool(observed[t]),
                 heartbeat_age_bins=int(heartbeat_age_bins[t]),slot=t,life_tag=bool(life_tag[t]),interactions=int(counts[t])) for t in range(96)]
        if self.config.get('alert_policy')=='latched_until_sign_of_life':
            latched=False
            for t,state in enumerate(states):
                if state in ['ACTIVE','MONITORING_UNAVAILABLE'] or life_tag[t] or t<28 or t>=88:latched=False
                elif state=='UNUSUAL_INACTIVITY':latched=True
                if latched:states[t]='UNUSUAL_INACTIVITY'
        return dict(states=states,probability=probability.tolist())
