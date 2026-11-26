import csv
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
INCOMING = DATA / "originals" / "incoming"
CAM = "/sdcard/DCIM/Camera"
W, H = 720, 1280


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, check=True).stdout


def new_files(since, exts):
    cutoff = datetime.strptime(since, "%Y-%m-%d %H:%M").timestamp()
    out = adb("shell", f"stat -c '%Y %n' {CAM}/*")
    files = []
    for line in out.splitlines():
        ts, path = line.split(" ", 1)
        if int(ts) >= cutoff and path.lower().endswith(exts):
            files.append((int(ts), path))
    return [p for _, p in sorted(files)]


def labels():
    with open(DATA / "labels.csv") as fh:
        return [r["file"] for r in csv.DictReader(fh)]


def to_glasses(src, dst):
    img = ImageOps.exif_transpose(Image.open(src)).convert("RGB")
    img = ImageOps.fit(img, (W, H), Image.LANCZOS)
    img.save(dst, "JPEG", quality=90)


def contact_sheet(files, names):
    tw, th, cols = 180, 320, 8
    rows = (len(files) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw, rows * (th + 18)), "white")
    d = ImageDraw.Draw(sheet)
    for i, f in enumerate(files):
        x, y = (i % cols) * tw, (i // cols) * (th + 18)
        sheet.paste(ImageOps.fit(ImageOps.exif_transpose(Image.open(f)), (tw - 4, th)), (x + 2, y + 18))
        d.text((x + 3, y + 3), f"{i + 1} {names[i] if i < len(names) else '-- EXTRA --'}", fill="black")
    sheet.save(DATA / "contact.jpg", quality=80)


def pull(since):
    shutil.rmtree(INCOMING, ignore_errors=True)
    INCOMING.mkdir(parents=True)
    photos = new_files(since, (".jpg", ".jpeg"))
    for i, p in enumerate(photos, 1):
        adb("pull", p, str(INCOMING / f"{i:03d}_{Path(p).name}"))
    files = sorted(INCOMING.glob("*.jp*g"))
    names = labels()
    contact_sheet(files, names)
    print(f"pulled {len(files)} photos, labels.csv has {len(names)} rows -> data/contact.jpg")


def assign():
    files = sorted(INCOMING.glob("*.jp*g"))
    names = labels()
    if len(files) != len(names):
        sys.exit(f"{len(files)} photos vs {len(names)} label rows - delete extras in {INCOMING} or fix labels.csv first")
    raw = DATA / "raw"
    raw.mkdir(exist_ok=True)
    for f, name in zip(files, names):
        orig = DATA / "originals" / name
        shutil.copy2(f, orig)
        to_glasses(f, raw / name)
    subprocess.run(["exiftool", "-q", "-gps:all=", "-overwrite_original", *map(str, (DATA / "originals").glob("*.jpg"))], check=True)
    print(f"wrote {len(names)} frames to data/raw ({W}x{H}, no EXIF) and data/originals (GPS stripped)")


def videos(since):
    out = DATA / "videos"
    out.mkdir(exist_ok=True)
    for i, p in enumerate(new_files(since, (".mp4",)), 1):
        tmp = out / f"src_{i}.mp4"
        adb("pull", p, str(tmp))
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(tmp), "-map_metadata", "-1", "-c:v", "hevc_videotoolbox",
                        "-tag:v", "hvc1", "-an", "-vf", "scale=540:960", str(out / f"walk{i}.mp4")], check=True)
        tmp.unlink()
        print(f"walk{i}.mp4 <- {p}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "pull":
        pull(sys.argv[2])
    elif cmd == "assign":
        assign()
    elif cmd == "videos":
        videos(sys.argv[2])
    else:
        sys.exit('usage: ingest.py pull "YYYY-MM-DD HH:MM" | assign | videos "YYYY-MM-DD HH:MM"')
