import re
from datetime import date

BRANDS = [
    "A.O. Smith", "Amana", "American Standard", "Ariston", "Bosch", "Bradford White", "Carrier", "Copeland", "Daikin", "Danfoss",
    "Electrolux", "Frigidaire", "GE", "Goodman", "Grundfos", "Haier", "Heil", "Honeywell", "Kenmore", "KitchenAid", "Lennox",
    "LG", "Liebherr", "Maytag", "Miele", "Mitsubishi", "Navien", "Panasonic", "Rheem", "Rinnai", "Ruud", "Samsung", "Siemens",
    "Sharp", "Sony", "State", "Toshiba", "Trane", "Whirlpool", "York", "Canon", "Philips", "Hitachi", "Fujitsu", "Bryant",
]
SOURCE = "ocr:mlkit"
TODAY = date.today()

LABEL = {
    "model": r"(?:MODEL|MOD|MDL|M/N|MODELL|TYPE|TYP|FRAME|DRAWING|P/N|PART|CAT)",
    "serial": r"(?:SERIAL|SER|S/N|SN|SERIE|CHASSIS|FAHRGESTELL)",
    "serial_weak": r"(?:NR|NO|N[O0]\.|Nº|No)",
    "date": r"(?:MFG\.?\s*DATE|MFD|DATE(?:\s+OF\s+MANUFACTURE)?|MANUFACTURED|BAUJAHR|YEAR|MFG)",
}
STOP = r"(?:MODEL|SERIAL|S/N|TYPE|DATE|MFG|NO\.?|NR\.?|V|HZ|KW|HP|RPM|A|W|IP)\b"


def norm(s):
    return re.sub(r"[^A-Z0-9]", "", (s or "").upper())


def fix_label(text):
    return re.sub(r"\bN0\b", "NO", text.upper().replace("N0.", "NO."))


def word_conf(line, value):
    toks = [w for w in line.get("words", []) if norm(w["text"]) and norm(w["text"]) in norm(value)]
    return sum(w["conf"] for w in toks) / len(toks) if toks else line.get("conf", 0.0)


def after_label(lines, label, value_re, exclude=None):
    for i, line in enumerate(lines):
        t = fix_label(line["text"])
        if exclude and re.search(rf"\b{exclude}\b", t):
            continue
        m = re.search(rf"\b{label}\b\.?\s*(?:NO\.?|NR\.?|#|Nº|N°|N(?=\d))?\s*[:.]?\s*(.*)$", t)
        if not m:
            continue
        rest = re.split(rf"\s+{STOP}", m.group(1))[0].strip(" .:")
        v = re.match(value_re, rest)
        if v and re.search(r"\d", v.group(0)):
            return v.group(0).strip(" .-"), line
        if rest:
            continue
        for nxt in lines[i + 1:i + 3]:
            if not re.search(r"\d", nxt["text"]):
                continue
            v = re.match(value_re, nxt["text"].upper().strip())
            if v:
                return v.group(0).strip(" .-"), nxt
            break
    return None, None


def edit1(a, b):
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) <= 1
    s, l = sorted((a, b), key=len)
    return any(l[:i] + l[i + 1:] == s for i in range(len(l)))


def find_make(lines):
    for line in lines:
        for w in re.findall(r"[A-Za-z][A-Za-z.&-]+", line["text"]):
            for b in BRANDS:
                nb, nw = norm(b), norm(w)
                if nw == nb or (len(nb) >= 5 and edit1(nw, nb)):
                    return b, line, 0.6 if len(nb) <= 3 else 1.0 if nw == nb else 0.8
    big = [l for l in lines if len(l.get("box", [])) == 4 and re.fullmatch(r"[A-Za-z][A-Za-z .&-]{2,30}", l["text"].strip())]
    if big:
        top = max(big, key=lambda l: l["box"][3] - l["box"][1])
        return top["text"].strip().title(), top, 0.4
    return None, None, 0.0


