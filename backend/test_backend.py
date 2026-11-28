import json

import pytest
from fastapi.testclient import TestClient

import app as backend
from mapping import line_items

T0 = 1795700000000


def cap(cid, typ="damage", flag="ok", **fields):
    return {"capture_id": cid, "type": typ, "quality": {"flag": flag},
            "fields": {k: {"value": v, "confidence": c} for k, (v, c) in fields.items()}}


def codes(items):
    return sorted(i["code"] for i in items)


def test_small_wall_stain_is_paint_only():
    items = line_items([cap("c1", damage_type=("water_stain", 0.9), surface=("north wall", 0.9), room=("kitchen", 0.9),
                            size_cm=({"width": 40, "height": 30}, 0.7))], [], 2026)
    assert codes(items) == ["PNT-WALL"]
    assert items[0]["qty"] == pytest.approx(1.3, abs=0.05)
    assert items[0]["qty_basis"] == "estimated" and items[0]["status"] == "draft" and items[0]["source_ids"] == ["c1"]


def test_large_wall_stain_tears_out_drywall_and_one_drying_line():
    items = line_items([
        cap("c1", damage_type=("water_stain", 0.8), surface=("wall", 0.9), size_cm=({"width": 120, "height": 90}, 0.6)),
        cap("c2", damage_type=("water_damage", 0.7), surface=("floor", 0.9)),
    ], [], 2026)
    assert codes(items) == ["BASE-RR", "DRY-EQ", "DRY-RR", "PNT-WALL", "SUBF-RR"]
    eq = [i for i in items if i["code"] == "DRY-EQ"][0]
    assert eq["source_ids"] == ["c1", "c2"] and eq["qty"] == 3
    subf = [i for i in items if i["code"] == "SUBF-RR"][0]
    assert subf["qty_basis"] == "default"


def test_low_confidence_goes_to_review():
    items = line_items([cap("c1", damage_type=("mold", 0.3), surface=("wall", 0.9))], [], 2026)
    assert codes(items) == ["INSP-REV"]
    assert items[0]["status"] == "needs_review" and items[0]["source_ids"] == ["c1"]


def test_low_quality_photo_marks_items_for_review():
    items = line_items([cap("c1", flag="low", damage_type=("water_stain", 0.9), surface=("ceiling", 0.9))], [], 2026)
    assert set(codes(items)) >= {"PNT-CLG", "CLG-RR", "INS-RR"}
    assert all(i["status"] == "needs_review" for i in items)


def test_old_water_heater_and_notes():
    items = line_items(
        [cap("c1", "nameplate", appliance=("water_heater", 0.9), mfg_date=("2011-04", 0.8))],
        [{"note_id": "n1", "text": "subfloor soft by the cabinet", "fields": {}}],
        2026,
    )
    assert codes(items) == ["CAB-DR", "DRY-EQ", "SUBF-RR", "WH-RPL"]
    wh = [i for i in items if i["code"] == "WH-RPL"][0]
    assert wh["status"] == "suggested" and "15 years" in wh["reason"]
    assert all(i["status"] == "needs_review" for i in items if i["source_ids"] == ["n1"])


def test_no_damage_and_young_heater_give_nothing():
    assert line_items([cap("c1", damage_type=("none", 0.9)), cap("c2", "nameplate", appliance=("water_heater", 0.9), mfg_date=("2022", 0.9))], [], 2026) == []


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(backend, "STORE", tmp_path)
    return TestClient(backend.app)


def post_capture(client, cid, job="J1", **extra):
    meta = {"job_id": job, "capture_id": cid, "type": "damage", "taken_at": T0, "room": "kitchen", "surface": "north wall",
            "transcript": "capture damage kitchen north wall", "quality": {"flag": "ok", "attempts": 1, "reasons": [], "scores": {"blur": 120.0}}}
    meta.update(extra)
    return client.post("/captures", data={"meta": json.dumps(meta)}, files={"photo": (f"{cid}.jpg", b"\xff\xd8fakejpeg", "image/jpeg")})


def finalize(client, captures, notes=(), job="J1"):
    body = {"tech_id": "tech-1", "started_at": T0, "ended_at": T0 + 600000, "capture_ids": list(captures), "note_ids": list(notes),
            "events": [{"type": "IDLE->CONNECTING", "at": T0}, {"type": "ACTIVE->RECONNECTING", "at": T0 + 1000, "cause": "device gone"},
                       {"type": "RECONNECTING->ACTIVE", "at": T0 + 2000}, {"type": "ACTIVE->STOPPED", "at": T0 + 600000}]}
    r = client.post(f"/jobs/{job}/finalize", json=body)
    assert r.status_code == 200, r.text
    return r.json()


def test_same_capture_three_times_is_stored_once(client, tmp_path):
    codes_ = [post_capture(client, "c01").status_code for _ in range(3)]
    assert codes_ == [201, 200, 200]
    assert len(list((tmp_path / "jobs/J1/captures").glob("*.json"))) == 1
    assert client.post("/notes", json={"job_id": "J1", "note_id": "n1", "taken_at": T0, "text": "x"}).status_code == 201
    assert client.post("/notes", json={"job_id": "J1", "note_id": "n1", "taken_at": T0, "text": "x"}).status_code == 200


