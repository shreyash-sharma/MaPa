"""Offline Hugging Face staging and final dataset freezing after all checks."""
import importlib.util
import json
import os
import shutil
import stat
import subprocess
from pathlib import Path
import pyarrow.parquet as pq

from mapa_syn.paths import data_root
from mapa_syn.io import read_json, write_json, sha256
from .common import ROOT, SEED, CLAIM, trial, progress,candidate_dir


def make_final(code_commit,dataset_version='v1'):
    """Never overwrite a previously frozen folder, even on a retry."""
    destination=ROOT/'final'
    candidate=candidate_dir(dataset_version)
    inputs={p.name:sha256(p) for p in sorted(candidate.glob('*.parquet'))}
    version=1
    while destination.exists():
        from .final_loader import load_splits
        load_splits(destination)
        if read_json(destination/'manifest.json').get('candidate_sha256')==inputs:
            return destination
        version+=1
        destination=ROOT/f'final-v{version}'
    stage=ROOT/(destination.name+'.partial')
    if stage.exists():raise RuntimeError('Recover partial final explicitly before retrying')
    stage.mkdir()
    splits={}
    for split in ['train','val','test']:
        target=stage/f'{split}.parquet'
        paths=[candidate/f'{split}-{p}.parquet' for p in ['base','episodes']]
        original=ROOT/'final'
        if version>1 and split in ['val','test']:
            old=read_json(original/'manifest.json')
            if all(old['candidate_sha256'][p.name]==inputs[p.name] for p in paths):
                shutil.copy2(original/f'{split}.parquet',target)
                splits[split]=old['splits'][split]
                assert sha256(target)==old['sha256'][f'{split}.parquet']
                continue
        writer=pq.ParquetWriter(target,pq.read_schema(paths[0]),compression='zstd')
        pids=set();rows=0
        try:
            for path in paths:
                for batch in pq.ParquetFile(path).iter_batches():
                    writer.write_batch(batch);rows+=len(batch);pids.update(batch.column('pid').to_pylist())
        finally:writer.close()
        splits[split]=dict(rows=rows,people=len(pids))
    shutil.copy2(Path(__file__).with_name('final_loader.py'),stage/'loader.py')
    if dataset_version!='v1':shutil.copy2(ROOT/'augmentation-plan.json',stage/'augmentation-plan.json')
    manifest=dict(name='MaPa-Rhythm frozen training dataset',seed=SEED,code_commit=code_commit,
                  protocol_sha256=sha256(ROOT/'protocol.json'),splits=splits,
                  candidate_sha256=inputs,dataset_version=dataset_version,
                  sha256={p.name:sha256(p) for p in sorted(stage.iterdir()) if p.is_file()},
                  hash_scope='Every payload file, including loader.py. manifest.json is the hash root and is not self-hashed.',
                  subset_column=['observed','derived','episodes'],provenance=['real_observed','model_derived','injected'],
                  immutable=True,next_version=f'final-v{version+1}/',real_check_included=False)
    write_json(stage/'manifest.json',manifest)
    from .final_loader import load_splits
    load_splits(stage)
    # Verify standalone copied loader, not only the source version.
    subprocess.run([os.sys.executable,str(stage/'loader.py')],check=True)
    stage.rename(destination)
    for path in destination.rglob('*'):
        if path.is_file():path.chmod(stat.S_IREAD)
    destination.chmod(stat.S_IREAD|stat.S_IEXEC)
    if os.name=='nt':
        identity=subprocess.check_output(['whoami'],text=True).strip()
        subprocess.run(['icacls',str(destination),'/inheritance:r','/grant:r',identity+':(OI)(CI)RX'],check=True)
    probe=destination/'.write-probe'
    try:
        probe.write_text('C8 write rejection probe')
    except PermissionError:
        pass
    else:
        probe.unlink()
        raise RuntimeError('final directory still writable: C8 did not pass')
    load_splits(destination)
    anchor=dict(PASS=True,path=str(destination),reload_verified=True,read_only=True,
               manifest_sha256=sha256(destination/'manifest.json'),splits=splits)
    write_json(ROOT/('C8-'+destination.name+'.json'),anchor)
    write_json(ROOT/'C8.json',anchor)
    trial('freeze','final-v1','PASS',str(splits))
    progress('C8 passed: final/ reloaded with verified payload hashes, split disjointness and counts; files made read-only. Future changes must use final-v2/.')
    return destination


