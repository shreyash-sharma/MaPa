"""Fixed worst-group training weights. Severity is a target, never an input."""
from .common import ROOT
from .forest import fit
from .evaluate import select
from .calibrate import matched_catch
from mapa_syn.io import read_json,write_json


def run():
    plan=[('cnn-v33-severity-tree',False),('cnn-v34-severity-monotone',True)]
    weights=dict(mild=1.,clear=8.,severe=8.)
    write_json(ROOT/'severity-training-plan.json',dict(candidates=plan,weights=weights,
        source='cnn-v19-augmented',fit='train only; severity labels affect sample weights, never inference features',
        test_read=False,data_changes=False))
    for name,monotone in plan:
        fit('cnn-v19-augmented',name,monotonic_quiet=monotone,positive_sampling='window',severity_weights=weights)
        p=ROOT/'models'/name/'complete.json';d=read_json(p)
        d.update(alert_policy='latched_until_sign_of_life',masking_corrected=True);write_json(p,d)
        select(name);r=matched_catch(name)
        print(name,r['C4'],flush=True)


if __name__=='__main__':run()
