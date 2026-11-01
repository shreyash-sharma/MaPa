"""Training-only positive-window supervision, including earlier quiet ticks."""
from .common import ROOT
from .forest import fit
from .evaluate import select
from .calibrate import matched_catch
from mapa_syn.io import read_json,write_json


def run():
    plan=[('cnn-v31-window-tree',False),('cnn-v32-window-monotone',True)]
    write_json(ROOT/'window-training-plan.json',dict(candidates=plan,
        source='cnn-v19-augmented',fit='all eligible positive window ticks with at least four continuously observed quiet bins, training people only',
        test_read=False,data_changes=False))
    for name,monotone in plan:
        fit('cnn-v19-augmented',name,monotonic_quiet=monotone,positive_sampling='window')
        p=ROOT/'models'/name/'complete.json';d=read_json(p)
        d.update(alert_policy='latched_until_sign_of_life',masking_corrected=True);write_json(p,d)
        select(name);r=matched_catch(name)
        print(name,r['C4'],flush=True)


if __name__=='__main__':run()
