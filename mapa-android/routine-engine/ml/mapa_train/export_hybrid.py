"""Export the streaming hybrid and cached daily encoder with XNNPACK."""
from pathlib import Path
import shutil
import hashlib
import json
import torch
from .hybrid_streaming import HybridStep, DailyEncoder
from .hybrid_weights import export_text
from .models import Hybrid


def export_program(module, examples, destination):
    from executorch.exir import to_edge_transform_and_lower
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    torch.backends.mha.set_fastpath_enabled(False)
    exported = torch.export.export(module.eval(), examples, strict=False)
    lowered = to_edge_transform_and_lower(exported, partitioner=[XnnpackPartitioner()])
    Path(destination).write_bytes(lowered.to_executorch().buffer)


def export_hybrid(model, folder):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    model = model.cpu().eval()
    step = HybridStep(model).eval()
    hidden = model.gru.hidden_size
    examples = (torch.zeros(1, 21), torch.zeros(1, step.conv_state_size),
                torch.zeros(1, 1, hidden), torch.zeros(1, 96, hidden),
                torch.zeros(1, dtype=torch.int64), torch.zeros(1, hidden))
    export_program(step, examples, folder / 'rhythm-step.pte')
    if model.slow is not None:
        export_program(DailyEncoder(model), (torch.zeros(1, 30, 9), torch.zeros(1, dtype=torch.int64)),
                       folder / 'rhythm-daily.pte')
    return examples


