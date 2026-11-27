import argparse
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))
import gemini
from extract import NAMEPLATE_SCHEMA

WEB = ROOT / "data/web"


def targets(split, variants):
    labels = [r for r in csv.DictReader(open(WEB / "labels.csv")) if r["type"] == "nameplate" and r["split"] == split]
    out = [(r["file"], WEB / "img" / r["file"]) for r in labels] if "orig" in variants else []
    parents = {r["file"] for r in labels}
    out += [(r["file"], WEB / "synth" / r["file"]) for r in csv.DictReader(open(WEB / "synth_labels.csv"))
            if r["parent"] in parents and r["expect"] in variants]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", required=True)
    ap.add_argument("--split", default="tune")
    ap.add_argument("--variants", default="orig")
    ap.add_argument("--limit", type=int, default=0)
    a = ap.parse_args()
    prompt = (ROOT / "backend/prompts/nameplate.txt").read_text()
    files = targets(a.split, a.variants.split(","))
    if a.limit:
        files = files[:a.limit]
    plan = [(m, f, p) for m in a.models.split(",") for f, p in files if not (WEB / "gemini" / m / (Path(f).stem + ".json")).exists()]
    print(f"planned calls: {len(plan)}  ({', '.join(f'{m}: used today {gemini.used_today(m)}/{gemini.cap(m)}' for m in a.models.split(','))})")
    for m, f, p in plan:
        out = WEB / "gemini" / m / (Path(f).stem + ".json")
        try:
            res, used = gemini.generate(prompt, NAMEPLATE_SCHEMA, p, purpose=f"nameplate:{f}", models=[m])
        except gemini.QuotaGuard as e:
            print("STOP:", e)
            return
        except Exception as e:
            print(f"{m} {f} failed: {e}")
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(res, indent=1))
        last = gemini.calls()[-1]
        print(f"{m:24} {f:26} {last['ms']:6} ms  in {last['in_tokens']} out {last['out_tokens']} think {last['thought_tokens']}")


if __name__ == "__main__":
    main()
