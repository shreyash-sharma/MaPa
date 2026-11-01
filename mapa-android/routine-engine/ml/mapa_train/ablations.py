"""The approved cumulative ablation sequence; these are parts of one model."""
ROWS = [
    {"row": 1, "name": "Plain CNN", "architecture": "plain-cnn"},
    {"row": 2, "name": "+ GRU memory", "personal": False, "slow": False, "attention": False},
    {"row": 3, "name": "+ personal context and heads", "personal": True, "slow": False, "attention": False},
    {"row": 4, "name": "+ surprise score", "inherits": 3, "surprise": True},
    {"row": 5, "name": "+ limit per parent", "inherits": 4, "personal_limit": True},
    {"row": 6, "name": "+ day-swap, source weights, day-quality gate", "inherits": 5, "data_tricks": True},
    {"row": 7, "name": "+ slow-lane LSTM", "inherits": 6, "slow": True},
    {"row": 8, "name": "+ attention", "inherits": 7, "attention": True, "requires_export": True},
    {"row": 9, "name": "+ day-one start (attention dropped)", "inherits": 7, "cold_start": True},
    {"row": 10, "name": "+ alert levels, morning check, floor", "inherits": 9, "full_decision": True},
    {"row": 11, "name": "+ session starts and movement", "dataset": "final-v3", "status": "pending final-v3"},
]


def configuration(row):
    if not 1 <= row <= len(ROWS):
        raise ValueError("Unknown ablation row")
    entry = dict(ROWS[row - 1])
    if entry.get("row") != row:
        raise ValueError("Unknown ablation row")
    parent = configuration(entry["inherits"]) if "inherits" in entry else {}
    parent.update(entry)
    return parent


def run_rows(version="causal-v3", tag="v3"):
    """Run only approved cumulative rows, each with the existing person CV driver."""
    from .zoo import train_cv
    reports = {}
    for row in range(1, 11):
        family = "plain-cnn" if row == 1 else "hybrid"
        reports[row] = train_cv([family], version, 15, tag, ablation_row=row)
    return reports


def decision_grid(row):
    """Later decision components cannot enter earlier cumulative rows."""
    import itertools
    configuration(row)
    if row > 10:
        raise ValueError("final-v3 row is not available for final-v2")
    for floor in [.05, .1, .2, .3, .4, .5, .6, .7]:
        base = {"floor": floor, "score_kind": "combined" if row >= 4 else "behaviour"}
        if row < 5:
            yield dict(base, kind="global")
        else:
            for q in [.9, .97, .995]:
                cfg = dict(base, kind="personal", q=q, morning=False, silence_floor=None)
                if row < 9:
                    yield cfg
                else:
                    for weight, margin in itertools.product([0., .25, .5, .75, 1.], [0., .1, .2]):
                        yield dict(cfg, cold_start=True, model_weight=weight,
                                   uncertainty_margin=margin, silence_floor=32, morning=row >= 10)