def verify_runtime(model, folder, steps=192, tolerance=1e-3):
    """Replay deterministic inputs through independently evolving runtime states."""
    from executorch.runtime import Runtime
    folder = Path(folder)
    net = model.cpu().eval()
    step = HybridStep(net).eval()
    hidden = net.gru.hidden_size
    method = Runtime.get().load_program(str(folder / 'rhythm-step.pte')).load_method('forward')
    initial = [torch.zeros(1, step.conv_state_size), torch.zeros(1, 1, hidden),
               torch.zeros(1, 96, hidden), torch.zeros(1, dtype=torch.int64)]
    py_state = [v.clone() for v in initial]
    et_state = [v.clone() for v in initial]
    generator = torch.Generator().manual_seed(729)
    slow = torch.zeros(1, hidden)
    daily_error = 0.
    with torch.no_grad():
        if net.slow is not None:
            encoder = DailyEncoder(net).eval()
            daily = Runtime.get().load_program(str(folder / 'rhythm-daily.pte')).load_method('forward')
            summaries = torch.rand(1, 30, 9, generator=generator)
            for n in [0, 1, 3, 14, 30]:
                counts = torch.tensor([n])
                expected = encoder(summaries, counts)
                actual = daily.execute([summaries, counts])[0]
                if not torch.isfinite(actual).all():
                    raise RuntimeError('Nonfinite daily encoder runtime output')
                daily_error = max(daily_error, float((expected-actual).abs().max()))
            slow = encoder(summaries, torch.tensor([3]))
        max_error = 0.
        for _ in range(steps):
            x = torch.rand(1, 21, generator=generator)
            expected = step(x, *py_state, slow)
            actual = method.execute([x, *et_state, slow])
            if len(actual) != len(expected):
                raise RuntimeError('Exported streaming output arity mismatch')
            for a, b in zip(expected, actual):
                error = float((a.to(torch.float64)-b.to(torch.float64)).abs().max())
                if not torch.isfinite(torch.tensor(error)):
                    raise RuntimeError('Nonfinite exported runtime output')
                max_error = max(max_error, error)
            py_state = list(expected[1:])
            et_state = list(actual[1:])
    files = {p.name: {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
             for p in folder.glob('*.pte')}
    result = {'steps': steps, 'stream_max_error': max_error, 'daily_max_error': daily_error,
              'tolerance': tolerance, 'pass': max(max_error, daily_error) <= tolerance, 'files': files}
    (folder / 'runtime-parity.json').write_text(json.dumps(result, indent=2))
    if not result['pass']:
        raise RuntimeError(f'ExecuTorch parity failed: {result}')
    return result


def load_locked_model(checkpoint: Path):
    """Load only a final-fit checkpoint whose selection lock verifies its hash."""
    checkpoint = Path(checkpoint)
    lock_path = checkpoint.parent / 'selection.json'
    if not checkpoint.is_file() or not lock_path.is_file():
        raise FileNotFoundError('A final-fit model.pt and its selection.json lock are required')
    lock = json.loads(lock_path.read_text(encoding='utf-8'))
    actual = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    if actual != lock.get('checkpoint_sha256'):
        raise RuntimeError('Final checkpoint SHA-256 differs from the locked selection')
    blend = float(lock.get('next_use_blend', 0.0))
    if 'forecast_calibration_sha256' in lock:
        calibration_path = checkpoint.parent / 'forecast-calibration.json'
        calibration_hash = hashlib.sha256(calibration_path.read_bytes()).hexdigest()
        if calibration_hash != lock['forecast_calibration_sha256']:
            raise RuntimeError('Forecast calibration SHA-256 differs from the locked selection')
        calibration = json.loads(calibration_path.read_text(encoding='utf-8'))
        if float(calibration.get('model_weight')) != blend:
            raise RuntimeError('Forecast calibration weight differs from the selection lock')
    payload = torch.load(checkpoint, map_location='cpu', weights_only=False)
    if not isinstance(payload, dict) or 'state_dict' not in payload or 'ablation_config' not in payload:
        raise RuntimeError('Checkpoint does not contain the expected trained hybrid state')
    cfg = payload['ablation_config']
    model = Hybrid(attention=bool(cfg.get('attention', False)),
                   personal=bool(cfg.get('personal', True)),
                   slow=bool(cfg.get('slow', True)))
    model.load_state_dict(payload['state_dict'], strict=True)
    model.next_use_blend = blend
    model.eval()
    return model, lock


def write_kotlin_goldens(model, folder, steps=192):
    """Write deterministic trained-weight daily and streaming vectors for Kotlin parity."""
    folder = Path(folder)
    torch.manual_seed(729)
    generator = torch.Generator().manual_seed(729)
    hidden = model.gru.hidden_size
    daily_rows = torch.rand(1, 30, 9, generator=generator)
    daily_encoder = DailyEncoder(model).eval()
    counts = [0, 1, 3, 14, 30]
    daily_lines = ['# count + 30x9 rows + daily encoder output']
    with torch.no_grad():
        for count in counts:
            output = daily_encoder(daily_rows, torch.tensor([count]))[0]
            values = [count, *daily_rows.reshape(-1).tolist(), *output.tolist()]
            daily_lines.append(' '.join(f'{float(v):.9g}' for v in values))
        slow = daily_encoder(daily_rows, torch.tensor([3]))
        step = HybridStep(model).eval()
        conv_state = torch.zeros(1, step.conv_state_size)
        gru_state = torch.zeros(1, 1, hidden)
        memory = torch.zeros(1, 96, hidden)
        memory_count = torch.zeros(1, dtype=torch.int64)
        stream_lines = ['# 21 inputs + 16 daily state + 19 output logits; sequential state']
        for _ in range(steps):
            x = torch.rand(1, 21, generator=generator)
            result = step(x, conv_state, gru_state, memory, memory_count, slow)
            logits, conv_state, gru_state, memory, memory_count = result
            values = [*x[0].tolist(), *slow[0].tolist(), *logits[0].tolist()]
            stream_lines.append(' '.join(f'{float(v):.9g}' for v in values))
    (folder / 'golden_hybrid_daily.txt').write_text('\n'.join(daily_lines) + '\n', encoding='utf-8')
    (folder / 'golden_hybrid_reference.txt').write_text('\n'.join(stream_lines) + '\n', encoding='utf-8')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--probe', action='store_true',
                        help='Use untrained deterministic weights to check export compatibility only')
    mode.add_argument('--checkpoint', type=Path,
                      help='Export the SHA-256-locked trained model from a final-fit folder')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--no-attention', action='store_true')
    args = parser.parse_args()
    torch.set_num_threads(1)
    args.out.mkdir(parents=True, exist_ok=True)
    lock = None
    if args.probe:
        torch.manual_seed(729)
        model = Hybrid(attention=not args.no_attention).eval()
        (args.out / 'PROBE-ONLY.txt').write_text(
            'Untrained deterministic export compatibility probe. Not a trained or selected model.\n')
    else:
        model, lock = load_locked_model(args.checkpoint)
    try:
        export_hybrid(model, args.out)
        result = verify_runtime(model, args.out)
        if lock is not None:
            export_text(model, args.out / 'hybrid_reference.weights.txt')
            write_kotlin_goldens(model, args.out)
            shutil.copy2(args.checkpoint.parent / 'cold-start-priors.json', args.out / 'cold-start-priors.json')
            shutil.copy2(args.checkpoint.parent / 'selection.json', args.out / 'selection.json')
            calibration_path = args.checkpoint.parent / 'forecast-calibration.json'
            if calibration_path.exists():
                shutil.copy2(calibration_path, args.out / 'forecast-calibration.json')
            result.update({'trained_checkpoint_sha256': lock['checkpoint_sha256'],
                           'decision_config': lock['decision_config'],
                           'next_use_blend': float(getattr(model, 'next_use_blend', 0.0)),
                           'kotlin_golden_rows': {'streaming': 192, 'daily': 5}})
            (args.out / 'trained-export.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
            stale_failure = args.out / 'export-failure.json'
            if stale_failure.exists():
                stale_failure.unlink()
        print(json.dumps(result, indent=2))
    except Exception as error:
        (args.out / 'export-failure.json').write_text(json.dumps({'probe_only': bool(args.probe),
            'error': f'{type(error).__name__}: {error}'}, indent=2))
        raise