def parse_date(s):
    s = s.upper().replace("O", "0").replace("L", "1")
    m = re.search(r"\b(\d{1,2})[-/.\s]+(\d{1,2})[-/.\s]+(\d{2,4})\b", s)
    if m:
        mo, d, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
        y = y + (1900 if y > TODAY.year % 100 else 2000) if y < 100 else y
        if 1 <= mo <= 12 and 1 <= d <= 31:
            return f"{y:04d}-{mo:02d}-{d:02d}", y
    m = re.search(r"\b((?:19|20)\d{2})[-/.](\d{1,2})\b", s)
    if m and 1 <= int(m.group(2)) <= 12:
        return f"{m.group(1)}-{int(m.group(2)):02d}", int(m.group(1))
    m = re.search(r"\b(\d{1,2})[-/.]((?:19|20)\d{2})\b", s)
    if m and 1 <= int(m.group(1)) <= 12:
        return f"{m.group(2)}-{int(m.group(1)):02d}", int(m.group(2))
    m = re.search(r"\b((?:19|20)\d{2})\b", s)
    if m:
        return m.group(1), int(m.group(1))
    return None, None


def find_date(lines):
    for i, line in enumerate(lines):
        t = line["text"].upper()
        m = re.search(rf"\b{LABEL['date']}\b\.?\s*[:.]?\s*(.*)$", t)
        if not m:
            continue
        for cand, src in ((m.group(1), line), (lines[i + 1]["text"] if i + 1 < len(lines) else "", lines[i + 1] if i + 1 < len(lines) else None)):
            v, y = parse_date(cand)
            if v:
                return v, y, src
    return None, None, None


def quality_factor(quality):
    flag = (quality or {}).get("flag", "unchecked")
    return {"ok": 1.0, "unchecked": 0.8, "low": 0.5}.get(flag, 0.8)


def field(value, ocr, qual, valid, raw):
    if value is None:
        return {"value": None, "confidence": 0.0, "raw_text": raw, "source": SOURCE, "signals": {"ocr": 0.0, "quality": qual, "validation": 0.0}}
    c = max(0.0, min(1.0, ocr * qual * valid))
    return {"value": value, "confidence": round(c, 3), "raw_text": raw, "source": SOURCE,
            "signals": {"ocr": round(ocr, 3), "quality": qual, "validation": valid}}


APPLIANCES = [
    (r"WATER\s*HEATER|WARMWASSER|BOILER", "water_heater"),
    (r"WASH|WASCH|LAVE", "washing_machine"),
    (r"DRYER|TROCKNER", "dryer"),
    (r"COMPRESSOR|SCROLL|R-?410A|R-?22\b", "hvac_compressor"),
    (r"FURNACE", "furnace"),
    (r"REFRIGERAT|FRIDGE|K[UÜ]HL", "refrigerator"),
    (r"DISHWASH|SP[UÜ]L", "dishwasher"),
]


def find_appliance(lines):
    for line in lines:
        for pattern, kind in APPLIANCES:
            if re.search(pattern, line["text"].upper()):
                return kind, line
    return None, None


CODE = r"[A-Z0-9][A-Z0-9\-/.,]{1,24}(?:\s[A-Z0-9][A-Z0-9\-/.]{0,8}){0,2}"
SERIAL = r"[A-Z0-9][A-Z0-9\-_]{3,24}"


def extract(ocr, quality=None):
    lines = (ocr or {}).get("lines", [])
    q = quality_factor(quality)

    make, mline, mvalid = find_make(lines)
    model, oline = after_label(lines, LABEL["model"], CODE)
    serial, sline = after_label(lines, LABEL["serial"], SERIAL)
    if serial is None:
        serial, sline = after_label(lines, LABEL["serial_weak"], SERIAL, exclude=LABEL["model"])
    mfg, year, dline = find_date(lines)
    appliance, aline = find_appliance(lines)

    def code_ok(v):
        n = norm(v)
        return 1.0 if 3 <= len(n) <= 24 and re.search(r"[A-Z0-9]", n) else 0.5

    def serial_ok(v):
        n = norm(v)
        return 1.0 if 4 <= len(n) <= 24 and re.search(r"\d", n) and " " not in v else 0.5

    return {
        "make": field(make, mline["conf"] if mline else 0.0, q, mvalid, mline["text"] if mline else None),
        "model": field(model, word_conf(oline, model) if oline else 0.0, q, code_ok(model) if model else 0.0, oline["text"] if oline else None),
        "serial": field(serial, word_conf(sline, serial) if sline else 0.0, q, serial_ok(serial) if serial else 0.0, sline["text"] if sline else None),
        "appliance": field(appliance, aline["conf"] if aline else 0.0, q, 0.8 if appliance else 0.0, aline["text"] if aline else None),
        "mfg_date": field(mfg, word_conf(dline, mfg) if dline else 0.0, q, (1.0 if 1900 <= year <= TODAY.year else 0.3) if mfg else 0.0, dline["text"] if dline else None),
    }
