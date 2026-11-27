import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "backend")]
import nameplate
import quality

WEB = ROOT / "data/web"
FIELDS = ["make", "model", "serial", "mfg_date"]


def fields_for(source, file, res, q):
    stem = Path(file).stem
    ocr_path = WEB / "ocr_mlkit" / (stem + ".json")
    ml = nameplate.extract(json.load(open(ocr_path))[res], q) if ocr_path.exists() else None
    if source == "mlkit":
        return ml
    model = source.split(":", 1)[1]
    gp = WEB / "gemini" / model / (stem + ".json")
    if not gp.exists():
        return None
    gm = nameplate.from_gemini(json.load(open(gp)), q, model)
    if source.startswith("gemini:"):
        return gm
    return nameplate.combine(ml, gm) if ml else None


def truth(row, f):
    v = row[f].strip()
    return None if v in ("", "unreadable") else v


def same(f, pred, want):
    p, w = nameplate.norm(pred), nameplate.norm(want)
    if f == "make":
        return len(p) >= 3 and (p in w or w in p)
    if f == "mfg_date":
        return p[:4] == w[:4]
    return p == w


def outcome(f, pred, want):
    if pred is None:
        return "null_ok" if want is None else "missed"
    return "correct" if want is not None and same(f, pred, want) else "wrong"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    res = args[0] if args else "640"
    source = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--source=")), "mlkit")
    labels = {r["file"]: r for r in csv.DictReader(open(WEB / "labels.csv")) if r["type"] == "nameplate"}
    images = [(f, f, "orig", r["split"]) for f, r in labels.items()]
    images += [(r["file"], r["parent"], r["expect"], r["split"]) for r in csv.DictReader(open(WEB / "synth_labels.csv")) if r["type"] == "nameplate"]
    t = json.loads(quality.THRESHOLDS.read_text())
    rows = []
    for file, parent, variant, split in images:
        img = (WEB / "img" / file) if variant == "orig" else (WEB / "synth" / file)
        reasons = quality.verdict(quality.score(img, t), "nameplate", t)
        q = {"flag": "low" if reasons else "ok"}
        out = fields_for(source, file, res, q)
        if out is None:
            continue
        for f in FIELDS:
            want = truth(labels[parent], f)
            rows.append({"file": file, "variant": variant, "split": split, "field": f, "pred": out[f]["value"], "want": want,
                         "conf": out[f]["confidence"], "outcome": outcome(f, out[f]["value"], want), "q": q["flag"]})

    def summary(sel, name):
        c = [r["conf"] for r in sel if r["outcome"] == "correct"]
        w = [r["conf"] for r in sel if r["outcome"] == "wrong"]
        n = {k: sum(r["outcome"] == k for r in sel) for k in ("correct", "wrong", "missed", "null_ok")}
        mc = sum(c) / len(c) if c else float("nan")
        mw = sum(w) / len(w) if w else float("nan")
        print(f"  {name:22} correct {n['correct']:3}  wrong {n['wrong']:3}  missed {n['missed']:3}  null_ok {n['null_ok']:3}   conf correct {mc:.2f} / wrong {mw:.2f}")

    print(f"source {source}, OCR at {res}, files scored: {len({r['file'] for r in rows})}")
    for split in ("tune", "eval"):
        base = [r for r in rows if r["split"] == split and r["variant"] == "orig"]
        if not base:
            continue
        print(f"\n{split} split, original photos")
        for f in FIELDS:
            summary([r for r in base if r["field"] == f], f)
        summary(base, "all fields")
        syn = [v for v in ("dark", "blurry", "glare", "cut_off") if any(r["split"] == split and r["variant"] == v for r in rows)]
        if syn:
            print(f"{split} split, synthetic variants (all fields)")
        for v in syn:
            summary([r for r in rows if r["split"] == split and r["variant"] == v], v)
    if "--detail" in sys.argv:
        for r in rows:
            if r["variant"] == "orig" and r["outcome"] in ("wrong", "missed"):
                print(f"{r['file']:16} {r['field']:9} {r['outcome']:7} pred={r['pred']!r:28} want={r['want']!r:24} conf={r['conf']}")


if __name__ == "__main__":
    main()