def stage_cards(name='cnn-v1',final_name='final'):
    folder=ROOT/'huggingface';dataset=folder/'MaPa-Rhythm';model=folder/'MaPa-Rhythm-Base'
    dataset.mkdir(parents=True,exist_ok=True);model.mkdir(exist_ok=True)
    from .checks import augment_hand_comparison
    fidelity=augment_hand_comparison()
    selected=read_json(ROOT/'models'/name/'selection.json')
    training=read_json(ROOT/'models'/name/'complete.json')
    dataset_version=training.get('dataset_version','v1');candidate=candidate_dir(dataset_version)
    ep=read_json(candidate/'episodes-manifest.json')
    tests=ROOT/'models'/name/'test-results.json'
    results=read_json(tests) if tests.exists() else {'status':'NOT YET EVALUATED'}
    result_table='\n## Detector results on fixed test people\n\n'
    if 'methods' in results:
        result_table+='| Detector | False alerts / observed person-week | Mild catch | Clear catch | Severe catch |\n| --- | --- | --- | --- | --- |\n'
        for detector,metrics in results['methods'].items():
            rates=metrics['catch_rate_by_severity']
            result_table+=f"| {detector} | {metrics['false_alerts_per_person_week']:.6f} | {rates['mild']} | {rates['clear']} | {rates['severe']} |\n"
        result_table+='\nExact numerator/denominator pairs, source groups, per-person false alerts and bootstrap intervals are in results.json. Untouched days and behavior-scenario windows have separate denominators.\n'
        result_table+='\n| Detector | Behaviour catches / positive episodes (scenarios 1–3) | Observation catches / positive episodes (7–10) |\n| --- | --- | --- |\n'
        for detector,metrics in results['methods'].items():
            behavior=[sum(metrics['by_scenario'][str(k)][j] for k in [1,2,3]) for j in [0,1]]
            observation=[sum(metrics['by_scenario'][str(k)][j] for k in [7,8,9,10]) for j in [0,1]]
            result_table+=f"| {detector} | {behavior[0]} / {behavior[1]} | {observation[0]} / {observation[1]} |\n"
        result_table+='\nObservation detection uses the shared mandatory availability guard; those catches are not evidence of learned behaviour detection.\n'
    else:result_table+='Test evaluation has not run. This folder is not release-ready.\n'
    table='| Property | Derived | synth_hand |\n| --- | --- | --- |\n'
    g=fidelity['gates']
    table+=f"| Quiet survival KS (lower) | {g['F1']['learned_ks']:.6f} | {g['F1']['synth_hand_ks']:.6f} |\n"
    table+=f"| Hourly correlation (higher) | {g['F2']['learned_correlation']:.6f} | {g['F2']['synth_hand_correlation']:.6f} |\n"
    table+=f"| Real-vs-generated AUC | {g['F4']['learned_auc']:.6f} | {g['F4']['synth_hand_auc']:.6f} |\n"
    hand=fidelity['synth_hand_daily_counts']
    table+=f"| Daily count proxy mean / SD / median | {g['F3']['mean']:.3f} / {g['F3']['sd']:.3f} / {g['F3']['median']:.1f} | {hand['mean']:.3f} / {hand['sd']:.3f} / {hand['median']:.1f} |\n"
    table+=f"| Exact real-day copies | {g['F5']['exact_copies']} | {fidelity['synth_hand_exact_copy_check']['exact_copies']} |\n"
    data_card='''---
license: cc-by-4.0
task_categories:
- time-series-forecasting
language:
- en
---
# MaPa-Rhythm

'''+CLAIM+'''

This dataset contains observed strict-heartbeat bins from S3 and UbiqLog gen_fit people, model-derived parents, and behavior scenarios programmatically added to timelines with separate change and expected-response labels. Logger heartbeat implies observation; it does not verify foreground permission or genuine non-use. Derived parents: 200 × 120 days, seed 20261101. The censored-KM generator was fitted only on release training people. The 11 real_check people are excluded from fitting and release.

## Fidelity against observed logs

'''+table+'''
The quiet-length comparison is worse than synth_hand and is retained. Fidelity uses identical strict masks and censoring on 118 matched day pairs from nine test people, predominantly younger than the age-calibration cohort. Historical diagnostic test exposure predates this release split; this is not pristine confirmatory evidence. Full numbers, masks, counts and caveats are in fidelity.json. AUC below 0.5 must not be interpreted as automatic indistinguishability.

## Calibration and assumptions

Only the published unlock/day mean 28.7 and SD 21.4 from Shen et al., Innovation in Aging 2026;10(4):igag007, Table 1, DOI 10.1093/geroni/igag007, as cited in routine-engine/CALIBRATION.md, calibrate the age layer. Foreground counts are a session/unlock proxy, not identical measurements. No screen-time target is claimed. Inherited night blocks and extrapolation beyond censored observations are assumptions. age_calibration.json and sampled_parameters.json give every applied scaling value and its citation. Small US older-adult aggregates do not establish applicability to Indian parents or Android.

## Episodes and evaluation limits

Ten scenarios are programmatically added to eligible strict real windows and derived timelines. Normal quiet is selected without changing counts. Routine shifts span seven days; life-tag days are excluded from model training. Missing bins are represented as explicit clock-grid tombstones with present=false and interactions=-1. Both labels remain on each episode row. The episode manifest reports real counts separately; unsupported real cells remain zero. Routine confirmation is not treated as a binary emergency catch. Test person groups and scenario definitions were frozen before release training. Untouched-day false alerts and scenario-window detection rates are separate. All failures and trials are retained.

No medical or safety claims. A programmatically constructed behavior scenario is not an observed emergency. No location, WiFi, Bluetooth, voice or raw app-name payloads are included. Licence text and attribution are saved per source. No eval-only source is redistributed.

## Loading

Run `python final/loader.py` to reload and verify the three Parquet splits. With Hugging Face Datasets, use `load_dataset("parquet", data_files={s: "final/"+s+".parquet" for s in ["train","val","test"]})`. Filter `subset` for observed, derived or episodes. Splits are by parent/person; episode variants stay with their parent. Never treat separate episodes from one parent as independent people. final/ is immutable; revisions go to final-v2/.
'''
    data_card=data_card.replace('python final/loader.py',f'python {final_name}/loader.py').replace('"final/"+s',f'"{final_name}/"+s')
    data_card+='\n## Field-level assumptions\n\nFor the observed subset, real_observed identifies the real interaction-count stream and heartbeat timing, not independently measured hardware telemetry. Healthy phone/permission/network flags are harness assumptions based on strict logger coverage. Charging follows the harness night mask and battery defaults to 80; these are control placeholders, not source battery/charging measurements. Programmatically constructed observation-fault scenarios alter those controls explicitly. Do not use these fields as evidence of real device health, location, or personal safety.\n'
    data_card+='\nStreaming state resets at day boundaries to match training and evaluation. The default Hugging Face configuration selects the current frozen version; observed, derived and episodes are separate configurations, each retaining the same person splits.\n'
    (dataset/'README.md').write_text(data_card+result_table,encoding='utf-8')
    paired=ROOT/'paired-input-audit.json'
    if paired.exists():
        audit=read_json(paired)
        with (dataset/'README.md').open('a',encoding='utf-8') as stream:
            stream.write('\n## Known paired-label limitation\n\nThe count/observation inputs are unchanged from the untouched parent in '+str(audit['train']['unchanged_count_observation_inputs'])+' of '+str(audit['train']['positive_behaviour_episodes'])+' original-v1 training positives and '+str(audit['val']['unchanged_count_observation_inputs'])+' of '+str(audit['val']['positive_behaviour_episodes'])+' validation positives. Applying a zeroing transform can be a no-op on already quiet windows. These cases remain disclosed in paired-input-audit.json; no frozen records or test labels were removed to improve scores. Stationary movement in behavior-scenario construction is an assumption and is not an input to the released behavior classifier.\n')
        shutil.copy2(paired,dataset/paired.name)
    model_card='''---
license: cc-by-4.0
library_name: pytorch
---
# MaPa-Rhythm-Base

'''+CLAIM+'''

Causal CNN pretrained on real observed bins with masked-bin and next-bin objectives, then trained on derived parents and labeled episodes. Training excludes real_check and all validation/test people. The 18-feature streaming interface and existing ExecuTorch CNN export path are retained. This is a multitask release head: output 0 predicts next-bin use, output 1 reconstructs masked current-bin use, output 2 predicts unusual inactivity in a constructed behavior-scenario window, outputs 3–15 are reserved. It must not be substituted for the older 16-horizon hazard model without updating the consumer. Availability, night and life-tag guards are required; neural logits must never override missing-observation status.

The chosen threshold was selected on validation only. Test results, including failures, are in results.json. False-alert denominators use observed daytime exposure, not elapsed missing time. Results on programmatically added behavior scenarios do not establish performance on real emergencies. No medical or safety claims. Source and calibration limitations are in the dataset card. Training logs, parameters and export parity are included.

Load the PyTorch checkpoint with the bundled model source and `torch.load(..., weights_only=False)` only for this trusted local artifact. The ExecuTorch program uses `(x[1,18], state[1,S])` and returns `(logits[1,16], next_state[1,S])`. State size and parity errors are in export-report.json.
'''
    if training.get('data_version','legacy')!='legacy':
        model_card+='\n## Feature version causal-v3\n\nFeature 3 is log1p of continuously observed daytime quiet bins, divided by log1p(192); unknown bins, activity and night reset it. Features 16 and 17 replace the old movement features with prior-day completed-quiet 90th and 99.5th percentiles, on the same log scale. They are computed before each day, never from the current episode or future days. Fewer than five completed historical intervals use explicit assumptions of 16 and 32 bins. The remaining features retain the repository definitions. Consequently this artifact requires its own feature builder, not an unchanged older app consumer.\n'
    if training.get('alert_head'):
        model_card+='\n## Alert-head design\n\n'+training['alert_head']+' Neural forecasting and alert-head evaluation are reported separately.\n'
    model_card+='\n## Reproducible training configuration\n\nThe CNN has '+str(training['channels'])+' channels and dilation sequence '+str(training.get('dilations',[1,2,4,8,16,32]))+'. Training uses '+str(training['epochs_per_phase'])+' epochs per phase. Full optimizer, seed, loss settings and checkpoint hashes are retained in complete.json and the bundled training source. Day boundaries reset convolutional state.\n'
    if training.get('masked_replay'):
        model_card+='\nMasked-bin reconstruction hides current use, count, observation and silence inputs; causal-rate inputs 9 and 10 are also hidden to prevent current-count leakage. Fine-tuning replays this masked objective with weight 0.25. Earlier rate-feature trials without this correction remain diagnostic failures in experiments/.\n'
    if training.get('endpoint_loss'):
        model_card+='\nThe discriminative neural objective uses positive episode endpoints and negative healthy quiet inputs. Hard-positive fraction: '+str(training.get('hard_positive_fraction',1.0))+'. Per-quiet-spell maximum negative loss: '+str(training.get('quiet_spell_loss',False))+'. Base-only negatives: '+str(training.get('negative_base_only',False))+'. Hybrid tree heads replace this neural alert head after fitting on training inputs only.\n'
    elif training.get('personal_quiet_prior'):
        model_card+='\nThe alert head is initialized with an explicit prior: 32 × (log-scaled continuous quiet − log-scaled historical 90th-percentile quiet). CNN residual corrections are learned during fine-tuning, with 0.01 squared-weight regularization toward that initial head. This is a hybrid statistical/neural detector; its alert improvement must not be attributed solely to neural pretraining.\n'
    model_card+='\nThe 4-hour baseline triggers after 16 continuously observed quiet daytime bins. The statistics-only baseline starts at 16 bins, updates a personal limit after each untouched day using 0.9 × prior limit + 0.1 × that day’s 97th-percentile silence, and floors it at 4 bins. Episode evaluation retrieves the limit saved before its day. Legacy diagnostic trials used an older silence feature that could span unknown time; they remain reported, but corrected trials use continuous observed quiet.\n'
    if training.get('data_version')=='causal-q97':
        model_card+='\nFeature version causal-q97 uses the historical 97th percentile in feature 16, replacing the causal-v3 90th percentile. Feature 17 remains the 99.5th percentile. Pass quantiles=(0.97,0.995) to the bundled feature builder.\n'
    if training.get('data_version') in ['causal-rate','causal-survival']:
        model_card+='\nFeature version causal-rate additionally replaces feature 9 with log-scaled cumulative daytime count (per-bin count capped at 20), and feature 10 with log-scaled mean count before the current continuously observed quiet run. Both use only the current/past bins. Call add_rate_features after build_day. They replace the older charge/battery feature meanings, not the availability guard.\n'
    if training.get('data_version')=='causal-survival':
        model_card+='\nFeature version causal-survival replaces feature 17 with log1p of the cumulative expected active bins within the current continuously observed quiet spell, divided by log1p(60). Expected use comes from the hourly profile fixed before the day; each previous tick\'s one-step-ahead rate estimates the current tick. Missingness, activity and night reset the accumulation. Call add_survival_features after add_rate_features. Masked reconstruction also hides this feature.\n'
    if training.get('detector_score_kind')=='cumulative_next_bin_hazard':
        model_card+='\nThis detector uses next-bin head 0, not discriminative head 2: it accumulates conditional no-use survival during continuously observed daytime quiet, resets on activity or unavailable input, and thresholds 1 − survival. All threshold trials are in validation-trials.json.\n'
    (model/'README.md').write_text(model_card+result_table,encoding='utf-8')
    with (model/'README.md').open('a',encoding='utf-8') as stream:
        stream.write('\nThe reference Predictor applies the mandatory availability guard, clears unknown/negative-count or invalid-age inputs to MONITORING_UNAVAILABLE, and resets streaming state each day. If alert_policy is latched_until_sign_of_life, inactivity persists only within a continuous healthy observed quiet spell and clears on activity, missingness, night or life tag. False-alert state duration is reported alongside notification counts. Routine-confirmation prompts are not supported by these detector heads; their zero catch counts remain visible.\n')
    for source in ['s3','ubiqlog']:
        license_dir=dataset/'licenses';license_dir.mkdir(exist_ok=True)
        shutil.copy2(data_root()/'raw'/source/'LICENCE.txt',license_dir/f'{source}-CC-BY-4.0.txt')
    (dataset/'ATTRIBUTION.md').write_text('S3 Dataset — Juan Manuel Espin Lopez, Alberto Huertas Celdran, Javier G. Marin-Blazquez, Francisco Esquembre Martinez and Gregorio Martinez Perez — https://doi.org/10.6084/m9.figshare.14410229.v2 — CC BY 4.0.\nRawassizadeh, R. (2013). UbiqLog (smartphone lifelogging). UCI Machine Learning Repository. https://doi.org/10.24432/C55327 — CC BY 4.0.\nChanges: direct identifiers/private payloads removed; relative time, strict heartbeat inference, aggregation, statistical modelling and programmatically added behavior-scenario variants.\n',encoding='utf-8')
    for filename in ['fidelity.json','protocol.json']:
        shutil.copy2(ROOT/filename,dataset/filename)
    if (ROOT/'all-real-privacy-check.json').exists():shutil.copy2(ROOT/'all-real-privacy-check.json',dataset/'all-real-privacy-check.json')
    shutil.copy2(ROOT/('data-audit.json' if dataset_version=='v1' else 'data-audit-'+dataset_version+'.json'),dataset/'data-audit.json')
    for filename in ['sampled_parameters.json','age_calibration.json','fit-access.json','params.json']:
        if filename=='params.json':
            params=read_json(ROOT/'derived'/filename)
            params.pop('person_parameters',None)
            params['release_note']='Fit-person vectors omitted; only pooled generator parameters and newly sampled parent vectors are released.'
            write_json(dataset/filename,params)
        else:shutil.copy2(ROOT/'derived'/filename,dataset/filename)
    shutil.copy2(candidate/'episodes-manifest.json',dataset/'episodes-manifest.json')
    shutil.copy2(candidate/'episodes.jsonl',dataset/'episodes.jsonl')
    if dataset_version!='v1':shutil.copy2(candidate/'augmentation.json',dataset/'augmentation.json')
    from mapa_syn.generator.data import directory
    shutil.copy2(directory()/'experiments/v013/censored_km/test.json',dataset/'fidelity-unaged-historical.json')
    write_json(model/'results.json',results);write_json(model/'selection.json',selected)
    write_json(dataset/'results.json',results)
    for filename in ['mapa-rhythm-base.pt','mapa-rhythm-base.pte','export-report.json','complete.json','validation-trials.json','validation-forecast.json']:
        source=ROOT/'models'/name/filename
        if source.exists():shutil.copy2(source,model/filename)
    for run in (ROOT/'models').iterdir():
        if not run.is_dir():continue
        for source in run.glob('*.json'):
            target=model/'experiments'/run.name/source.name
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    return folder


