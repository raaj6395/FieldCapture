import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import jsonschema
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

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


@app.post("/captures")
async def post_capture(meta: str = Form(...), photo: UploadFile = File(...)):
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
    write_json(d / f"{cid}.json", rec)
    return JSONResponse({"status": "stored", "capture_id": cid}, 201)


@app.post("/notes")
async def post_note(n: dict):
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
    })
    return JSONResponse({"status": "stored", "note_id": nid}, 201)


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
    errors = sorted(VALIDATOR.iter_errors(rec), key=lambda e: list(e.path))
    if errors:
        raise HTTPException(500, "job record failed schema: " + "; ".join(f"{list(e.path)}: {e.message}" for e in errors[:5]))
    return rec


@app.post("/jobs/{job_id}/finalize")
async def finalize(job_id: str, body: dict):
    rec = build_record(safe(job_id), body)
    write_json(STORE / "jobs" / job_id / "job_record.json", rec)
    return rec


@app.get("/jobs/{job_id}")
async def get_job(job_id: str):
    p = STORE / "jobs" / safe(job_id) / "job_record.json"
    if not p.exists():
        raise HTTPException(404, "not finalized")
    return json.loads(p.read_text())
