import asyncio
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import jsonschema
from fastapi import BackgroundTasks, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

import extract
import gemini
import nameplate
from mapping import line_items

HERE = Path(__file__).parent
STORE = Path(os.environ.get("FC_STORE", HERE / "store"))
SCHEMA = json.loads((HERE / "schema/job_record.schema.json").read_text())
VALIDATOR = jsonschema.Draft202012Validator(SCHEMA, format_checker=jsonschema.FormatChecker())

app = FastAPI(title="FieldCapture backend")


def iso(ms):
    return None if ms is None else datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, indent=2))
    tmp.replace(path)


def safe(name):
    if not name or not all(ch.isalnum() or ch in "-_" for ch in name):
        raise HTTPException(400, f"bad id: {name!r}")
    return name


def voice_fields(meta):
    fields = {}
    for k in ("room", "surface"):
        if meta.get(k):
            fields[k] = {"value": meta[k], "confidence": 0.9, "source": "voice"}
    if meta.get("unrecognised"):
        fields["voice_unrecognised"] = {"value": meta["unrecognised"], "confidence": 0.3, "source": "voice"}
    return fields


def vlm_on():
    return os.environ.get("FC_VLM") == "1"


def with_vlm(fallback, cache, fn, *args):
    if not vlm_on():
        return fallback
    try:
        fields, raw = fn(*args, vlm=gemini.generate)
        write_json(cache, raw)
        return fields
    except Exception as e:
        print(f"vlm failed ({cache.name}): {e}")
        return fallback


def read_nameplate(photo, ocr, quality, cache):
    ml = nameplate.extract(ocr, quality)
    return with_vlm(ml, cache, lambda *a, vlm: extract.nameplate_fields(*a, vlm=vlm), photo, ocr, quality)


def now_ms():
    return int(time.time() * 1000)


@app.post("/captures")
async def post_capture(background: BackgroundTasks, meta: str = Form(...), photo: UploadFile = File(...)):
    received = now_ms()
    m = json.loads(meta)
    job, cid = safe(m["job_id"]), safe(m["capture_id"])
    d = STORE / "jobs" / job / "captures"
    if (d / f"{cid}.json").exists():
        return JSONResponse({"status": "exists", "capture_id": cid}, 200)
    d.mkdir(parents=True, exist_ok=True)
    ext = Path(photo.filename or "x.jpg").suffix.lower() or ".jpg"
    photo_path = d / f"{cid}{ext}"
    photo_path.write_bytes(await photo.read())
    q = m.get("quality") or {}
    rec = {
        "capture_id": cid,
        "type": m["type"],
        "taken_at": iso(m["taken_at"]),
        "received_at": iso(int(time.time() * 1000)),
        "photo": f"captures/{photo_path.name}",
        "voice_command": m.get("transcript"),
        "asr_confidence": m.get("asr_confidence"),
        "retake_of": m.get("retake_of"),
        "quality": {
            "passed": q.get("flag") == "ok",
            "flag": q.get("flag", "unchecked"),
            "attempts": q.get("attempts", 1),
            "scores": q.get("scores"),
            "failed_checks": q.get("reasons", []),
        },
        "fields": voice_fields(m),
    }
    if m["type"] == "nameplate":
        if m.get("ocr"):
            write_json(d / f"{cid}.ocr.json", m["ocr"])
        rec["fields"].update(nameplate.extract(m.get("ocr"), rec["quality"]))
    rec["timing"] = dict(m.get("timing") or {}, received=received, stored=now_ms())
    rec["extraction"] = "pending" if vlm_on() else "done"
    write_json(d / f"{cid}.json", rec)
    if vlm_on():
        background.add_task(run_extraction, d, cid, m, photo_path)
    return JSONResponse({"status": "stored", "capture_id": cid}, 201)


