import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parent.parent
THRESHOLDS = ROOT / "android/app/src/main/assets/thresholds.json"
WIDTH = 360
METRICS = ["blur", "brightness", "dark_frac", "glare_frac", "edge_density", "border_edge_frac"]


def gray(path):
    rgb = np.asarray(ImageOps.exif_transpose(Image.open(path)).convert("RGB"), dtype=np.float64)
    return 0.299 * rgb[..., 0] + 0.587 * rgb[..., 1] + 0.114 * rgb[..., 2]


def shrink(y, width=WIDTH):
    h, w = y.shape
    height = round(h * width / w)
    ii = np.zeros((h + 1, w + 1))
    ii[1:, 1:] = y.cumsum(0).cumsum(1)
    xs = np.array([x * w // width for x in range(width + 1)])
    ys = np.array([r * h // height for r in range(height + 1)])
    x0, x1, y0, y1 = xs[:-1], np.maximum(xs[1:], xs[:-1] + 1), ys[:-1], np.maximum(ys[1:], ys[:-1] + 1)
    s = ii[y1][:, x1] - ii[y0][:, x1] - ii[y1][:, x0] + ii[y0][:, x0]
    return s / ((y1 - y0)[:, None] * (x1 - x0)[None, :])


def scores(y, edge_e):
    h, w = y.shape
    lap = y[:-2, 1:-1] + y[2:, 1:-1] + y[1:-1, :-2] + y[1:-1, 2:] - 4 * y[1:-1, 1:-1]
    edges = np.abs(lap) > edge_e
    bw, bh = int(0.08 * w), int(0.08 * h)
    yy, xx = np.mgrid[1:h - 1, 1:w - 1]
    band = (xx < bw) | (xx >= w - bw) | (yy < bh) | (yy >= h - bh)
    centre = y[int(0.2 * h):int(0.8 * h), int(0.2 * w):int(0.8 * w)]
    return {
        "blur": float(lap.var()),
        "brightness": float(y.mean()),
        "dark_frac": float((y < 20).mean()),
        "glare_frac": float((centre > 250).mean()),
        "edge_density": float(edges.mean()),
        "border_edge_frac": float(edges[band].sum() / max(edges.sum(), 1)),
    }


def score(path, t=None):
    t = t or json.loads(THRESHOLDS.read_text())
    return scores(shrink(gray(path)), t["edge_e"])


def text_at_edge(ocr, margin):
    w, h = ocr["w"], ocr["h"]
    return sum(1 for l in ocr["lines"] if len(l["text"].strip()) >= 3 and l["box"]
               and (l["box"][0] <= margin * w or l["box"][1] <= margin * h or l["box"][2] >= w - margin * w or l["box"][3] >= h - margin * h))


def verdict(s, kind, t=None, ocr=None):
    t = t or json.loads(THRESHOLDS.read_text())
    k = t[kind]
    reasons = []
    if s["brightness"] < t["dark_mean"] or s["dark_frac"] > t["dark_frac"]:
        reasons.append("dark")
    if s["blur"] < k["blur"]:
        reasons.append("blurry")
    if k.get("glare_frac") is not None and s["glare_frac"] > k["glare_frac"]:
        reasons.append("glare")
    if k.get("empty_edge_density") is not None and s["edge_density"] < k["empty_edge_density"]:
        reasons.append("empty")
    if k.get("border_edge_frac") is not None and s["border_edge_frac"] > k["border_edge_frac"]:
        reasons.append("cut_off")
    if k.get("text_edge_margin") is not None and ocr and text_at_edge(ocr, k["text_edge_margin"]):
        reasons.append("cut_off")
    return reasons


if __name__ == "__main__":
    args = sys.argv[1:]
    out = None
    if args[:1] == ["--csv"]:
        out, args = Path(args[1]), args[2:]
    rows = [(Path(p).name, score(p)) for p in args]
    print(f"{'file':28}" + "".join(f"{m:>17}" for m in METRICS) + "  nameplate / damage")
    for name, s in rows:
        print(f"{name:28}" + "".join(f"{s[m]:17.4f}" for m in METRICS) + f"  {verdict(s, 'nameplate') or 'ok'} / {verdict(s, 'damage') or 'ok'}")
    if out:
        out.write_text("file," + ",".join(METRICS) + "\n" + "".join(f"{n}," + ",".join(repr(s[m]) for m in METRICS) + "\n" for n, s in rows))
