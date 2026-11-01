"""Build release candidates. final/ is created and frozen only by the verifier."""
import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

from mapa_syn.generator.data import directory, load_person
from mapa_syn.generator.calibrated import simulate_calibrated
from mapa_syn.io import read_json, write_json, sha256
from .common import ROOT, SEED, freeze_protocol, trial, progress


def derived():
    protocol = freeze_protocol()
    folder = ROOT / 'derived'
    folder.mkdir(exist_ok=True)
    destination = folder / 'arrays.npz'
    if destination.exists():
        return destination
    models_path = directory() / 'experiments/v013/candidate-models.json'
    model = next(m for m in read_json(models_path) if m['candidate'] == 'censored_km')
    fit_audit = read_json(directory() / 'experiments/v013/fit-access.json')
    allowed = {p['pid'] for p in protocol['people'] if p['split'] == 'train'}
    if not set(fit_audit['opened_pids']) <= allowed:
        raise RuntimeError('Cached generator fitted outside release train people')
    write_json(folder / 'params.json', model)
    write_json(folder / 'fit-access.json', dict(opened_pids=fit_audit['opened_pids'], real_check_opened=[],
               model_sha256=sha256(folder / 'params.json'), cached_models_sha256=sha256(models_path)))
    trial('derived', 'censored-km-age-v1', 'STARTED', '200 x 120; censored KM plus published count-proxy age layer; no detector objectives')
    with np.load(directory() / 'hand_nights.npz', allow_pickle=False) as z:
        result = simulate_calibrated(model, z['counts'], z['observed'], 200, 120, SEED)
    partial = folder / 'arrays.partial.npz'
    np.savez_compressed(partial, **{k: result[k] for k in ['counts', 'observed', 'states', 'start_dow']})
    write_json(folder / 'sampled_parameters.json', result['parameters'])
    write_json(folder / 'age_calibration.json', result['calibration'])
    partial.rename(destination)
    daily = np.maximum(result['counts'], 0).sum(2)
    stats = dict(mean=float(daily.mean()), sd=float(daily.std()), median=float(np.median(daily)), sha256=sha256(destination))
    write_json(folder / 'summary.json', stats)
    trial('derived', 'censored-km-age-v1', 'COMPLETE', str(stats))
    progress('Generated 200 × 120 derived parent-days from the fit-only censored-KM model. Age calibration is a published unlock-count proxy, not observed older-adult timelines. ' + str(stats))
    return destination


SCHEMA = pa.schema([
    ('pid', pa.string()), ('source', pa.string()), ('split', pa.string()), ('subset', pa.string()),
    ('t', pa.int32()), ('day_index', pa.int32()), ('dow', pa.int8()), ('interactions', pa.int32()),
    ('observed', pa.bool_()), ('phone_on', pa.bool_()), ('usage_ok', pa.bool_()), ('network_ok', pa.bool_()),
    ('charging', pa.bool_()), ('battery', pa.int16()), ('present', pa.bool_()), ('moved', pa.int8()),
    ('life_tag', pa.bool_()), ('provenance', pa.string()), ('observation_basis', pa.string()),
    ('episode_id', pa.string()), ('scenario', pa.int8()), ('severity', pa.string()),
    ('injected', pa.string()), ('expected', pa.string()), ('window', pa.bool_()),
])


def day_table(person, day, counts, obs, split, subset, episode=None):
    n = 96
    slot = np.arange(n)
    real = person['source'] != 'derived'
    values = dict(pid=[person['pid']]*n, source=[person['source']]*n, split=[split]*n, subset=[subset]*n,
                  t=day*96+slot, day_index=[day]*n, dow=[(person['start_dow']+day)%7]*n,
                  interactions=np.where(obs, counts, -1).astype(np.int32), observed=obs.copy(), phone_on=obs.copy(),
                  usage_ok=np.ones(n,bool), network_ok=obs.copy(), charging=(slot<28)|(slot>=88),
                  battery=np.full(n,80,np.int16), present=obs.copy(), moved=np.full(n,-1,np.int8), life_tag=np.zeros(n,bool),
                  provenance=['real_observed' if real else 'model_derived']*n,
                  observation_basis=['heartbeat_inferred' if real else 'synthetic_learned']*n,
                  episode_id=['']*n, scenario=np.zeros(n,np.int8), severity=['none']*n,
                  injected=['none']*n, expected=['none']*n, window=np.zeros(n,bool))
    if episode:
        for key, value in episode.items():
            values[key] = value
    table = pa.Table.from_pydict(values, schema=SCHEMA)
    # Real observed subset contains only strict observed bins; retain explicit
    # unknown clock ticks in episode context rather than joining missing time.
    return table.filter(pa.array(obs)) if real and subset=='observed' else table


def build_base():
    protocol = freeze_protocol()
    derived_path = derived()
    folder = ROOT / 'candidate'
    folder.mkdir(exist_ok=True)
    if (folder / 'base-manifest.json').exists():
        return read_json(folder / 'base-manifest.json')
    trial('base', 'release-v1', 'STARTED', 'Observed strict bins and derived full timelines; frozen person groups')
    paths = {s: folder / f'{s}-base.parquet' for s in ['train','val','test']}
    writers = {s:pq.ParquetWriter(p,SCHEMA,compression='zstd',use_dictionary=True) for s,p in paths.items()}
    counts = {s:0 for s in paths}
    try:
        for row in protocol['people']:
            p = load_person(row)
            cc, oo = p['counts'].reshape(-1,96),p['observed'].reshape(-1,96)
            for day in np.flatnonzero(oo.any(1)):
                table = day_table(p,int(day),cc[day],oo[day],row['split'],'observed')
                writers[row['split']].write_table(table)
                counts[row['split']] += len(table)
        with np.load(derived_path,allow_pickle=False) as z:
            dc, do, dd = z['counts'], z['observed'], z['start_dow']
            for split, people in protocol['derived_splits'].items():
                for i in people:
                    p = dict(pid=f'derived-{i:04d}',source='derived',start_dow=int(dd[i]))
                    tables = [day_table(p,day,dc[i,day],do[i,day],split,'derived') for day in range(120)]
                    table = pa.concat_tables(tables)
                    writers[split].write_table(table)
                    counts[split] += len(table)
    finally:
        for writer in writers.values(): writer.close()
    result = dict(rows=counts,sha256={p.name:sha256(p) for p in paths.values()},real_check_included=False)
    write_json(folder/'base-manifest.json',result)
    trial('base','release-v1','COMPLETE',str(result))
    progress('Observed and derived candidate Parquet files built. Final training files remain unfrozen until episodes, training and release checks are verified. '+str(counts))
    return result


if __name__ == '__main__':
    print(build_base())
