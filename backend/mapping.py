import json
import re
from pathlib import Path

CATALOG = {i["code"]: i for i in json.loads((Path(__file__).parent / "line_items.json").read_text())}
REVIEW_BELOW = 0.5
SMALL_SQFT = 4.0
PATCH_SQFT = 2.0
DEFAULT_SQFT = 10.0
DRYING_DAYS = 3
WH_MAX_AGE = 10
CM2_PER_SQFT = 929.03
WALLS = {"", "wall", "north wall", "south wall", "east wall", "west wall", "drywall", "baseboard", "trim", "stud"}
FLOORS = {"floor", "subfloor"}
CABINETS = {"cabinet", "vanity"}


def val(fields, name):
    f = fields.get(name)
    return (f["value"], f["confidence"]) if f and f.get("value") is not None else (None, 0.0)


def item(code, qty, basis, sources, conf, room=None, status=None, reason=None):
    i = dict(CATALOG[code], qty=max(round(qty, 1), 0.1), qty_basis=basis, room=room, source_ids=list(sources), confidence=round(conf, 2),
             status=status or ("needs_review" if conf < REVIEW_BELOW else "draft"))
    if reason:
        i["reason"] = reason
    return i


def area(fields):
    size, conf = val(fields, "size_cm")
    if size and size.get("width") and size.get("height"):
        return size["width"] * size["height"] / CM2_PER_SQFT, size["width"] / 30.48, "estimated", conf
    return DEFAULT_SQFT, 4.0, "default", None


def damage(c, today_year):
    f = c["fields"]
    dtype, dconf = val(f, "damage_type")
    surface, sconf = val(f, "surface")
    room, _ = val(f, "room")
    ids = [c["capture_id"]]
    if dtype is None or dtype == "unknown" or dconf < REVIEW_BELOW:
        return [item("INSP-REV", 1, "default", ids, dconf, room, "needs_review", f"damage type unclear (conf {dconf:.2f})")]
    if dtype == "none":
        return []
    sqft, width_ft, basis, size_conf = area(f)
    conf = min(x for x in (dconf, sconf if surface else 1.0, size_conf if size_conf is not None else 1.0))
    low = c["quality"]["flag"] == "low"
    status = "needs_review" if low or conf < REVIEW_BELOW else None
    s = surface or ""
    if s not in WALLS | FLOORS | CABINETS | {"ceiling", "insulation"}:
        return [item("INSP-REV", 1, "default", ids, conf, room, "needs_review", f"{dtype} on {s}: no catalog item, price by hand")]
    out = []
    if dtype == "peeling_paint" and s in WALLS | {"ceiling"}:
        return [item("PNT-CLG" if s == "ceiling" else "PNT-WALL", sqft, basis, ids, conf, room, status)]
    if s == "ceiling":
        if dtype == "mold":
            out += [item("MOLD-TRT", sqft, basis, ids, conf, room, status), item("CLG-RR", sqft, basis, ids, conf, room, status)]
        else:
            out.append(item("PNT-CLG", sqft, basis, ids, conf, room, status))
            if sqft >= SMALL_SQFT or dtype == "water_damage":
                out += [item("CLG-RR", sqft, basis, ids, conf, room, status), item("INS-RR", sqft, basis, ids, conf, room, status),
                        item("DRY-EQ", DRYING_DAYS, "default", ids, conf, room, status)]
    elif s == "insulation":
        out += [item("INS-RR", sqft, basis, ids, conf, room, status), item("DRY-EQ", DRYING_DAYS, "default", ids, conf, room, status)]
    elif s in FLOORS:
        out += [item("SUBF-RR", sqft, basis, ids, conf, room, status), item("DRY-EQ", DRYING_DAYS, "default", ids, conf, room, status)]
    elif s in CABINETS:
        out.append(item("CAB-DR", 1, "default", ids, conf, room, status))
    elif dtype == "mold":
        out += [item("MOLD-TRT", sqft, basis, ids, conf, room, status), item("DRY-RR", sqft, basis, ids, conf, room, status)]
    elif dtype in ("hole", "crack"):
        if sqft < PATCH_SQFT:
            out += [item("DRY-PATCH", 1, "default", ids, conf, room, status), item("PNT-WALL", sqft, basis, ids, conf, room, status)]
        else:
            out += [item("DRY-RR", sqft, basis, ids, conf, room, status), item("PNT-WALL", sqft, basis, ids, conf, room, status)]
    elif dtype == "water_stain" and sqft < SMALL_SQFT:
        out.append(item("PNT-WALL", sqft, basis, ids, conf, room, status))
    else:
        out += [item("DRY-RR", sqft, basis, ids, conf, room, status), item("PNT-WALL", sqft, basis, ids, conf, room, status),
                item("BASE-RR", width_ft, basis, ids, conf, room, status), item("DRY-EQ", DRYING_DAYS, "default", ids, conf, room, status)]
    return out


def nameplate(c, today_year):
    f = c["fields"]
    appliance, aconf = val(f, "appliance")
    date, dconf = val(f, "mfg_date")
    if appliance != "water_heater" or not date:
        return []
    year = int(str(date)[:4])
    age = today_year - year
    if age <= WH_MAX_AGE:
        return []
    return [item("WH-RPL", 1, "default", [c["capture_id"]], min(aconf, dconf), None, "suggested", f"water heater made {year}, {age} years old")]


NOTE_RULES = [
    (r"\b(sub ?floor)\b.*\b(soft|spongy|rot\w*|wet)\b|\b(soft|spongy)\b.*\bsub ?floor\b", [("SUBF-RR", DEFAULT_SQFT), ("DRY-EQ", DRYING_DAYS)]),
    (r"\bcabinet", [("CAB-DR", 1)]),
    (r"\bmou?ld", [("MOLD-TRT", DEFAULT_SQFT)]),
    (r"\b(insulation)\b", [("INS-RR", DEFAULT_SQFT)]),
]


def note(n, today_year):
    text = n["text"].lower()
    room, _ = val(n["fields"], "location")
    out = []
    for pattern, items in NOTE_RULES:
        if re.search(pattern, text):
            out += [item(code, qty, "default", [n["note_id"]], 0.5, room, "needs_review", "from a spoken note, check the note text") for code, qty in items]
    return out


def merge(items):
    out, eq = [], None
    for i in items:
        if i["code"] != "DRY-EQ":
            out.append(i)
        elif eq is None:
            eq = dict(i, room=None)
        else:
            eq["qty"] = max(eq["qty"], i["qty"])
            eq["source_ids"] += [s for s in i["source_ids"] if s not in eq["source_ids"]]
            eq["confidence"] = max(eq["confidence"], i["confidence"])
            if i["status"] == "draft":
                eq["status"] = "draft"
    return out + ([eq] if eq else [])


def line_items(captures, notes, today_year):
    items = []
    for c in captures:
        items += damage(c, today_year) if c["type"] == "damage" else nameplate(c, today_year)
    for n in notes:
        items += note(n, today_year)
    return merge(items)