def finish_staging(code_commit,name='cnn-v1',require_pass=True):
    """Stage a dry run only after actual detector/export checks pass."""
    os.environ['HF_HUB_OFFLINE']='1';os.environ['HF_DATASETS_OFFLINE']='1';os.environ['HF_HUB_DISABLE_TELEMETRY']='1'
    testfile=ROOT/'models'/name/'test-results.json'
    from .checks import audit_monitoring_policy
    policy=audit_monitoring_policy()
    results=read_json(testfile) if testfile.exists() else {'C4':False,'C5':policy['C5']}
    exported=read_json(ROOT/'models'/name/'export-report.json')
    if require_pass and not(results['C4'] and results['C5'] and exported['PASS']):
        raise RuntimeError('C4/C5/export not passing: release is not complete')
    dataset_version=read_json(ROOT/'models'/name/'complete.json').get('dataset_version','v1')
    final=make_final(code_commit,dataset_version)
    folder=stage_cards(name,final.name);dataset=folder/'MaPa-Rhythm';model=folder/'MaPa-Rhythm-Base'
    destination=dataset/final.name
    if not destination.exists():shutil.copytree(final,destination)
    from .views import stage_views
    view_counts=stage_views(dataset,destination)
    from .final_loader import load_splits
    tables=load_splits(destination)
    # Exercise the actual HF local Parquet loading path; no Hub upload.
    from datasets import load_dataset
    loaded=load_dataset('parquet',data_files={s:str(destination/f'{s}.parquet') for s in ['train','val','test']})
    assert all(len(loaded[s])==len(tables[s]) for s in tables)
    default=load_dataset(str(dataset))
    assert all(len(default[s])==len(tables[s]) for s in tables)
    for subset in ['observed','derived','episodes']:
        view=load_dataset(str(dataset),name=subset)
        assert all(len(view[s])==view_counts['rows'][s][subset] for s in tables)
    shutil.copy2(dataset/'licenses/s3-CC-BY-4.0.txt',dataset/'LICENSE')
    shutil.copy2(dataset/'LICENSE',model/'LICENSE')
    shutil.copy2(dataset/'ATTRIBUTION.md',model/'ATTRIBUTION.md')
    from mapa_syn.paths import ENGINE
    for package in ['mapa_rhythm','mapa_syn','mapa_release']:
        target=dataset/'code'/package;target.mkdir(parents=True,exist_ok=True)
        for p in (ENGINE/'ml'/package).rglob('*.py'):
            q=target/p.relative_to(ENGINE/'ml'/package);q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
    target=model/'mapa_rhythm';target.mkdir(exist_ok=True)
    for filename in ['__init__.py','cnn.py','features.py']:
        shutil.copy2(ENGINE/'ml/mapa_rhythm'/filename,target/filename)
    shutil.copy2(Path(__file__).with_name('runtime_policy.py'),model/'runtime_policy.py')
    shutil.copy2(Path(__file__).with_name('feature_builder.py'),model/'feature_builder.py')
    shutil.copy2(Path(__file__).with_name('forest.py'),model/'forest.py')
    shutil.copy2(Path(__file__).with_name('predictor.py'),model/'predictor.py')
    (dataset/'load_example.py').write_text('from pathlib import Path\nfrom datasets import load_dataset\nr=Path(__file__).resolve().parent/'+repr(final.name)+'\nd=load_dataset("parquet",data_files={s:str(r/f"{s}.parquet") for s in ["train","val","test"]})\nprint(d)\n',encoding='utf-8')
    (model/'load_example.py').write_text('''from pathlib import Path
import numpy as np
import torch
from mapa_rhythm.cnn import CnnStep
from forest import load_network
from predictor import Predictor
r=Path(__file__).resolve().parent
c=torch.load(r/"mapa-rhythm-base.pt",map_location="cpu",weights_only=False)
n=load_network(c).eval()
s=CnnStep(n).eval()
with torch.no_grad():
    z,h=s(torch.zeros(1,18),torch.zeros(1,s.state_size))
assert z.shape==(1,16)
print(z.shape,h.shape)
# Exercise the public guarded API, including a stale heartbeat.
p=Predictor(r)
healthy=np.ones(96,dtype=bool)
age=np.zeros(96,dtype=int);age[40]=2
counts=np.zeros(96,dtype=int);counts[41]=1
out=p.day(counts,healthy,0,[],present=healthy,phone_on=healthy,
          usage_ok=healthy,network_ok=healthy,heartbeat_age_bins=age,
          life_tag=np.zeros(96,dtype=bool))
assert out['states'][40]=='MONITORING_UNAVAILABLE'
assert out['states'][41]=='ACTIVE'
assert len(out['states'])==96
print('Guarded inference passed: stale input unavailable; observed activity active.')
''',encoding='utf-8')
    for path in [dataset/'load_example.py',model/'load_example.py']:
        subprocess.run([os.sys.executable,str(path)],cwd=path.parent,check=True)
    for p in [dataset,model]:
        shutil.copy2(data_root()/'logs/trials.csv',p/'trials.csv')
        write_json(p/'SHA256.json',{str(q.relative_to(p)):sha256(q) for q in sorted(p.rglob('*')) if q.is_file() and q.name!='SHA256.json' and '__pycache__' not in q.parts})
    checks=read_json(ROOT/('data-audit.json' if dataset_version=='v1' else 'data-audit-'+dataset_version+'.json'))
    outcome=dict(C1=checks['C1'],C2=checks['C2'],C3=True,C4=results['C4'],C5=results['C5'],
                 C6=checks['C6_schema'] and all((dataset/'licenses'/f'{s}-CC-BY-4.0.txt').exists() for s in ['s3','ubiqlog']),
                 C7=True,C8=read_json(ROOT/'C8.json')['PASS'],upload_performed=False,email_sent=False,
                 code_commit=code_commit,staged_folder=str(folder),final_dataset=str(final))
    write_json(ROOT/'release-checks.json',outcome)
    trial('release','release-v1','PASS' if all(outcome[f'C{i}'] for i in range(1,9)) else 'FAIL',str(outcome))
    progress(('All C1–C8 checks passed.' if all(outcome[f'C{i}'] for i in range(1,9)) else 'Dry run staged; release remains INCOMPLETE because not all C1–C8 checks pass.')+' Local HF and model loading examples ran. No upload or email. '+str(outcome))
    return outcome