def run_extraction(d, cid, m, photo_path):
    path = d / f"{cid}.json"
    rec = json.loads(path.read_text())
    start = now_ms()
    try:
        if m["type"] == "nameplate":
            rec["fields"].update(read_nameplate(photo_path, m.get("ocr"), rec["quality"], d / f"{cid}.vlm.json"))
        elif m["type"] == "damage":
            rec["fields"].update(with_vlm({}, d / f"{cid}.vlm.json", extract.damage_fields, photo_path, rec["quality"], m.get("room"), m.get("surface")))
    finally:
        rec["timing"].update(extract_start=start, extract_done=now_ms())
        rec["extraction"] = "done"
        write_json(path, rec)


FINALIZE_WAIT_S = 90


async def wait_extractions(job, ids, limit_s):
    d = STORE / "jobs" / job
    files = [f for i in ids for f in (d / "captures" / f"{i}.json", d / "notes" / f"{i}.json") if f.exists()]
    for i in range(limit_s * 2 + 1):
        pending = [f.stem for f in files if json.loads(f.read_text()).get("extraction") == "pending"]
        if not pending or i == limit_s * 2:
            return pending
        await asyncio.sleep(0.5)


@app.post("/notes")
async def post_note(n: dict, background: BackgroundTasks):
    job, nid = safe(n["job_id"]), safe(n["note_id"])
    p = STORE / "jobs" / job / "notes" / f"{nid}.json"
    if p.exists():
        return JSONResponse({"status": "exists", "note_id": nid}, 200)
    write_json(p, {
        "note_id": nid,
        "taken_at": iso(n["taken_at"]),
        "text": n["text"],
        "voice_command": n.get("transcript"),
        "fields": {"needs_review": {"value": True, "confidence": 1.0, "source": "rule: spoken notes are error-prone in noise"}},
        "extraction": "pending" if vlm_on() else "done",
    })
    if vlm_on():
        background.add_task(run_note_extraction, p, n["text"])
    return JSONResponse({"status": "stored", "note_id": nid}, 201)


def run_note_extraction(p, text):
    rec = json.loads(p.read_text())
    try:
        rec["fields"].update(with_vlm({}, p.with_suffix(".vlm.json"), extract.note_fields, text))
    finally:
        rec["extraction"] = "done"
        write_json(p, rec)


EVENT_TYPES = {"IDLE->CONNECTING": "start", "STOPPED->CONNECTING": "start", "CONNECTING->ACTIVE": "connected", "ACTIVE->PAUSED": "pause",
               "PAUSED->ACTIVE": "resume", "RECONNECTING->ACTIVE": "reconnect", "RECONNECTING->PAUSED": "reconnect"}


def event_type(t):
    if t == "device":
        return "device"
    if t.endswith("->RECONNECTING"):
        return "disconnect"
    if t.endswith("->STOPPED"):
        return "stop"
    return EVENT_TYPES.get(t, "device")


def build_record(job, body):
    d = STORE / "jobs" / job
    have_c = {p.stem: json.loads(p.read_text()) for p in sorted((d / "captures").glob("*.json"))}
    have_n = {p.stem: json.loads(p.read_text()) for p in sorted((d / "notes").glob("*.json"))}
    want_c, want_n = body.get("capture_ids", []), body.get("note_ids", [])
    captures = [have_c[i] for i in want_c if i in have_c]
    notes = [have_n[i] for i in want_n if i in have_n]
    missing = [i for i in want_c if i not in have_c] + [i for i in want_n if i not in have_n]
    today = datetime.now(timezone.utc).year
    items = line_items(captures, notes, today)
    delays = [(datetime.fromisoformat(c["received_at"]) - datetime.fromisoformat(c["taken_at"])).total_seconds() for c in captures]
    rec = {
        "schema_version": "1.0",
        "job_id": job,
        "tech_id": body.get("tech_id", "tech-1"),
        "status": "partial" if missing else "complete",
        "missing": missing,
        "finalized_at_ms": now_ms(),
        "started_at": iso(body["started_at"]),
        "ended_at": iso(body.get("ended_at")),
        "device": body.get("device", {"type": "mock", "sdk_version": "DAT 1.1.0"}),
        "session_events": [{"type": event_type(e["type"]), "at": iso(e["at"]), "transition": e["type"], "cause": e.get("cause", "")} for e in body.get("events", [])],
        "captures": captures,
        "notes": notes,
        "line_items": items,
        "summary": {
            "captures": len(captures),
            "notes": len(notes),
            "low_quality": sum(c["quality"]["flag"] == "low" for c in captures),
            "needs_review": sum(i["status"] == "needs_review" for i in items),
            "upload_delay_s_max": round(max(delays), 1) if delays else None,
        },
    }
    return validate(rec)


