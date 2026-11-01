"""Train-only maximum-risk quiet-spell mining for the portable alert head."""
from .common import ROOT
from .forest import fit
from .evaluate import select
from .calibrate import matched_catch
from mapa_syn.io import read_json,write_json


def run():
    plan=[('cnn-v28-spell-tree',False),('cnn-v29-spell-monotone',True)]
    write_json(ROOT/'spell-mining-plan.json',dict(candidates=plan,rounds=3,
        source='cnn-v19-augmented',fit='train only',test_read=False,data_changes=False))
    for name,monotone in plan:
        fit('cnn-v19-augmented',name,monotonic_quiet=monotone,spell_mining_rounds=3)
        p=ROOT/'models'/name/'complete.json';d=read_json(p)
        d.update(alert_policy='latched_until_sign_of_life',masking_corrected=True);write_json(p,d)
        select(name);r=matched_catch(name)
        print(name,r['C4'],flush=True)


if __name__=='__main__':run()
