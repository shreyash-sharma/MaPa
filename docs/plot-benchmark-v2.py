"""Recreate MaPa's v2 benchmark figures from the checked-in aggregate tables."""
import csv
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets"
ASSETS.mkdir(exist_ok=True)

COLORS = {
    "MaPa policy": "#087F8C",
    "Earlier hybrid policy": "#7E8A97",
    "4-hour timer": "#D9853B",
    "8-hour timer": "#A7B0B8",
    "12-hour timer": "#567C8D",
    "Statistics-only": "#8974A5",
    "24-hour timer": "#C8CDD2",
}

with (ROOT / "docs" / "benchmark-v2.csv").open(newline="", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

plt.rcParams.update({
    "font.family": "DejaVu Sans",
    "font.size": 9,
    "axes.titlesize": 12,
    "axes.labelsize": 9,
    "figure.facecolor": "#FBFAF7",
    "axes.facecolor": "#FBFAF7",
    "savefig.facecolor": "#FBFAF7",
})

fig, (ax_fa, ax_catch) = plt.subplots(
    1, 2, figsize=(13, 6.8), gridspec_kw={"width_ratios": [1.0, 1.25]}
)
fig.suptitle(
    "MaPa Rhythm v2 | one-shot derived-cohort benchmark",
    x=0.06, y=0.98, ha="left", fontsize=17, fontweight="bold", color="#172B35",
)
fig.text(
    0.06, 0.925,
    "40 generated parent timelines × 120 days · 685.7 observed person-weeks · programmatically constructed behavior scenarios",
    ha="left", fontsize=9.5, color="#53636C",
)

# False alerts, with person-bootstrap intervals from the saved metrics artifact.
y = np.arange(len(rows))
values = np.array([float(r["false_alerts_per_observed_person_week"]) for r in rows])
low = np.array([float(r["ci_low"]) for r in rows])
high = np.array([float(r["ci_high"]) for r in rows])
colors = [COLORS[r["method"]] for r in rows]
ax_fa.barh(y, values, color=colors, height=0.67, edgecolor="none")
ax_fa.errorbar(values, y, xerr=[values - low, high - values], fmt="none",
               ecolor="#243842", capsize=3, linewidth=1.2)
for i, value in enumerate(values):
    ax_fa.text(max(value, 0) + 0.11, i, f"{value:.2f}", va="center", fontsize=8.5, color="#172B35")
ax_fa.set_yticks(y, [r["method"] for r in rows])
ax_fa.invert_yaxis()
ax_fa.set_xlim(0, 7.7)
ax_fa.set_xlabel("False alerts per observed person-week")
ax_fa.set_title("Alert burden", loc="left", pad=12, fontweight="bold", color="#172B35")
ax_fa.grid(axis="x", color="#DDE2E2", linewidth=0.8)
ax_fa.set_axisbelow(True)
ax_fa.spines[["top", "right", "left"]].set_visible(False)
ax_fa.spines["bottom"].set_color("#B7C0C3")
ax_fa.tick_params(axis="y", length=0, pad=8)

# Behavior catches: only four comparison methods to keep the grouped chart legible.
selected = ["MaPa policy", "8-hour timer", "12-hour timer", "Statistics-only"]
lookup = {r["method"]: r for r in rows}
groups = ["Mild", "Clear", "Severe"]
keys = [("mild_caught", "mild_total"), ("clear_caught", "clear_total"), ("severe_caught", "severe_total")]
x = np.arange(len(groups))
width = 0.18
for idx, method in enumerate(selected):
    rate = [int(lookup[method][a]) / int(lookup[method][b]) for a, b in keys]
    pos = x + (idx - (len(selected) - 1) / 2) * width
    bars = ax_catch.bar(pos, rate, width, label=method, color=COLORS[method], edgecolor="none")
    for bar, (a, b) in zip(bars, keys):
        caught, total = int(lookup[method][a]), int(lookup[method][b])
        ax_catch.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.025,
                      f"{100*caught/total:.0f}%", ha="center", va="bottom", fontsize=7.1,
                      color="#34464F")
ax_catch.set_xticks(x, groups)
ax_catch.set_ylim(0, 1.27)
ax_catch.set_ylabel("Constructed behavior scenarios caught")
ax_catch.set_yticks(np.linspace(0, 1, 6), ["0%", "20%", "40%", "60%", "80%", "100%"])
ax_catch.set_title("Catch rate by severity", loc="left", pad=12, fontweight="bold", color="#172B35")
ax_catch.grid(axis="y", color="#DDE2E2", linewidth=0.8)
ax_catch.set_axisbelow(True)
ax_catch.spines[["top", "right", "left"]].set_visible(False)
ax_catch.spines["bottom"].set_color("#B7C0C3")
ax_catch.legend(frameon=False, ncol=2, loc="upper center", bbox_to_anchor=(0.5, -0.11), fontsize=8)

fig.text(
    0.06, 0.045,
    "Bars show controlled derived-data results, not real-world disruptions. The catch chart includes cases with no changed phone-use inputs. "
    "MaPa policy: 30/30, 29/29, 21/21 catches on changed mild/clear/severe windows.",
    ha="left", fontsize=8.2, color="#53636C",
)
fig.tight_layout(rect=(0.045, 0.12, 0.99, 0.9), w_pad=3.0)
fig.savefig(ASSETS / "benchmark-v2.png", dpi=210, bbox_inches="tight")
plt.close(fig)

# Hard-negative breakdown. The all-alert cases are intentionally visible.
with (ROOT / "docs" / "benchmark-v2-hard-negatives.csv").open(newline="", encoding="utf-8") as f:
    negatives = list(csv.DictReader(f))
fig, ax = plt.subplots(figsize=(10.5, 4.6))
fig.suptitle(
    "Expected-none episodes: scenario-level alerts",
    x=0.08, y=0.98, ha="left", fontsize=15, fontweight="bold", color="#172B35",
)
names = [r["scenario"] for r in negatives]
rates = [int(r["alerted"]) / int(r["episodes"]) for r in negatives]
totals = [int(r["episodes"]) for r in negatives]
alerted = [int(r["alerted"]) for r in negatives]
yy = np.arange(len(names))
negcolors = ["#087F8C" if rate < 0.5 else "#C85B45" for rate in rates]
ax.barh(yy, rates, height=0.6, color=negcolors, edgecolor="none")
for i, (rate, a, n) in enumerate(zip(rates, alerted, totals)):
    ax.text(max(rate, 0) + 0.018, i, f"{a}/{n}  ({rate:.0%})", va="center", fontsize=9, color="#263B44")
ax.set_yticks(yy, names)
ax.invert_yaxis()
ax.set_xlim(0, 1.23)
ax.set_xticks(np.linspace(0, 1, 6), ["0%", "20%", "40%", "60%", "80%", "100%"])
ax.set_xlabel("Episodes with at least one alert")
ax.grid(axis="x", color="#DDE2E2", linewidth=0.8)
ax.set_axisbelow(True)
ax.spines[["top", "right", "left"]].set_visible(False)
ax.spines["bottom"].set_color("#B7C0C3")
ax.tick_params(axis="y", length=0, pad=8)
fig.text(
    0.08, 0.035,
    "All episodes are from the same 40-parent generated cohort. Daytime inactivity and phone-left-home are unresolved cases.",
    ha="left", fontsize=8.5, color="#53636C",
)
fig.tight_layout(rect=(0.06, 0.1, 0.98, 0.9))
fig.savefig(ASSETS / "benchmark-v2-hard-negatives.png", dpi=210, bbox_inches="tight")
plt.close(fig)
