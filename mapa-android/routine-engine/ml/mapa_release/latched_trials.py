"""Predeclared serving-policy trials; no data or test-definition changes."""
import shutil
from .common import ROOT,trial
from mapa_syn.io import read_json,write_json
from .evaluate import select,final_test
from .calibrate import matched_catch

TRIALS=[('cnn-v4','cnn-v15-neural-latched'),('cnn-v7-q97','cnn-v16-q97-latched'),('cnn-v9-mil','cnn-v17-mil-latched')]


def run():
    write_json(ROOT/'latched-trial-plan.json',dict(candidates=TRIALS,
               selection='Declared before model-test evaluation; fit unchanged; validate then test only if validation C4 passes; stop on first passing test.',
               change='Latch until sign of life, unknown observation, night or life tag; apply the same notification-state policy to every detector.'))
    for source_name,name in TRIALS:
        source=ROOT/'models'/source_name;folder=ROOT/'models'/name;folder.mkdir(exist_ok=True)
        if not (folder/'complete.json').exists():
            for filename in ['mapa-rhythm-base.pt','complete.json','val-scores.npy','mapa-rhythm-base.pte','export-report.json']:
                if (source/filename).exists():shutil.copy2(source/filename,folder/filename)
            config=read_json(folder/'complete.json')
            config.update(name=name,source_model=source_name,alert_policy='latched_until_sign_of_life')
            write_json(folder/'complete.json',config)
            trial('serving-policy',name,'STARTED','Frozen source weights '+source_name+'; shared latch policy; no fitting or data changes.')
        selection=select(name)
        if not selection['validation_C4']:
            matched_catch(name);selection=read_json(folder/'selection.json')
        print(name+' validation_C4='+str(selection['validation_C4']),flush=True)
        if selection['validation_C4']:
            result=final_test(name)
            print(name+' test_C4='+str(result['C4'])+' C5='+str(result['C5']),flush=True)
            if result['C4'] and result['C5']:
                write_json(ROOT/'ready-model.json',dict(name=name,selection=str(folder/'selection.json'),results=str(folder/'test-results.json')))
                return name
    return None


if __name__=='__main__':print(run())
