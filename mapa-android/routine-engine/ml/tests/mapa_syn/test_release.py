import numpy as np
import pytest
from mapa_release.build import day_table


def test_release_real_missing_time_stays_unobserved_not_quiet():
    p = dict(pid='s3-test',source='s3',start_dow=0)
    counts=np.zeros(96,dtype=int)
    observed=np.zeros(96,dtype=bool); observed[[1,70]]=True
    table=day_table(p,0,counts,observed,'train','observed')
    assert table['t'].to_pylist()==[1,70]
    assert table['provenance'].to_pylist()==['real_observed']*2
    context=day_table(p,0,counts,observed,'train','episodes')
    assert context['interactions'][2].as_py()==-1
    assert not context['observed'][2].as_py()


def test_release_permission_loss_is_never_known_quiet():
    from mapa_release.inject import inject_day
    p=dict(pid='derived-0000',source='derived',start_dow=0)
    c=np.ones((20,96),int); o=np.ones_like(c,bool)
    table,meta=inject_day(p,c,o,14,'train',10,'mild',32,4,'monitoring_unavailable')
    d=table.to_pydict()
    assert d['interactions'][32:36]==[-1]*4
    assert d['observed'][32:36]==[False]*4
    assert d['present'][32:36]==[True]*4
    assert d['provenance'][32:36]==['injected']*4
    assert c[14,32]==1  # Untouched parent was not mutated.
    assert meta['expected']=='monitoring_unavailable'


def test_release_stale_and_permission_failure_have_no_fresh_state():
    from mapa_release.evaluate import monitoring_unavailable
    assert not monitoring_unavailable(True,True,True,True,True,age_bins=0)
    for i in range(5):
        args=[True]*5;args[i]=False
        assert monitoring_unavailable(*args)
    assert monitoring_unavailable(True,True,True,True,True,age_bins=2)


def test_episode_statistics_threshold_never_uses_future_untouched_day(monkeypatch):
    from mapa_release import evaluate as ev
    from mapa_release.inject import inject_day
    p=dict(pid='derived-0000',source='derived',start_dow=0)
    c=np.zeros((101,96),int);o=np.ones_like(c,bool)
    frames={}
    groups=[]
    for day in [0,100]:
        frames[(p['pid'],'',day)]=day_table(p,day,c[day],o[day],'val','derived').to_pandas()
        groups.append(dict(pid=p['pid'],episode_id='',day=day))
    table,meta=inject_day(p,c,o,0,'val',1,'mild',32,12,'unusual_inactivity')
    eid=meta['episode_id'];frames[(p['pid'],eid,0)]=table.to_pandas()
    groups.append(dict(pid=p['pid'],episode_id=eid,day=0))
    x=np.zeros((3,96,18),np.float32)
    x[2,:,3]=np.log1p(20)/np.log1p(192)
    scores=np.zeros((3,96))
    monkeypatch.setattr(ev,'evaluation_inputs',lambda *args:({'x':x},groups,scores,frames))
    before=ev.evaluate('val','fixture',.5)['methods']['statistics_only']['by_scenario']['1']
    x[1,:,3]=1  # A later day radically changes its gap distribution.
    after=ev.evaluate('val','fixture',.5)['methods']['statistics_only']['by_scenario']['1']
    assert before==after==[1,1]


def test_serving_policy_never_calls_unknown_or_stale_data_fresh():
    import itertools
    from mapa_release.runtime_policy import classify
    keys=['present','phone_on','usage_ok','network_ok','observed']
    for bits in itertools.product([False,True],repeat=5):
        for age in [0,1,2,100]:
            status=classify(1.,.1,**dict(zip(keys,bits)),heartbeat_age_bins=age,interactions=50)
            if not all(bits) or age>1:assert status=='MONITORING_UNAVAILABLE'
    assert classify(1.,.1,**dict.fromkeys(keys,True),slot=10)=='QUIET'
    assert classify(1.,.1,**dict.fromkeys(keys,True),life_tag=True)=='QUIET'
    assert classify(1.,.1,**dict.fromkeys(keys,True),interactions=-1)=='MONITORING_UNAVAILABLE'
    assert classify(1.,.1,**dict.fromkeys(keys,True),heartbeat_age_bins=-1)=='MONITORING_UNAVAILABLE'


def test_feature_builder_never_counts_missing_time_as_observed_quiet():
    from mapa_release.feature_builder import build_day,completed_quiet
    counts=np.zeros(96,int);observed=np.ones(96,bool);observed[40]=False
    x=build_day(counts,observed,0,[])
    assert x[40,3]==0
    assert np.isclose(np.expm1(x[41,3]*np.log1p(192)),1)
    assert np.all(x[:28,3]==0)
    counts[:]=1;counts[35:40]=0
    # A missing end boundary censors that interval; it cannot enter quantiles.
    assert completed_quiet(counts,observed)==[]


def test_causal_rate_features_cannot_read_future_counts():
    from mapa_release.feature_builder import build_day,add_rate_features
    c=np.zeros(96,int);o=np.ones(96,bool);c[30]=3
    before=add_rate_features(build_day(c,o,0,[]))
    c[70]=20
    after=add_rate_features(build_day(c,o,0,[]))
    assert np.array_equal(before[:70],after[:70])
    assert before[35,9]>0 and before[35,10]>0


