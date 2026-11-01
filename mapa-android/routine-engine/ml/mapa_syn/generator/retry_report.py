"""Record the bounded retry outcome without promoting a failed sample."""
import json

from ..io import read_json, sha256, write_json
from ..paths import data_root, version_root
from .data import directory


def decision(results):
    if len(results) != 4 or len({r['candidate'] for r in results}) != 4:
        raise RuntimeError('All four frozen candidate outcomes are required')
    winners = [r for r in results if all(g['pass'] for g in r['scores']['gates'].values())]
    return 'synth_learned' if winners else 'synth_hand'


def finalize_negative_retry(results):
    root, data = directory(), data_root()
    if decision(results) != 'synth_hand':
        raise RuntimeError('Passing candidate requires age-layer sampling before promotion')
    work = root / 'experiments/v013'
    roles = read_json(root / 'roles-v013.json')
    audit = read_json(work / 'fit-access.json')
    forbidden = {p['pid'] for p in roles['people'] if p['partition'] != 'fit'}
    assert not set(audit['opened_pids']) & forbidden
    baseline = data / 'converted/synthetic'
    summary = read_json(baseline / 'summary.json')
    assert sha256(baseline / 'bins.csv.gz') == summary['bins_sha256']
    manifest = dict(spec_revision='0.1.3', primary='synth_hand',
                    primary_path=str(baseline), parents=summary['parents'], days=summary['days'],
                    bins=summary['bins'], bins_sha256=summary['bins_sha256'],
                    observation_basis='synthetic', learned_promoted=False,
                    learned_gate='FAIL', candidate_limit=4, candidates_evaluated=4,
                    real_training_candidates=[str(data / 'converted' / s / 'heartbeat_inferred') for s in ['s3', 'ubiqlog']],
                    real_fit_roles=str(root / 'roles-v013.json'),
                    real_data_warning='Mixed-source CSVs include real_check: use role-filtered physical shards, never unfiltered training.',
                    review_required=True, injection_allowed=False, rhythm_training_performed=False,
                    upload_performed=False)
    write_json(root / 'data-manifest.json', manifest)
    outcome = dict(stage='S2b', spec_revision='0.1.3', gate='FAIL', execution='COMPLETE',
                   decision='synth_hand remains primary under DESIGN.md v0.1.3 rule 9',
                   results=results, injection_allowed=False, age_layer_applied_to_retry=False,
                   age_layer_reason='No candidate passed pre-age fidelity; no post-age learned sample is authorized.')
    # R1 copies remain intact; these files identify the current stage outcome.
    write_json(root / 'stage-S2a.json', dict(stage='S2a', gate='PASS', spec_revision='0.1.3',
               model_path=str(work / 'candidate-models.json'), model_sha256=sha256(work / 'candidate-models.json'),
               fit_access=str(work / 'fit-access.json'), real_check_read_by_fit=0,
               tune_or_test_read_by_fit=0, role_counts=audit['role_counts']))
    write_json(root / 'stage-S2b.json', outcome)
    write_json(root / 'fidelity-v013.json', results)
    write_json(version_root() / 'review_gate.json', dict(injection_allowed=False,
               state='v0.1.3_S2b_complete_review_required', S2b_gate='FAIL', primary='synth_hand'))
    rows = []
    for r in results:
        g = r['scores']['gates']
        rows.append(f"| {r['candidate']} | {g['F1']['learned_ks']:.6f} FAIL | {g['F2']['learned_correlation']:.6f} {'PASS' if g['F2']['pass'] else 'FAIL'} | {g['F3']['median']:.1f} PASS | {g['F4']['learned_auc']:.6f} PASS | {g['F5']['minimum_distance']:.6f} PASS | FAIL |")
    report = '''## v0.1.3 bounded retry — completed, learned gate FAIL

All four frozen candidates were generated (200 parents × 120 days each, seed 20261101) and evaluated once each on the frozen fidelity-test group. No candidate passed all gates. DESIGN.md v0.1.3 rule 9 applies: synth_hand remains primary. No learned candidate was promoted. No injection, rhythm-model training or upload occurred.

| Candidate | F1 KS | F2 correlation | F3 daily median | F4 AUC | F5 minimum NN distance | Overall |
| --- | --- | --- | --- | --- | --- | --- |
''' + '\n'.join(rows) + '''

Hand comparison: F1 KS 0.076976; F2 correlation 0.131812; F4 AUC 0.785766. Lower KS/AUC and higher correlation are the specified rules. F3 real exposure-adjusted proxy IQR is [0, 570.75], not a published older-adult range. All four have zero exact real-day copies among 24,000 generated days against 1,729 available real days. This is an exact-copy check, not an anonymity guarantee.

Fidelity is before the age layer. synth_hand is unchanged, including its existing age assumptions. Both synthetic sets use identical strict real observation masks and censoring. There are 118 matched day pairs from the nine fidelity-test people. F1 uses UbiqLog quiet records only. No over-60 layer was applied to a failed retry candidate. The implemented age layer uses only CALIBRATION.md's TS 28.7 ± 21.4 unlock/day aggregate; its count-proxy mapping remains an assumption and is not a validated age effect.

Partitions: 26 fit / 9 tune / 9 fidelity-test from 44 timestamp-usable gen_fit people, plus 11 real_check. The earlier exposed holdout became tune. The newly designated test people had appeared in the historical R1 fit: these results are repeated diagnostics, not pristine confirmatory evidence. Fitting opens fit-only physical shards; real_check is read only by the evaluation privacy check. Candidate settings were frozen before test access. All losers, tune scores, test scores and sample hashes remain in generator/experiments/v013/. R1 remains a failed diagnostic in experiments/r1/ and failed-samples/.

F4 caveat: the fixed rule says lower AUC is better; an AUC below 0.5 is not proof of indistinguishability because reversing scores changes AUC to 1−AUC. Direction-invariant diagnostic AUCs are recorded separately, without changing the frozen gate. F1 fails for every candidate regardless of that issue.

Primary data: converted/synthetic/bins.csv.gz, 2,304,000 bins, 200 parents × 120 days, seed 20261031. generator/data-manifest.json identifies the primary data and role-filtered real inputs. Every generated timeline retains its synthetic provenance. Existing baseline data hashes are unchanged. No new external dataset was admitted.

Required fallback future-release wording, conditional on actual completion of pretraining (which has not run): “MaPa is pretrained on real smartphone logs and on synthetic parents calibrated to published over-60 studies.” Current factual claim: the data and generator diagnostics are prepared; no rhythm model was pretrained in this task.

Stop point: S2b completed with FAIL; review gate remains closed. A fifth candidate or a changed fidelity protocol requires a spec revision; no gate was weakened to claim success.
'''
    status = data / 'logs/build-status.md'
    old = status.read_text(encoding='utf-8')
    marker = '## v0.1.3 bounded retry'
    status.write_text(old.split(marker)[0].rstrip() + '\n\n' + report, encoding='utf-8')
    card = root / 'generator_card.md'
    previous = card.read_text(encoding='utf-8')
    card.write_text(previous.split(marker)[0].rstrip() + '\n\n' + report, encoding='utf-8')
    diagnostics = {r['candidate']: dict(reported_auc=r['scores']['gates']['F4']['learned_auc'],
                   direction_invariant_auc=max(r['scores']['gates']['F4']['learned_auc'], 1-r['scores']['gates']['F4']['learned_auc'])) for r in results}
    write_json(work / 'auc-interpretation.json', diagnostics)
    write_json(work / 'SHA256.json', {str(p.relative_to(work)): sha256(p) for p in sorted(work.rglob('*')) if p.is_file() and p.name != 'SHA256.json'})
    return outcome
