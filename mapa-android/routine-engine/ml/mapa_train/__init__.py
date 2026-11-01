"""Train and test the on-device rhythm CNN on MaPa-Syn data (spec v0.1.2, experiments A and B).

    python -m mapa_train.train --exp A --data D:\\GitHub\\MaPa\\data\\MaPaData --out runs
    python -m mapa_train.test  --runs runs --data D:\\GitHub\\MaPa\\data\\MaPaData
    python -m mapa_train.parity                     # Python features and CNN match the phone's golden file

Hard rules, checked in code: `real_check` people are never read for training or tuning, and no
`test` person is read for training. A run that would break either rule stops with an error.
"""
