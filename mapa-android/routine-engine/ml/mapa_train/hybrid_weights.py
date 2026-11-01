"""Lossless text parameter export for the Kotlin hybrid parity implementation."""
import json
from pathlib import Path
import numpy as np


def export_text(model, destination, next_use_blend=None):
    destination = Path(destination)
    shapes = {}
    with destination.open('w', encoding='utf-8') as stream:
        stream.write('mapa-rhythm 4\n# hybrid parameters; convolution columns use PyTorch [in,tap] order\n')
        blend = float(getattr(model, 'next_use_blend', 0.0) if next_use_blend is None else next_use_blend)
        values = [model.cnn.channels, model.gru.hidden_size, int(model.personal is not None),
                  int(model.slow is not None), int(model.attention is not None), blend]
        stream.write('tensor hybrid.config 1 6\n' + ' '.join(map(str, values)) + '\n')
        for name, parameter in model.named_parameters():
            array = parameter.detach().cpu().numpy()
            shapes[name] = list(array.shape)
            matrix = array.reshape(array.shape[0], -1) if array.ndim > 1 else array.reshape(1, -1)
            stream.write(f'tensor {name} {matrix.shape[0]} {matrix.shape[1]}\n')
            for row in matrix:
                stream.write(' '.join(repr(float(v)) for v in row) + '\n')
    destination.with_suffix('.shapes.json').write_text(json.dumps(shapes, indent=2))


def read_text(destination):
    destination = Path(destination)
    shapes = json.loads(destination.with_suffix('.shapes.json').read_text())
    lines = iter(destination.read_text().splitlines())
    next(lines)
    tensors = {}
    for line in lines:
        if not line or line.startswith('#'):
            continue
        _, name, rows, cols = line.split()
        array = np.array([[float(v) for v in next(lines).split()] for _ in range(int(rows))], dtype=np.float32)
        if array.shape != (int(rows), int(cols)):
            raise ValueError(f'Invalid tensor shape for {name}')
        if name in shapes:
            tensors[name] = array.reshape(shapes[name])
    return tensors
