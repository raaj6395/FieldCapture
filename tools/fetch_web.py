import csv, re, sys, time, urllib.request
from pathlib import Path

UA = {"User-Agent": "FieldCaptureEval/0.1 (research script)"}
OUT = Path(__file__).resolve().parent.parent / "data/web"


def url_for(row):
    thumb = row["thumb"]
    if int(row["w"]) <= 1280 and not thumb.split("?")[0].endswith((".tif.jpg", ".tiff.jpg")):
        return re.sub(r"/thumb(/.+?)/[^/]+$", r"\1", thumb.split("?")[0])
    return re.sub(r"/\d+px-", "/1280px-", thumb.split("?")[0])


def main():
    rows = list(csv.DictReader(open(OUT / "sources.csv")))
    for r in rows:
        dest = OUT / "img" / r["file"]
        if dest.exists():
            continue
        for t in range(5):
            try:
                dest.write_bytes(urllib.request.urlopen(urllib.request.Request(url_for(r), headers=UA)).read())
                print("ok", r["file"])
                break
            except Exception as e:
                print("retry", r["file"], e)
                time.sleep(15 * (t + 1))
        time.sleep(3)


if __name__ == "__main__":
    sys.exit(main())