def test_partial_then_complete_and_schema_valid(client):
    post_capture(client, "c01")
    client.post("/notes", json={"job_id": "J1", "note_id": "n1", "taken_at": T0 + 5, "text": "subfloor soft by the cabinet", "transcript": "note subfloor soft by the cabinet"})
    rec = finalize(client, ["c01", "c02"], ["n1"])
    assert rec["status"] == "partial" and rec["missing"] == ["c02"]
    post_capture(client, "c02", type="nameplate", room=None, surface=None)
    rec = finalize(client, ["c01", "c02"], ["n1"])
    assert rec["status"] == "complete" and rec["missing"] == []
    assert [e["type"] for e in rec["session_events"]] == ["start", "disconnect", "reconnect", "stop"]
    assert rec["summary"]["captures"] == 2 and rec["summary"]["notes"] == 1
    assert {"SUBF-RR", "CAB-DR", "INSP-REV"} <= {i["code"] for i in rec["line_items"]}
    assert client.get("/jobs/J1").json() == rec


def test_bad_ids_rejected(client):
    assert post_capture(client, "../etc").status_code == 400


def test_tiny_damage_area_never_gives_zero_quantity():
    import mapping
    c = {"capture_id": "c1", "quality": {"flag": "ok"}, "fields": {
        "damage_type": {"value": "water_stain", "confidence": 0.9}, "surface": {"value": "wall", "confidence": 0.9},
        "room": {"value": "kitchen", "confidence": 0.9}, "size_cm": {"value": {"width": 3, "height": 2}, "confidence": 0.5}}}
    items = mapping.damage(c, 2026)
    assert items and all(i["qty"] > 0 for i in items)


def test_fixture_damage_goes_to_review_and_insulation_gets_its_own_item():
    sink = line_items([cap("c1", damage_type=("water_damage", 0.9), surface=("sink", 0.9))], [], 2026)
    assert codes(sink) == ["INSP-REV"] and sink[0]["status"] == "needs_review" and "sink" in sink[0]["reason"]
    fan = line_items([cap("c1", damage_type=("water_stain", 0.9), surface=("ceiling fan", 0.9))], [], 2026)
    assert codes(fan) == ["INSP-REV"]
    ins = line_items([cap("c1", damage_type=("water_damage", 0.9), surface=("insulation", 0.9))], [], 2026)
    assert codes(ins) == ["DRY-EQ", "INS-RR"]


def test_peeling_paint_is_paint_only():
    wall = line_items([cap("c1", damage_type=("peeling_paint", 0.9), surface=("wall", 0.9), size_cm=({"width": 120, "height": 90}, 0.6))], [], 2026)
    assert codes(wall) == ["PNT-WALL"]
    ceiling = line_items([cap("c1", damage_type=("peeling_paint", 0.9), surface=("ceiling", 0.9), size_cm=({"width": 120, "height": 90}, 0.6))], [], 2026)
    assert codes(ceiling) == ["PNT-CLG"]


def test_human_edit_overrides_ai_keeps_original_and_rebuilds_estimate(client, tmp_path):
    post_capture(client, "c01", surface="wall")
    finalize(client, ["c01"])
    url = "/jobs/J1/captures/c01/fields/"
    rec = client.post(url + "damage_type", json={"value": "hole"}).json()
    f = rec["captures"][0]["fields"]["damage_type"]
    assert f["value"] == "hole" and f["source"] == "human" and f["confidence"] == 1.0 and f["previous"] is None
    rec = client.post(url + "size_cm", json={"value": {"width": 120, "height": 90}}).json()
    assert {"DRY-RR", "PNT-WALL"} <= set(codes(rec["line_items"])) and "DRY-PATCH" not in codes(rec["line_items"])
    assert all(i["qty_basis"] == "measured" for i in rec["line_items"] if i["unit"] == "sq ft")
    rec = client.post(url + "damage_type", json={"value": "none"}).json()
    assert rec["line_items"] == [] and rec["captures"][0]["fields"]["damage_type"]["previous"] is None
    assert client.get("/jobs/J1").json() == rec
    again = finalize(client, ["c01"])
    assert again["captures"][0]["fields"]["damage_type"]["value"] == "none"


def test_bad_edits_are_rejected(client):
    post_capture(client, "c01")
    finalize(client, ["c01"])
    url = "/jobs/J1/captures/c01/fields/"
    assert client.post(url + "damage_type", json={"value": "flood"}).status_code == 400
    assert client.post(url + "appliance", json={"value": "Sump pump"}).json()["captures"][0]["fields"]["appliance"]["value"] == "Sump pump"
    assert client.post(url + "size_cm", json={"value": {"width": -3}}).status_code == 400
    assert client.post(url + "tech_id", json={"value": "x"}).status_code == 400
    assert client.post("/jobs/J1/captures/c09/fields/serial", json={"value": "x"}).status_code == 404
