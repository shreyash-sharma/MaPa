# MaPa-Rhythm release builder

The user release objective supersedes historical MaPa-Syn review gates. This package stages data and models offline; it contains no upload or messaging operation. Outputs live under `MaPaData/mapa-rhythm-release/`.

Commands, from the `ml` folder:

```
python -m mapa_release.build
python -m mapa_release.inject
python -m mapa_release.checks
python -m mapa_release.learning
python -m mapa_release.evaluate
```

Training opens train/validation candidates only. Test evaluation requires an unchanged checkpoint with a passing validation selection, and results are cached. Person splits, seeds and scenario definitions are frozen in protocol.json before training. Data changes may target data properties only, never detector results. Failed comparisons and model trials remain visible in logs/trials.csv.

ExecuTorch export uses `mapa_rhythm.export_executorch.export`. The isolated Linux environment currently requires ExecuTorch 0.7.0 / its matching CPU PyTorch, setuptools 80.9.0 and the flatc compiler. Windows GPU training stays in its existing environment.

`package.make_final(code_commit)` concatenates the immutable candidate payloads, writes the manifest and standalone loader, reloads all three splits, then makes payloads read-only. Never call it before checking the chosen training data and model. Never modify an existing final/; later data goes to final-v2/.

The release head has three trained outputs in the existing 16-wide CNN streaming interface. It is not a drop-in replacement for the older all-horizon hazard head. Mandatory observation, night and life-tag policies run outside the neural model.
