# Model notes (observed)

## 2026-11-26 - Gemini API smoke test (free tier key from AI Studio)

- Key lists 49 models with generateContent, incl. gemini-2.5-flash, gemini-3.5-flash, gemini-3.8-flash, flash-lite variants.
- `gemini-3.8-flash` -> **HTTP 503 "high demand"** on the first call. Free-tier capacity on the newest model is not reliable -> backend needs retry with backoff + a fallback model list.
- `gemini-2.5-flash` -> OK, 4.2 s, JSON mode (`responseMimeType: application/json`) returned valid JSON.
- Tokens: 48 text + **258 image** (640x480 JPEG) + 61 output + **487 thinking**. Thinking is on by default and was most of the tokens and latency -> try a low/zero thinking budget for extraction and measure accuracy vs latency.
- Synthetic test image (drawn brown blob + blue "card"): model answered `damage_type: "None"` with **confidence 1.0**. Model self-confidence is not trustworthy on its own -> combine with photo quality, format checks and a second signal (Phase 5 plan). Smoke test only, not part of the test set.

## 2026-11-27 - Phase 5 step 1: ML Kit on-device OCR for nameplates (no API calls)

Engine: `com.google.mlkit:text-recognition:16.0.1` (bundled Latin model, works offline). Runs inline on every nameplate capture before the spoken reply; result saved as `cNN.ocr.json` next to the photo and uploaded in the capture meta. Backend `nameplate.py` parses make / model / serial / mfg_date / appliance with label rules and gives each field
`confidence = ocr x quality x validation` (signals stored per field):
- ocr: mean ML Kit word confidence of the value tokens (line confidence as fallback)
- quality: 1.0 gate ok, 0.8 unchecked, 0.5 saved "low"
- validation: make in brand list 1.0 (fuzzy 0.8, 2-3 letter brand 0.6, biggest-text fallback 0.4); model/serial format 1.0 / 0.5; year 1900..today 1.0 / 0.3; null -> 0

Measured with `tools/eval_ocr.py` on the 25 web nameplates and their synthetic variants, OCR run on the phone via the `ocr:<dir>` debug hook (results committed in `data/web/ocr_mlkit/` so the eval reruns without a phone). Parser rules were changed looking at the tune split only.

| set (640 px, capture size) | correct | wrong | missed | right null | mean conf correct / wrong |
|---|---|---|---|---|---|
| tune, originals | 12 | 10 | 11 | 15 | 0.56 / 0.30 |
| eval, originals | 12 | 9 | 13 | 18 | 0.55 / 0.32 |
| eval, dark | 8 | 6 | 21 | 17 | 0.23 / 0.08 |
| eval, blurry | 2 | 1 | 31 | 18 | 0.15 / 0.08 |
| eval, glare | 6 | 8 | 20 | 18 | 0.23 / 0.19 |
| eval, cut_off | 4 | 9 | 21 | 18 | 0.49 / 0.23 |

- Confidence goes down when the answer is wrong on every set except tune cut_off (0.27 / 0.37): a cut-off serial is a clean partial read that no signal can see. Cut-off is also the check the gate no longer runs (Phase 4).
- Accuracy is low on its own (12 of 34 present fields on eval). Most losses are OCR misreads on old stamped / dark plates ("Baujchr 1S", "DATE GE") and unlabelled codes (badges with no MODEL label). Planned second read: Cloud Vision DOCUMENT_TEXT_DETECTION on the backend; agreement between two independent OCR reads becomes the strongest signal.
- Full resolution is not better than 640 px (eval all fields 13 correct vs 12, wrong-answer confidence higher), so the 640x480 mock captures are not the bottleneck.
- Latency on the phone at 640 px: median 71 ms, p90 119 ms (n=125); 378 ms for the first call of a fresh process (model load). Cost 0.
- Live check on job 5C1FE517: capture -> OCR -> upload -> finalize works end to end; with no text in view every field is null with confidence 0.
