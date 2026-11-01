"""Fixed training-only monotonic-head candidates; validation selects operating points."""
from .common import ROOT,trial
from .forest import fit
from .evaluate import select
from .calibrate import matched_catch
from mapa_syn.io import read_json,write_json


def run():
    plan=[('cnn-v25-monotone-tree',2,100),('cnn-v26-monotone-tree',3,200),('cnn-v27-monotone-tree',4,200)]
    write_json(ROOT/'monotone-plan.json',dict(candidates=plan,source='cnn-v19-augmented',
        constraint='Risk nondecreasing in continuous observed quiet feature, with other features fixed',
        fit='train only',test_read=False,data_changes=False))
    for name,depth,trees in plan:
        fit('cnn-v19-augmented',name,monotonic_quiet=True,max_depth=depth,max_iter=trees)
        p=ROOT/'models'/name/'complete.json';d=read_json(p)
        d.update(alert_policy='latched_until_sign_of_life',masking_corrected=True);write_json(p,d)
        select(name);r=matched_catch(name)
        print(name,r['C4'],flush=True)


if __name__=='__main__':run()
