"""Render every current-protocol fit outcome; read no dataset rows."""
import csv
import json
from collections import Counter
from pathlib import Path
from mapa_release.common import ROOT


def write_report():
    protocol = json.loads((ROOT / 'training-protocol-v3.json').read_text())
    log = ROOT.parent / 'logs' / 'trials.csv'
    events = []
    with log.open(encoding='utf-8-sig', newline='') as stream:
        for event in csv.DictReader(stream):
            name = event['trial_id']
            if event['stage'] == 'cv-fit' and ('hybrid-v3-fold' in name or name.startswith('hybrid-ablation-')):
                events.append(event)
    latest = {event['trial_id']: event for event in events}
    statuses = Counter(event['status'] for event in latest.values())
    lines = ['# Training progress', '', 'Train-only person CV. Validation and test results are not inferred from fit completion.', '',
             f"Protocol version: {protocol['protocol_version']}; folds: 5; seeds: 5.", '',
             f'Latest fit states: {dict(statuses)}', '',
             '| Fit | Status | Timestamp UTC |', '|---|---|---|']
    for name, event in sorted(latest.items()):
        lines.append(f"| {name} | {event['status']} | {event['timestamp_utc']} |")
    lines += ['', '## Failures and stopped fits', '']
    failures = [event for event in events if event['status'] in {'FAIL', 'STOPPED'}]
    if not failures:
        lines.append('No failed or stopped fits recorded for this protocol run so far.')
    for event in failures:
        detail = event['details'].replace('|', '/').replace('\n', ' ')
        lines.append(f"- {event['trial_id']}: {detail}")
    lines += ['', '## Completed fit coverage', '', '| Fit | Real stage-1 bins | Real days passing 14 h |', '|---|---:|---:|']
    for fit in sorted((ROOT / 'models').glob('*hybrid-v3/fold*-seed*/fit.json')):
        info = json.loads(fit.read_text())
        lines.append(f"| {fit.parent.parent.name}/{fit.parent.name} | {info.get('stage1_real_observed_bins', 'not recorded')} | {info.get('real_days_passing_14h', 'not recorded')} |")
    destination = ROOT / 'results' / 'training-progress.md'
    destination.parent.mkdir(exist_ok=True)
    destination.write_text('\n'.join(lines) + '\n')
    return destination


if __name__ == '__main__':
    print(write_report())
