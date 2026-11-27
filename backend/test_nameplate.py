import json

from fastapi.testclient import TestClient

import app as backend
import nameplate


def ocr(*lines, conf=0.9):
    return {"lines": [{"text": t, "conf": conf, "box": [0, i * 20, 100, i * 20 + 18], "words": [{"text": w, "conf": conf} for w in t.split()]}
                      for i, t in enumerate(lines)]}


def test_label_then_value_on_a_later_line_and_model_no_is_not_a_serial():
    f = nameplate.extract(ocr("SONY MODEL NO. CDP-CE525", "COMPACT DISC PLAYER", "SERIAL N0.", "SERIALNO", "5104890"), {"flag": "ok"})
    assert f["make"]["value"] == "Sony"
    assert f["model"]["value"] == "CDP-CE525"
    assert f["serial"]["value"] == "5104890"


def test_unreadable_fields_are_null_with_zero_confidence():
    f = nameplate.extract(ocr("THREE PHASE INDUCTION MOTOR", "IP 55"), {"flag": "ok"})
    for k in ("model", "serial", "mfg_date"):
        assert f[k]["value"] is None and f[k]["confidence"] == 0.0


def test_low_quality_photo_lowers_confidence():
    lines = ocr("RHEEM", "MODEL XE50T10", "SERIAL Q081234567")
    ok, low = nameplate.extract(lines, {"flag": "ok"}), nameplate.extract(lines, {"flag": "low"})
    for k in ("make", "model", "serial"):
        assert low[k]["value"] == ok[k]["value"]
        assert low[k]["confidence"] < ok[k]["confidence"]


def test_low_ocr_confidence_lowers_field_confidence():
    a = nameplate.extract(ocr("SERIAL 12345678", conf=0.9), {"flag": "ok"})["serial"]["confidence"]
    b = nameplate.extract(ocr("SERIAL 12345678", conf=0.4), {"flag": "ok"})["serial"]["confidence"]
    assert b < a


def test_dates_and_impossible_year():
    assert nameplate.extract(ocr("DATE 10-17-40"))["mfg_date"]["value"] == "1940-10-17"
    assert nameplate.extract(ocr("MFG DATE 04/2011"))["mfg_date"]["value"] == "2011-04"
    good = nameplate.extract(ocr("MFG DATE 2011"))["mfg_date"]
    bad = nameplate.extract(ocr("MFG DATE 2099"))["mfg_date"]
    assert bad["signals"]["validation"] == 0.3 and bad["confidence"] < good["confidence"]


def test_number_glued_to_label_and_short_brand_is_discounted():
    f = nameplate.extract(ocr("Chassis N11400", "GE APPLIANCES"), {"flag": "ok"})
    assert f["serial"]["value"] == "11400"
    assert f["make"]["value"] == "GE" and f["make"]["signals"]["validation"] == 0.6


def test_appliance_keyword():
    f = nameplate.extract(ocr("Water Heater - ELECTRIC", "MODEL PROE50 T2 RH95"), {"flag": "ok"})
    assert f["appliance"]["value"] == "water_heater"


def test_endpoint_fills_nameplate_fields_and_record_validates(tmp_path, monkeypatch):
    monkeypatch.setattr(backend, "STORE", tmp_path)
    c = TestClient(backend.app)
    meta = {"job_id": "J2", "capture_id": "c01", "type": "nameplate", "taken_at": 1795700000000, "quality": {"flag": "ok", "attempts": 1},
            "ocr": ocr("RHEEM", "Water Heater", "MODEL XE50T10", "SERIAL Q081234567", "MFG DATE 2011-04")}
    assert c.post("/captures", data={"meta": json.dumps(meta)}, files={"photo": ("c01.jpg", b"\xff\xd8x", "image/jpeg")}).status_code == 201
    r = c.post("/jobs/J2/finalize", json={"started_at": 1795700000000, "capture_ids": ["c01"], "note_ids": [], "events": []})
    assert r.status_code == 200, r.text
    f = r.json()["captures"][0]["fields"]
    assert f["make"]["value"] == "Rheem" and f["serial"]["value"] == "Q081234567" and f["mfg_date"]["value"] == "2011-04"
    assert (tmp_path / "jobs/J2/captures/c01.ocr.json").exists()
    assert {i["code"] for i in r.json()["line_items"]} == {"WH-RPL"}


