import argparse
import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "backend")]
import extract
import gemini
import nameplate
import quality

WEB = ROOT / "data/web"
OUT = ROOT / "eval"
MODEL = "gemini-3.1-flash-lite"
NP_FIELDS = ["make", "model", "serial", "mfg_date"]
NOTE_FIELDS = ["location", "issue", "material", "severity"]
STOP = {"the", "of", "a", "an", "on", "in", "by", "at", "to", "from", "out", "about", "near"}


class Missing(Exception):
    pass


def cached(kind, stem, run, counter):
    path = OUT / "cache" / MODEL / kind / f"{stem}.json"

    def vlm(prompt, schema, image, purpose=""):
        if path.exists():
            return json.loads(path.read_text()), MODEL
        counter.append(f"{kind}/{stem}")
        if not run:
            raise Missing(path)
        resp, model = gemini.generate(prompt, schema, image, purpose=purpose, models=[MODEL])
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(resp, indent=1))
        return resp, model
    return vlm


def tokens(s):
    return {w for w in re.findall(r"[a-z]+", (s or "").lower()) if w not in STOP}


def levenshtein(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def same(field, pred, want):
    if field == "make":
        p, w = nameplate.norm(pred), nameplate.norm(want)
        return len(p) >= 3 and (p in w or w in p)
    if field in ("model", "serial"):
        return nameplate.norm(pred) == nameplate.norm(want)
    if field == "mfg_date":
        return nameplate.norm(pred)[:4] == nameplate.norm(want)[:4]
    if field == "size_cm":
        a = pred["width"] * pred["height"]
        b = want["width"] * want["height"]
        return abs(a - b) <= 0.3 * b
    if field == "location":
        w = tokens(want)
        return len(w & tokens(pred)) >= max(1, round(len(w) * 2 / 3))
    return str(pred) == str(want)


def outcome(field, pred, want):
    if pred is None:
        return "null_ok" if want is None else "missed"
    return "correct" if want is not None and same(field, pred, want) else "wrong"


def frames():
    labels = {r["file"]: r for r in csv.DictReader(open(WEB / "labels.csv"))}
    out = [(f, f, "orig", r) for f, r in labels.items()]
    out += [(r["file"], r["parent"], r["expect"], labels[r["parent"]]) for r in csv.DictReader(open(WEB / "synth_labels.csv"))]
    return out


def run(args):
    t = json.loads(quality.THRESHOLDS.read_text())
    rows, planned, missing = [], [], 0
    for file, parent, variant, lab in frames():
        if args.split and lab["split"] != args.split or args.variants and variant not in args.variants.split(","):
            continue
        img = (WEB / "img" / file) if variant == "orig" else (WEB / "synth" / file)
        kind = lab["type"]
        s = quality.score(img, t)
        reasons = quality.verdict(s, kind, t)
        q = {"flag": "low" if reasons else "ok"}
        base = {"file": file, "parent": parent, "split": lab["split"], "type": kind, "variant": variant, "condition": lab["condition"],
                "lighting": "low" if variant == "dark" or lab["lighting"] == "low" else lab["lighting"], "gate_passed": int(not reasons),
                "gate_reasons": " ".join(reasons)}
        vlm = cached(kind, Path(file).stem, args.run, planned)
        try:
            if kind == "nameplate":
                ocr = json.load(open(WEB / "ocr_mlkit" / f"{Path(file).stem}.json"))["640"]
                fields, _ = extract.nameplate_fields(img, ocr, q, vlm=vlm)
                ml = nameplate.extract(ocr, q)
                for f in NP_FIELDS:
                    want = None if lab[f].strip() in ("", "unreadable") else lab[f].strip()
                    for src, out in (("combined", fields), ("mlkit", ml)):
                        pred = out[f]["value"]
                        r = dict(base, source=src, field=f, truth=want, predicted=pred, confidence=out[f]["confidence"],
                                 outcome=outcome(f, pred, want), raw_text=out[f].get("raw_text"), how=out[f].get("source", ""))
                        if f == "serial" and pred and want:
                            r["cer"] = round(levenshtein(nameplate.norm(pred), nameplate.norm(want)) / len(nameplate.norm(want)), 3)
                        rows.append(r)
            else:
                fields, raw = extract.damage_fields(img, q, lab["room"] or None, lab["surface"] or None, vlm=vlm)
                want = lab["damage_type"]
                d = fields["damage_type"]
                rows.append(dict(base, source="combined", field="damage_type", truth=want, predicted=d["value"], confidence=d["confidence"],
                                 outcome=outcome("damage_type", d["value"], want), raw_text=d.get("raw_text"), how=d["source"]))
                if lab["true_width_cm"]:
                    want = {"width": float(lab["true_width_cm"]), "height": float(lab["true_height_cm"])}
                    sz = fields["size_cm"]
                    r = dict(base, source="combined", field="size_cm", truth=want, predicted=sz["value"], confidence=sz["confidence"],
                             outcome=outcome("size_cm", sz["value"], want), raw_text=sz.get("raw_text"), how=sz["source"])
                    if sz["value"]:
                        r["size_err"] = round(abs(sz["value"]["width"] * sz["value"]["height"] / (want["width"] * want["height"]) - 1), 3)
                    rows.append(r)
        except Missing:
            missing += 1
        except gemini.QuotaGuard:
            raise
        except Exception as e:
            print(f"skipped {file}: {e}")
            missing += 1
    for n in csv.DictReader(open(ROOT / "data/notes.csv")):
        vlm = cached("note", n["id"], args.run, planned)
        try:
            fields, _ = extract.note_fields(n["text"], vlm=vlm)
        except (Missing, RuntimeError) as e:
            if not isinstance(e, Missing):
                print(f"skipped note {n['id']}: {e}")
            missing += 1
            continue
        for f in NOTE_FIELDS:
            want = n[f] or None
            pred = fields[f]["value"]
            rows.append({"file": n["id"], "parent": n["id"], "split": "eval", "type": "note", "variant": n["source"], "condition": n["source"],
                         "lighting": "", "gate_passed": "", "gate_reasons": "", "source": "combined", "field": f, "truth": want, "predicted": pred,
                         "confidence": fields[f]["confidence"], "outcome": outcome(f, pred, want), "raw_text": n["text"], "how": fields[f]["source"]})
    return rows, planned, missing


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", action="store_true", help="call the API for frames not in the cache")
    ap.add_argument("--split", default="", help="only frames of this split (notes are always eval)")
    ap.add_argument("--variants", default="", help="comma list, e.g. orig,dark")
    args = ap.parse_args()
    rows, planned, missing = run(args)
    if not args.run:
        print(f"uncached calls needed: {len(planned)} (kinds: " + ", ".join(f"{k} {sum(p.startswith(k) for p in planned)}" for k in ("nameplate", "damage", "note")) + ")")
        print(f"{MODEL} used today {gemini.used_today(MODEL)}/{gemini.cap(MODEL)}")
    OUT.mkdir(exist_ok=True)
    cols = ["file", "parent", "split", "type", "variant", "condition", "lighting", "gate_passed", "gate_reasons", "source", "field", "truth",
            "predicted", "confidence", "outcome", "cer", "size_err", "how", "raw_text"]
    with open(OUT / "results.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow({k: (json.dumps(v) if isinstance(v, dict) else v) for k, v in r.items()})
    print(f"rows {len(rows)}, frames skipped for missing cache {missing} -> eval/results.csv")


if __name__ == "__main__":
    main()
