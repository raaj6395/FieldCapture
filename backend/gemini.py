import base64
import io
import json
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
from PIL import Image, ImageOps

HERE = Path(__file__).parent
LOG = HERE / "logs/calls.jsonl"
MODELS = ["gemini-3.1-flash-lite", "gemini-2.5-flash"]
DAILY_CAP = {"gemini-3.1-flash-lite": 450}
DEFAULT_CAP = 18
MIN_GAP_S = 13
RETRY_503_S = 20
URL = "https://generativelanguage.googleapis.com/v1beta/models/{}:generateContent"


class QuotaGuard(Exception):
    pass


def key():
    for line in (HERE / ".env").read_text().splitlines():
        k, _, v = line.partition("=")
        if k.strip() == "GEMINI_API_KEY" and v.strip():
            return v.strip()
    raise RuntimeError("GEMINI_API_KEY missing in backend/.env")


def pacific_day(ts):
    return datetime.fromtimestamp(ts, ZoneInfo("America/Los_Angeles")).date().isoformat()


def calls():
    if not LOG.exists():
        return []
    return [json.loads(l) for l in LOG.read_text().splitlines() if l.strip()]


def cap(model):
    return DAILY_CAP.get(model, DEFAULT_CAP)


def thinking(model):
    return {"thinkingBudget": 0} if model.startswith("gemini-2.5") else {"thinkingLevel": "low"}


def used_today(model, now=None):
    now = now or time.time()
    return sum(c["model"] == model and pacific_day(c["ts"]) == pacific_day(now) for c in calls())


def wait_gap():
    last = max((c["ts"] for c in calls()), default=0)
    gap = MIN_GAP_S - (time.time() - last)
    if gap > 0:
        time.sleep(gap)


def jpeg(path_or_bytes, long_side=640):
    im = Image.open(io.BytesIO(path_or_bytes) if isinstance(path_or_bytes, bytes) else path_or_bytes)
    im = ImageOps.exif_transpose(im).convert("RGB")
    im.thumbnail((long_side, long_side))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=80)
    return buf.getvalue(), im.size


def log(entry):
    LOG.parent.mkdir(exist_ok=True)
    with LOG.open("a") as f:
        f.write(json.dumps(entry) + "\n")


def generate(prompt, schema, image=None, purpose="", models=MODELS):
    parts = [{"text": prompt}]
    size = None
    if image is not None:
        data, size = jpeg(image)
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(data).decode()}})
    for model in [m for m in models for _ in range(2)]:
        body = {"contents": [{"parts": parts}],
                "generationConfig": {"temperature": 0, "responseMimeType": "application/json", "responseSchema": schema,
                                     "thinkingConfig": thinking(model)}}
        if used_today(model) >= cap(model):
            raise QuotaGuard(f"{model}: {cap(model)} calls already made today (Pacific), local cap")
        wait_gap()
        t = time.time()
        try:
            r = httpx.post(URL.format(model), headers={"x-goog-api-key": key()}, json=body, timeout=60)
        except httpx.TimeoutException:
            log({"ts": t, "model": model, "purpose": purpose, "status": "timeout", "ms": round((time.time() - t) * 1000), "image_px": size})
            raise
        u = r.json().get("usageMetadata", {}) if r.headers.get("content-type", "").startswith("application/json") else {}
        log({"ts": t, "model": model, "purpose": purpose, "status": r.status_code, "ms": round((time.time() - t) * 1000),
             "image_px": size, "in_tokens": u.get("promptTokenCount"), "out_tokens": u.get("candidatesTokenCount"),
             "thought_tokens": u.get("thoughtsTokenCount")})
        if r.status_code == 429:
            raise QuotaGuard(f"{model}: HTTP 429 from Google, stopping")
        if r.status_code == 503:
            time.sleep(RETRY_503_S)
            continue
        if r.status_code >= 400:
            raise RuntimeError(f"{model}: HTTP {r.status_code} {r.text[:300]}")
        text = r.json()["candidates"][0]["content"]["parts"][0]["text"]
        return json.loads(text), model
    raise RuntimeError("all models unavailable (503)")
