"""Training/validation label identifiability audit; never changes frozen data."""
import numpy as np
import pyarrow.parquet as pq
from mapa_syn.io import write_json
from .common import ROOT,trial,progress


def audit():
    result={}
    for split in ['train','val']:
        base=pq.read_table(ROOT/'candidate'/f'{split}-base.parquet',columns=['pid','day_index','t','interactions','observed']).to_pandas()
        reference={}
        for key,frame in base.groupby(['pid','day_index'],sort=False):
            c=np.full(96,-1);o=np.zeros(96,bool);slots=frame.t.to_numpy()%96
            c[slots]=frame.interactions;o[slots]=frame.observed
            reference[key]=(c,o)
        episodes=pq.read_table(ROOT/'candidate'/f'{split}-episodes.parquet').to_pandas()
        positive=0;identical=[]
        for eid,frame in episodes.groupby('episode_id',sort=False):
            if frame.expected.iloc[0]!='unusual_inactivity':continue
            positive+=1
            c,o=reference[(frame.pid.iloc[0],int(frame.day_index.iloc[0]))]
            cc=np.full(96,-1);oo=np.zeros(96,bool);slots=frame.t.to_numpy()%96
            cc[slots]=frame.interactions;oo[slots]=frame.observed
            if np.array_equal(cc,c) and np.array_equal(oo,o):identical.append(str(eid))
        result[split]=dict(positive_behaviour_episodes=positive,unchanged_count_observation_inputs=len(identical),episode_ids=identical)
    result['interpretation']='Some injected positives may have unchanged use/observation inputs; no-op cases are reported, not silently removed. This audit reads train/val only, changes neither data nor labels, and does not use detector scores.'
    write_json(ROOT/'paired-input-audit.json',result)
    trial('paired-input-audit','release-v1','REPORTED',str(result))
    progress('Paired parent/episode input audit recorded in paired-input-audit.json. Counts and labels remain frozen; any no-op positive cases are disclosed rather than hidden.')
    return result


if __name__=='__main__':print(audit())