def validate(rec):
    errors = sorted(VALIDATOR.iter_errors(rec), key=lambda e: list(e.path))
    if errors:
        raise HTTPException(500, "job record failed schema: " + "; ".join(f"{list(e.path)}: {e.message}" for e in errors[:5]))
    return rec


@app.post("/jobs/{job_id}/finalize")
async def finalize(job_id: str, body: dict):
    pending = await wait_extractions(safe(job_id), body.get("capture_ids", []) + body.get("note_ids", []), FINALIZE_WAIT_S)
    if pending:
        raise HTTPException(503, f"extraction still running for {pending}, retry later")
    rec = build_record(safe(job_id), body)
    write_json(STORE / "jobs" / job_id / "job_record.json", rec)
    return rec


@app.get("/jobs/{job_id}")
async def get_job(job_id: str):
    p = STORE / "jobs" / safe(job_id) / "job_record.json"
    if not p.exists():
        raise HTTPException(404, "not finalized")
    return json.loads(p.read_text())


EDITABLE = {"make", "model", "serial", "mfg_date", "appliance", "room", "surface", "damage_type", "size_cm"}
CHOICES = {"damage_type": set(extract.DAMAGE_TYPES), "appliance": nameplate.APPLIANCE_TYPES | {"other"}}


def edited_value(name, v):
    if v is None or (isinstance(v, str) and not v.strip()):
        return None
    if name == "size_cm":
        try:
            w, h = float(v["width"]), float(v["height"])
        except (TypeError, KeyError, ValueError):
            raise HTTPException(400, "size_cm needs width and height in cm")
        if not (0 < w <= 5000 and 0 < h <= 5000):
            raise HTTPException(400, "size_cm out of range")
        return {"width": round(w), "height": round(h)}
    if not isinstance(v, str):
        raise HTTPException(400, f"{name} must be text")
    v = v.strip()
    if name in CHOICES and v not in CHOICES[name]:
        raise HTTPException(400, f"{name} must be one of {sorted(CHOICES[name])}")
    return v


@app.post("/jobs/{job_id}/captures/{capture_id}/fields/{name}")
async def edit_field(job_id: str, capture_id: str, name: str, body: dict):
    d = STORE / "jobs" / safe(job_id)
    rec_path, cap_path = d / "job_record.json", d / "captures" / f"{safe(capture_id)}.json"
    if not rec_path.exists() or not cap_path.exists():
        raise HTTPException(404, "job or capture not found")
    if name not in EDITABLE:
        raise HTTPException(400, f"{name} is not editable")
    cap = json.loads(cap_path.read_text())
    old = cap["fields"].get(name)
    if old and old.get("source") == "human":
        old = old.get("previous")
    cap["fields"][name] = {"value": edited_value(name, body.get("value")), "confidence": 1.0, "source": "human",
                           "edited_at": iso(now_ms()), "previous": old}
    rec = json.loads(rec_path.read_text())
    ids = [c["capture_id"] for c in rec["captures"]]
    rec["captures"] = [cap if i == capture_id else json.loads((d / "captures" / f"{i}.json").read_text()) for i in ids]
    rec["line_items"] = line_items(rec["captures"], rec["notes"], datetime.now(timezone.utc).year)
    rec["summary"]["needs_review"] = sum(i["status"] == "needs_review" for i in rec["line_items"])
    validate(rec)
    write_json(cap_path, cap)
    write_json(rec_path, rec)
    return rec
