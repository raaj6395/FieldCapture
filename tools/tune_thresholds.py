import csv
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import quality

WEB = quality.ROOT / "data/web"
MAX_FALSE_REJECT = 0.10


def load():
    t = json.loads(quality.THRESHOLDS.read_text())
    items = []
    for r in csv.DictReader(open(WEB / "labels.csv")):
        items.append({"file": r["file"], "path": WEB / "img" / r["file"], "split": r["split"], "type": r["type"], "expect": "ok", "condition": r["condition"]})
    for r in csv.DictReader(open(quality.ROOT / "data/real_quality.csv")):
        items.append({"file": r["file"], "path": quality.ROOT / "data/originals/incoming" / r["file"], "split": "real", "type": r["type"], "expect": r["expect"], "condition": "real phone"})
    for r in csv.DictReader(open(WEB / "synth_labels.csv")):
        items.append({"file": r["file"], "path": WEB / "synth" / r["file"], "split": r["split"], "type": r["type"], "expect": r["expect"], "condition": "synthetic"})
    for it in items:
        it["s"] = quality.score(it["path"], t)
        ocr = (quality.ROOT / "data/own_ocr" if it["split"] == "real" else WEB / "ocr_mlkit") / (Path(it["file"]).stem + ".json")
        it["ocr"] = json.loads(ocr.read_text())["640"] if ocr.exists() else None
    return t, items


def pick(neg, pos, below, geo=False, cap=MAX_FALSE_REJECT):
    vals = sorted(set(neg + pos))
    cands = [vals[0] - 1e-6] + [(a + b) / 2 for a, b in zip(vals, vals[1:])] + [vals[-1] + 1e-6]
    scored = []
    for c in cands:
        fr = sum((v < c) if below else (v > c) for v in neg) / max(len(neg), 1)
        tp = sum((v < c) if below else (v > c) for v in pos) / max(len(pos), 1)
        if fr <= cap:
            scored.append((tp, -fr, c))
    tp, nfr, _ = max(scored)
    tied = [c for a, b, c in scored if (a, b) == (tp, nfr)]
    lo, hi = min(tied), max(tied)
    a, b = max([v for v in vals if v < lo], default=lo), min([v for v in vals if v > hi], default=hi)
    c = (a * b) ** 0.5 if geo and a > 0 else (a + b) / 2
    return c, tp, -nfr


def tune(t, items):
    tr = [i for i in items if i["split"] == "tune"]
    ok = [i for i in tr if i["expect"] == "ok"]
    log = []

    def fit(path, metric, kind, expect, below, geo=False, cap=MAX_FALSE_REJECT):
        neg = [i["s"][metric] for i in ok if kind in (None, i["type"])]
        pos = [i["s"][metric] for i in tr if i["expect"] == expect and kind in (None, i["type"])]
        c, tp, fr = pick(neg, pos, below, geo, cap)
        node = t
        for k in path[:-1]:
            node = node[k]
        node[path[-1]] = round(c, 4)
        log.append(f"{'.'.join(path):30} = {c:10.4f}   catches {tp:5.0%} of {expect} (n={len(pos)}), rejects {fr:4.0%} of good (n={len(neg)})")

    fit(["dark_mean"], "brightness", None, "dark", True)
    t["dark_frac"] = 1.0
    fit(["nameplate", "blur"], "blur", "nameplate", "blurry", True, geo=True)
    fit(["damage", "blur"], "blur", "damage", "blurry", True, geo=True)
    fit(["nameplate", "glare_frac"], "glare_frac", "nameplate", "glare", False)
    fit(["nameplate", "border_edge_frac"], "border_edge_frac", "nameplate", "cut_off", False, cap=0.0)
    t["nameplate"]["border_edge_frac"] = None
    log.append("nameplate.border_edge_frac     = disabled: edge-touching text also means a well-filled frame; good plates got cut_off retakes")
    good = [i for i in ok if i["type"] == "nameplate" and i["ocr"]]
    cut = [i for i in tr if i["type"] == "nameplate" and i["expect"] == "cut_off" and i["ocr"]]
    rate = lambda g, m: sum(bool(quality.text_at_edge(i["ocr"], m)) for i in g) / len(g)
    cands = [(rate(cut, m), -rate(good, m), m) for m in (0.005 * k for k in range(1, 11)) if rate(good, m) <= MAX_FALSE_REJECT]
    tp, nfr, _ = max(cands)
    tied = [m for a, b, m in cands if (a, b) == (tp, nfr)]
    t["nameplate"]["text_edge_margin"] = round(tied[len(tied) // 2], 3)
    log.append(f"nameplate.text_edge_margin     = {t['nameplate']['text_edge_margin']:10.4f}   catches {tp:5.0%} of cut_off (n={len(cut)}), rejects {-nfr:4.0%} of good (n={len(good)}); OCR line touching the edge, ties {min(tied):.3f}-{max(tied):.3f}")
    return log


def report(t, items, split):
    rows = [i for i in items if i["split"] == split]
    out = [f"\n{split} split: end-to-end gate verdicts"]
    for typ in ("nameplate", "damage"):
        for exp in ("ok", "dark", "blurry", "glare", "cut_off"):
            g = [i for i in rows if i["type"] == typ and i["expect"] == exp]
            if not g:
                continue
            v = [quality.verdict(i["s"], typ, t, i["ocr"]) for i in g]
            if exp == "ok":
                bad = [f"{i['file']}({','.join(x)})" for i, x in zip(g, v) if x]
                out.append(f"  {typ:9} good      passed {len(g) - len(bad):2}/{len(g):2}  rejected: {' '.join(bad) or '-'}")
            else:
                hit = sum(bool(x) for x in v)
                right = sum(exp == (x[0] if x else None) or exp in x for x in v)
                out.append(f"  {typ:9} {exp:9} flagged {hit:2}/{len(g):2}  with the right reason {right:2}/{len(g):2}")
    return out


def main():
    t, items = load()
    log = tune(t, items)
    t["provisional"] = True
    t["tuned_on"] = "web + synthetic tune split; retune on real glasses frames"
    print("\n".join(log))
    for split in ("tune", "eval", "real"):
        print("\n".join(report(t, items, split)))
    if "--write" in sys.argv:
        quality.THRESHOLDS.write_text(json.dumps(t, indent=2) + "\n")
        print(f"\nwrote {quality.THRESHOLDS.relative_to(quality.ROOT)}")


if __name__ == "__main__":
    main()
