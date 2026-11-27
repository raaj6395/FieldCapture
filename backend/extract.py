import json
import sys
from pathlib import Path

import gemini
import nameplate

HERE = Path(__file__).parent
PROMPT = {k: (HERE / f"prompts/{k}.txt").read_text() for k in ("nameplate", "damage", "note")}

DAMAGE_TYPES = ["water_stain", "water_damage", "mold", "crack", "hole", "peeling_paint", "swelling", "none"]
ISSUES = ["leak", "wet", "water_stain", "mold", "soft_spot", "crack", "hole", "swelling", "odor", "other"]
MATERIALS = ["drywall", "subfloor", "carpet", "cabinet", "ceiling", "insulation", "baseboard", "tile", "wood", "pipe", "appliance", "other"]
SEVERITIES = ["low", "medium", "high"]


def vfield(kind="string", enum=None):
    value = {"type": kind, "nullable": True}
    if enum:
        value["enum"] = enum
    return {"type": "object", "properties": {"value": value, "confidence": {"type": "number"}}, "required": ["value", "confidence"]}


def obj(props):
    return {"type": "object", "properties": props, "required": list(props)}


TEXT_FIELD = {"type": "object", "properties": {"value": {"type": "string", "nullable": True}, "confidence": {"type": "number"},
                                               "raw_text": {"type": "string", "nullable": True}}, "required": ["value", "confidence", "raw_text"]}
NAMEPLATE_SCHEMA = obj({k: TEXT_FIELD for k in nameplate.FIELDS})
NAMEPLATE_SCHEMA_LEAN = obj({k: vfield() for k in nameplate.FIELDS})
DAMAGE_SCHEMA = obj({
    "damage_type": vfield(enum=DAMAGE_TYPES),
    "size": obj({"width_cm": {"type": "number", "nullable": True}, "height_cm": {"type": "number", "nullable": True},
                 "reference": {"type": "string"}, "confidence": {"type": "number"}}),
    "evidence": {"type": "string"},
})
NOTE_SCHEMA = obj({"location": vfield(), "issue": vfield(enum=ISSUES), "material": vfield(enum=MATERIALS),
                   "severity": vfield(enum=SEVERITIES), "action": vfield()})

QUALITY = nameplate.quality_factor
SIZE_CAP_NO_REF, SIZE_CAP_REF = 0.5, 0.8
ASR_FACTOR = 0.8


def clamp(x):
    return round(max(0.0, min(1.0, float(x or 0.0))), 3)


def nameplate_fields(photo, ocr, quality, vlm=gemini.generate, use_vlm=True):
    ml = nameplate.extract(ocr, quality)
    if not use_vlm:
        return ml, None
    lean = gemini.profile() == "lean"
    prompt = "\n".join(l for l in PROMPT["nameplate"].splitlines() if not l.startswith("- raw_text")) if lean else PROMPT["nameplate"]
    resp, model = vlm(prompt, NAMEPLATE_SCHEMA_LEAN if lean else NAMEPLATE_SCHEMA, photo, purpose=f"nameplate:{Path(photo).name}")
    return nameplate.combine(ml, nameplate.from_gemini(resp, quality, model)), {"model": model, "response": resp}


def damage_fields(photo, quality, room, surface, vlm=gemini.generate):
    prompt = PROMPT["damage"].replace("{room}", room or "not said").replace("{surface}", surface or "not said")
    resp, model = vlm(prompt, DAMAGE_SCHEMA, photo, purpose=f"damage:{Path(photo).name}")
    q = QUALITY(quality)
    src = f"vlm:{model}"
    dt = resp.get("damage_type") or {}
    v = dt.get("value") if dt.get("value") in DAMAGE_TYPES else None
    out = {"damage_type": {"value": v, "confidence": clamp(dt.get("confidence", 0) * q) if v else 0.0, "source": src,
                           "raw_text": resp.get("evidence"), "signals": {"model": clamp(dt.get("confidence")), "quality": q}}}
    s = resp.get("size") or {}
    w, h = s.get("width_cm"), s.get("height_cm")
    ref = (s.get("reference") or "none").strip()
    has_ref = ref.lower() not in ("", "none", "null")
    if v and v != "none" and w and h and w > 0 and h > 0:
        cap = SIZE_CAP_REF if has_ref else SIZE_CAP_NO_REF
        out["size_cm"] = {"value": {"width": round(w), "height": round(h)}, "confidence": clamp(min(s.get("confidence", 0), cap) * q),
                          "source": src, "raw_text": f"reference: {ref}", "signals": {"model": clamp(s.get("confidence")), "quality": q, "cap": cap}}
    else:
        out["size_cm"] = {"value": None, "confidence": 0.0, "source": src, "raw_text": f"reference: {ref}"}
    return out, {"model": model, "response": resp}


def note_fields(text, vlm=gemini.generate):
    resp, model = vlm(PROMPT["note"].replace("{text}", text), NOTE_SCHEMA, None, purpose="note")
    enums = {"issue": ISSUES, "material": MATERIALS, "severity": SEVERITIES}
    out = {}
    for k in ("location", "issue", "material", "severity", "action"):
        r = resp.get(k) or {}
        v = r.get("value")
        if isinstance(v, str) and (not v.strip() or v.strip().lower() in ("null", "none")):
            v = None
        if k in enums and v not in enums[k]:
            v = None
        out[k] = {"value": v, "confidence": clamp(r.get("confidence", 0) * ASR_FACTOR) if v else 0.0, "source": f"vlm:{model}",
                  "signals": {"model": clamp(r.get("confidence")), "asr": ASR_FACTOR}}
    return out, {"model": model, "response": resp}


if __name__ == "__main__":
    kind, arg = sys.argv[1], sys.argv[2]
    if kind == "note":
        print(json.dumps(note_fields(arg)[0], indent=1))
    elif kind == "damage":
        print(json.dumps(damage_fields(arg, {"flag": "unchecked"}, *(sys.argv[3:5] + [None, None])[:2])[0], indent=1))
    else:
        print(json.dumps(nameplate_fields(arg, None, {"flag": "unchecked"})[0], indent=1))
