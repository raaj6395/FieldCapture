import json
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STORE = ROOT / "backend/store/jobs"
LOG = ROOT / "backend/logs/calls.jsonl"
PRICE = {"gemini-3.1-flash-lite": (0.25, 1.50), "gemini-2.5-flash": (0.30, 2.50)}
STAGES = [("cmd", "shot", "voice command -> photo passed quality check (incl. retakes)"),
          ("shot", "saved", "on-device OCR + save to disk"),
          ("saved", "upload_start", "queue wait before upload"),
          ("upload_start", "received", "upload (phone -> server, includes clock skew)"),
          ("received", "stored", "server stores photo + record (the phone's request ends here)"),
          ("received", "extract_done", "received -> fields extracted"),
          ("extract_start", "extract_done", "extraction (model call incl. free-tier spacing and retries)"),
          ("cmd", "extract_done", "END TO END: voice command -> capture has its extracted fields")]


def cost(call):
    pin, pout = PRICE.get(call["model"], (0.0, 0.0))
    out = (call.get("out_tokens") or 0) + (call.get("thought_tokens") or 0)
    return ((call.get("in_tokens") or 0) * pin + out * pout) / 1e6


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p * (len(xs) - 1))))]


def main():
    jobs = sys.argv[1:]
    calls = [json.loads(l) for l in LOG.read_text().splitlines()] if LOG.exists() else []
    caps = []
    for j in jobs:
        rec = json.loads((STORE / j / "job_record.json").read_text())
        for c in rec["captures"]:
            t = c.get("timing") or {}
            win = [x for x in calls if t.get("extract_start", 0) / 1000 - 1 <= x["ts"] <= t.get("extract_done", 0) / 1000 + 1]
            caps.append({"job": j, "type": c["type"], "t": t, "calls": win})
    print(f"jobs {', '.join(jobs)}: {len(caps)} captures\n")
    print("| stage | n | median ms | p90 ms |\n|---|---|---|---|")
    for a, b, name in STAGES:
        xs = [c["t"][b] - c["t"][a] for c in caps if a in c["t"] and b in c["t"]]
        if xs:
            print(f"| {name} | {len(xs)} | {statistics.median(xs):.0f} | {pct(xs, 0.9):.0f} |")
    print("\n| | captures | model calls | input tokens | output+thinking tokens | cost USD (paid-tier price) |\n|---|---|---|---|---|---|")
    for typ in sorted({c["type"] for c in caps}) + ["per job"]:
        sel = caps if typ == "per job" else [c for c in caps if c["type"] == typ]
        cl = [x for c in sel for x in c["calls"]]
        usd = sum(cost(x) for x in cl) / (len(jobs) if typ == "per job" else 1)
        print(f"| {typ} | {len(sel)} | {len(cl)} | {sum(x.get('in_tokens') or 0 for x in cl)} | "
              f"{sum((x.get('out_tokens') or 0) + (x.get('thought_tokens') or 0) for x in cl)} | {usd:.5f} |")
    print("\nPrices: gemini-3.1-flash-lite $0.25 / 1M input, $1.50 / 1M output (thinking billed as output). Billed on the free tier: $0.")


if __name__ == "__main__":
    main()
