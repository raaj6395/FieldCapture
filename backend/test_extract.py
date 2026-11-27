import extract


def fake(resp):
    return lambda prompt, schema, image, purpose="": (resp, "fake")


def dmg(dtype="water_stain", conf=0.9, w=40, h=30, ref="ceiling tile", sconf=0.9):
    return {"damage_type": {"value": dtype, "confidence": conf}, "size": {"width_cm": w, "height_cm": h, "reference": ref, "confidence": sconf}, "evidence": "x"}


def test_size_confidence_is_capped_by_reference():
    with_ref, _ = extract.damage_fields("p.jpg", {"flag": "ok"}, "kitchen", "ceiling", vlm=fake(dmg(ref="ceiling tile")))
    no_ref, _ = extract.damage_fields("p.jpg", {"flag": "ok"}, "kitchen", "ceiling", vlm=fake(dmg(ref="none")))
    assert with_ref["size_cm"]["confidence"] == 0.8 and no_ref["size_cm"]["confidence"] == 0.5
    assert with_ref["size_cm"]["value"] == {"width": 40, "height": 30}


def test_low_quality_lowers_damage_confidence_and_unknown_class_is_null():
    ok, _ = extract.damage_fields("p.jpg", {"flag": "ok"}, None, None, vlm=fake(dmg()))
    low, _ = extract.damage_fields("p.jpg", {"flag": "low"}, None, None, vlm=fake(dmg()))
    assert low["damage_type"]["confidence"] < ok["damage_type"]["confidence"]
    bad, _ = extract.damage_fields("p.jpg", {"flag": "ok"}, None, None, vlm=fake(dmg(dtype="rust")))
    assert bad["damage_type"]["value"] is None and bad["damage_type"]["confidence"] == 0.0 and bad["size_cm"]["value"] is None


def test_no_damage_has_no_size():
    out, _ = extract.damage_fields("p.jpg", {"flag": "ok"}, None, None, vlm=fake(dmg(dtype="none")))
    assert out["damage_type"]["value"] == "none" and out["size_cm"]["value"] is None


def test_note_fields_enum_null_and_asr_discount():
    resp = {"location": {"value": "behind the toilet", "confidence": 1.0}, "issue": {"value": "soft_spot", "confidence": 0.9},
            "material": {"value": "plaster", "confidence": 0.9}, "severity": {"value": "null", "confidence": 0.9}, "action": {"value": None, "confidence": 0}}
    out, _ = extract.note_fields("drywall behind soft wallet", vlm=fake(resp))
    assert out["location"]["confidence"] == extract.ASR_FACTOR
    assert out["material"]["value"] is None and out["severity"]["value"] is None and out["action"]["confidence"] == 0.0


def test_room_and_surface_are_passed_to_the_prompt():
    seen = {}

    def vlm(prompt, schema, image, purpose=""):
        seen["p"] = prompt
        return dmg(), "fake"

    extract.damage_fields("p.jpg", {"flag": "ok"}, "kitchen", "north wall", vlm=vlm)
    assert '"kitchen"' in seen["p"] and '"north wall"' in seen["p"]