def vlm(**fields):
    return {k: {"value": v, "confidence": c, "raw_text": v} for k, (v, c) in fields.items()}


def test_two_readers_agreeing_beats_either_alone_and_conflict_drops():
    ml = nameplate.extract(ocr("RHEEM", "SERIAL Q081234567", conf=0.7), {"flag": "ok"})
    agree = nameplate.combine(ml, nameplate.from_gemini(vlm(make=("Rheem", 0.9), serial=("Q081234567", 0.9)), {"flag": "ok"}))
    conflict = nameplate.combine(ml, nameplate.from_gemini(vlm(make=("Rheem", 0.9), serial=("Q081284567", 0.95)), {"flag": "ok"}))
    solo = nameplate.combine(nameplate.extract(ocr("RHEEM"), {"flag": "ok"}), nameplate.from_gemini(vlm(serial=("Q081234567", 0.9)), {"flag": "ok"}))
    assert agree["serial"]["confidence"] > max(ml["serial"]["confidence"], 0.9 * 1.0)
    assert agree["serial"]["source"].endswith(":agree")
    assert conflict["serial"]["source"].endswith(":conflict") and conflict["serial"]["confidence"] < 0.5
    assert solo["serial"]["source"].endswith(":solo") and solo["serial"]["confidence"] < 0.9 * 0.8


def test_vlm_null_strings_and_umlauts():
    g = nameplate.from_gemini(vlm(make=("Fritz Bührer", 0.9), serial=("null", 0.9), model=("N/A", 0.5)), {"flag": "ok"})
    assert g["serial"]["value"] is None and g["serial"]["confidence"] == 0.0 and g["model"]["value"] is None
    assert nameplate.norm("Bührer") == nameplate.norm("Buhrer")


def test_vlm_off_by_default_no_api_call(tmp_path, monkeypatch):
    monkeypatch.setattr(backend, "STORE", tmp_path)
    monkeypatch.delenv("FC_VLM", raising=False)
    monkeypatch.setattr(backend.gemini, "generate", lambda *a, **k: (_ for _ in ()).throw(AssertionError("api called")))
    meta = {"job_id": "J3", "capture_id": "c01", "type": "nameplate", "taken_at": 1795700000000, "quality": {"flag": "ok"}, "ocr": ocr("SERIAL 12345678")}
    assert TestClient(backend.app).post("/captures", data={"meta": json.dumps(meta)}, files={"photo": ("c01.jpg", b"\xff\xd8x", "image/jpeg")}).status_code == 201


def test_vlm_failure_keeps_ocr_fields(tmp_path, monkeypatch):
    monkeypatch.setattr(backend, "STORE", tmp_path)
    monkeypatch.setenv("FC_VLM", "1")
    monkeypatch.setattr(backend.gemini, "generate", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("503")))
    meta = {"job_id": "J4", "capture_id": "c01", "type": "nameplate", "taken_at": 1795700000000, "quality": {"flag": "ok"}, "ocr": ocr("SERIAL 12345678")}
    c = TestClient(backend.app)
    assert c.post("/captures", data={"meta": json.dumps(meta)}, files={"photo": ("c01.jpg", b"\xff\xd8x", "image/jpeg")}).status_code == 201
    rec = json.loads((tmp_path / "jobs/J4/captures/c01.json").read_text())
    assert rec["fields"]["serial"]["value"] == "12345678" and rec["fields"]["serial"]["source"] == "ocr:mlkit"
