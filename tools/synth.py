import csv
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "data/web"
OUT = WEB / "synth"


def dark(im, rng):
    a = np.asarray(im, dtype=np.float64) * rng.uniform(0.08, 0.15)
    a += np.random.default_rng(rng.randrange(1 << 30)).normal(0, 3, a.shape)
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def blurry(im, rng):
    return im.filter(ImageFilter.GaussianBlur(im.width * rng.uniform(0.006, 0.01)))


def glare(im, rng):
    w, h = im.size
    r = min(w, h) * rng.uniform(0.2, 0.3)
    cx, cy = w * rng.uniform(0.4, 0.6), h * rng.uniform(0.4, 0.6)
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).ellipse((cx - r * 1.4, cy - r, cx + r * 1.4, cy + r), fill=255)
    m = m.filter(ImageFilter.GaussianBlur(r * 0.25))
    a = np.asarray(im, dtype=np.float64) + np.asarray(m, dtype=np.float64)[..., None] * 1.3
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))


def cut_off(im, rng):
    w, h = im.size
    f = rng.uniform(0.45, 0.6)
    cw, ch = int(w * f), int(h * f)
    x = int((w - cw) * rng.choice([0.0, 1.0]))
    y = int((h - ch) * rng.uniform(0.3, 0.7))
    return im.crop((x, y, x + cw, y + ch))


KINDS = {"dark": dark, "blurry": blurry, "glare": glare, "cut_off": cut_off}


def main():
    OUT.mkdir(exist_ok=True)
    rows = [r for r in csv.DictReader(open(WEB / "labels.csv")) if r["type"] != "damage" or r["damage_type"] != "none"]
    out = []
    for r in rows:
        im = ImageOps.exif_transpose(Image.open(WEB / "img" / r["file"])).convert("RGB")
        for kind, fn in KINDS.items():
            if kind == "cut_off" and r["type"] != "nameplate":
                continue
            rng = random.Random(f"{r['file']}:{kind}")
            name = f"{Path(r['file']).stem}__{kind}.jpg"
            fn(im, rng).save(OUT / name, quality=90)
            out.append({"file": name, "parent": r["file"], "split": r["split"], "type": r["type"], "expect": kind, "label_source": "synthetic"})
    with open(WEB / "synth_labels.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(out[0]))
        w.writeheader()
        w.writerows(out)
    print(len(out), "variants")


if __name__ == "__main__":
    main()
