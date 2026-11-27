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

## 2026-11-27 - Phase 5 step 2: Gemini as second nameplate reader (no Cloud Vision key available)

Free-tier guard in `backend/gemini.py` (checked before every call, all calls logged to `backend/logs/calls.jsonl` incl. timeouts): >= 13 s between calls (< 5 RPM), daily cap per model per Pacific day: gemini-3.1-flash-lite 450 (user reports a 500/day quota), every other model 18 (public reports say 2.5-flash free tier may be as low as 20/day). HTTP 429 stops the run; 503 moves to the next model. Results cached per model and file, never re-sent. Backend only calls Gemini when `FC_VLM=1`; on any failure the capture keeps its ML Kit fields.

Calls made today: 22 (2.5-flash 5, 3.1-flash-lite 12, 3.5-flash 5 of which 2 timed out at 60 s).

Bake-off, same prompt (`backend/prompts/nameplate.txt`), 5 tune plates at 640 px:

| model | correct | wrong | missed | median latency | in / out tokens |
|---|---|---|---|---|---|
| ML Kit + rules | 6 | 3 | 4 | 71 ms (phone) | - |
| gemini-3.1-flash-lite | 11 | 2 | 0 | 2.8 s | 1334 / 212 |
| gemini-2.5-flash | 10 | 3 | 0 | 2.7 s | 527 / 160 |
| gemini-3.5-flash | 7 (3 files) | 0 | 0 | 17.8 s, 2 timeouts | 1335 / 156 |

Choice: **gemini-3.1-flash-lite** - most accurate here, ~3 s, and the only model with a quota large enough for the eval. 3.5-flash is too slow for a field reply. Gemini 3 models bill ~2.5x the input tokens of 2.5-flash for the same 640 px image (higher default media resolution).

All 12 tune plates (dates scored on the year: day/month order on old plates is ambiguous and only the year feeds appliance age):

| source | correct | wrong | missed | mean conf correct / wrong |
|---|---|---|---|---|
| ML Kit only | 12 | 10 | 11 | 0.56 / 0.30 |
| Gemini only | 30 | 4 | 0 | 0.89 / 0.81 |
| combined (agree / solo / conflict) | 30 | 4 | 0 | 0.66 / 0.42 |

- Gemini's own confidence barely moves when it is wrong (0.81). It invented a "ZP" prefix on a model code cut off at the frame edge and guessed an overstruck serial, both fields labelled unreadable. Agreement with ML Kit is what brings confidence down on those (0.38 and 0.28).
- Combination rule: both agree -> 1-(1-a)(1-b); only one reader has a value -> x0.7; readers disagree -> higher one x0.4. Weights are first guesses, to be fitted on the tune split in Phase 7.
- Fixed along the way: my label for web_np_14 ("4-5-66", US order) was written 1966-05-04, now 1966-04-05; text normalisation now folds umlauts (Buhrer vs Bührer counted wrong before).
- Not run yet (needs approval): 13 eval plates and 100 synthetic variants on 3.1-flash-lite = 113 calls.