@pytest.mark.parametrize('data_version',['causal-rate','causal-survival'])
def test_model_training_never_opens_test_or_real_check_arrays(tmp_path,monkeypatch,data_version):
    import torch
    from mapa_release import learning
    root=tmp_path/'release';root.mkdir();(root/'protocol.json').write_text('{}')
    for split in ['train','val']:
        x=np.zeros((2,96,18),np.float32);x[:,:,2]=1;x[:,:,9:11]=1;x[:,:,17]=1
        np.savez(root/f'{split}.npz',x=x,y=np.zeros((2,96,16),np.float32),mask=np.ones((2,96,16),bool),
                 real=np.array([True,False]),episode=np.array([False,True]),tag=np.array([False,False]))
    calls=[]
    def prepare(split,version,dataset_version='v1'):
        assert split in ['train','val']
        assert dataset_version=='v1'
        calls.append(split);return root/f'{split}.npz'
    original=np.load
    def guarded(path,*args,**kwargs):
        assert path in [root/'train.npz',root/'val.npz']
        return original(path,*args,**kwargs)
    monkeypatch.setattr(learning,'ROOT',root)
    monkeypatch.setattr(learning,'freeze_protocol',lambda:None)
    monkeypatch.setattr(learning,'prepare_arrays',prepare)
    monkeypatch.setattr(learning,'trial',lambda *args:None)
    monkeypatch.setattr(learning,'progress',lambda *args:None)
    monkeypatch.setattr(torch.cuda,'is_available',lambda:False)
    monkeypatch.setattr(np,'load',guarded)
    original_forward=learning.CausalCnn.forward
    hidden_seen=[]
    def checked_forward(self,x,h0=None):
        hidden=x[:,:,2]==0
        if hidden.any():
            hidden_seen.append(True)
            assert torch.all(x[:,:,9:11][hidden]==0)
            if data_version=='causal-survival':assert torch.all(x[:,:,17][hidden]==0)
        return original_forward(self,x,h0)
    monkeypatch.setattr(learning.CausalCnn,'forward',checked_forward)
    result=learning.train(channels=2,epochs=1,name='fit-access-fixture',data_version=data_version)
    assert calls==['train','val']
    assert result['test_read'] is False
    assert hidden_seen


def test_expected_missed_activity_is_causal_and_resets_on_loss_or_activity():
    from mapa_release.feature_builder import add_survival_features
    x=np.zeros((96,18),np.float32);x[:,2]=1;x[:,11]=.25
    a=add_survival_features(x)
    assert np.isclose(np.expm1(a[31,17]*np.log1p(60)),1.)
    x[50:,11]=.9
    b=add_survival_features(x)
    assert np.array_equal(a[:50],b[:50])
    x[32,2]=0;x[35,0]=1
    c=add_survival_features(x)
    assert c[32,17]==c[35,17]==0
    assert np.isclose(np.expm1(c[36,17]*np.log1p(60)),.25)


@pytest.mark.parametrize('input_features',[18,66])
def test_portable_forest_head_matches_teacher_probabilities(input_features):
    import torch
    from sklearn.ensemble import HistGradientBoostingClassifier
    from threadpoolctl import threadpool_limits
    from mapa_release.forest import convert
    rng=np.random.default_rng(5)
    x=rng.normal(size=(300,input_features)).astype(np.float32);y=(x[:,0]+x[:,1]>0).astype(int)
    with threadpool_limits(limits=1):
        teacher=HistGradientBoostingClassifier(max_iter=5,max_leaf_nodes=4,max_depth=2,early_stopping=False,random_state=5).fit(x,y)
        expected=teacher.predict_proba(x)[:,1]
    head,spec=convert(teacher,torch.nn.Linear(input_features+4,16))
    with torch.no_grad():
        inputs=torch.cat([torch.zeros(len(x),4),torch.from_numpy(x)],1)
        actual=torch.sigmoid(head(inputs)[:,2]).numpy()
    assert spec['depth']<=2
    assert spec['input_features']==input_features
    assert np.max(np.abs(actual-expected))<1e-6


def test_alert_latch_deduplicates_quiet_but_resets_on_loss_or_activity():
    from mapa_release.runtime_policy import latch_alerts
    assert latch_alerts([False,True,False,True],[True]*4)==[False,True,True,True]
    assert latch_alerts([True,False,False,False],[True,True,False,True])==[True,True,False,False]


def test_hf_views_select_latest_frozen_version_without_duplicate_files(tmp_path):
    import pyarrow as pa
    import pyarrow.parquet as pq
    import yaml
    from mapa_release.views import stage_views
    dataset=tmp_path/'dataset';dataset.mkdir();final=dataset/'final-v2';final.mkdir()
    (dataset/'README.md').write_text('---\nlicense: cc-by-4.0\n---\nTest card\n')
    for split in ['train','val','test']:
        pq.write_table(pa.table({'subset':['observed','derived','episodes'],'value':[1,2,3]}),final/f'{split}.parquet')
    result=stage_views(dataset,final)
    assert result['rows']['train']==dict(observed=1,derived=1,episodes=1)
    header=yaml.safe_load((dataset/'README.md').read_text().split('---')[1])
    config=next(c for c in header['configs'] if c.get('default'))
    assert all(r['path'].startswith('final-v2/') for r in config['data_files'])
    for subset,value in [('observed',1),('derived',2),('episodes',3)]:
        assert pq.read_table(dataset/'data'/subset/'train.parquet')['value'].to_pylist()==[value]
