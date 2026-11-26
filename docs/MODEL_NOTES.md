# Model notes (observed)

## 2026-11-26 - Gemini API smoke test (free tier key from AI Studio)

- Key lists 49 models with generateContent, incl. gemini-2.5-flash, gemini-3.5-flash, gemini-3.8-flash, flash-lite variants.
- `gemini-3.8-flash` -> **HTTP 503 "high demand"** on the first call. Free-tier capacity on the newest model is not reliable -> backend needs retry with backoff + a fallback model list.
- `gemini-2.5-flash` -> OK, 4.2 s, JSON mode (`responseMimeType: application/json`) returned valid JSON.
- Tokens: 48 text + **258 image** (640x480 JPEG) + 61 output + **487 thinking**. Thinking is on by default and was most of the tokens and latency -> try a low/zero thinking budget for extraction and measure accuracy vs latency.
- Synthetic test image (drawn brown blob + blue "card"): model answered `damage_type: "None"` with **confidence 1.0**. Model self-confidence is not trustworthy on its own -> combine with photo quality, format checks and a second signal (Phase 5 plan). Smoke test only, not part of the test set.
