## Stretch D - cost and latency

Prices: gemini-3.1-flash-lite paid tier $0.25 / 1M input tokens, $1.50 / 1M output tokens (thinking billed as output). We ran on the AI Studio free tier, so the billed amount is $0; the dollar figures below are what the same tokens would cost on the paid tier. Measured from `backend/logs/calls.jsonl` (every call logged with tokens and latency).

| call (baseline) | n | cost per call | median latency | p90 latency |
|---|---|---|---|---|
| nameplate (image 640 px + prompt) | 38 | $0.00083 | 4.3 s | 6.0 s |
| damage (image 640 px + prompt) | 17 | $0.00066 | 4.6 s | 5.6 s |
| note (text only) | 12 | $0.00043 | 3.0 s | 3.5 s |

Estimated cost per job for a typical walk (2 nameplates, 5 damage photos, 3 notes): **$0.0062**. On-device work is free: ML Kit OCR median 71 ms, p90 119 ms on the phone (n=125); the quality check runs inside the capture.

### Real walk-throughs (same 8-item walk each time: 6 captures, 2 notes; `tools/walk.sh`, `tools/cost_latency.py <job>`)

| stage, median / p90 ms | walk 1: baseline | walk 2: lean | walk 3: lean + extraction after upload |
|---|---|---|---|
| voice command -> photo passed quality check | 314 / 329 | 324 / 361 | 323 / 333 |
| on-device OCR + save | 6 / 94 | 3 / 90 | 6 / 72 |
| queue wait before upload | 85 / 130 | 73 / 112 | 92 / 105 |
| upload (phone -> server) | 180 / 185 | 182 / 194 | 183 / 186 |
| phone's upload request held by the server | extraction inside: ~10 000 | extraction inside | **2 / 4** |
| extraction (model call incl. free-tier spacing and retries) | 9 968 / 14 127 | 2 002 / 14 488 | 2 150 / 2 699 |
| **END TO END: voice command -> capture has its fields** | **14 656 / 17 081** | 8 998 / 30 558 | **2 938 / 3 252** |
| model calls / cost per job (paid-tier price) | 7 / **$0.00480** | 8 / **$0.00239** | 6 / $0.00182 |

- **Cost per job halved:** $0.00480 -> $0.00239 (50%) on the same walk with the `lean` profile, and $0.00182 on walk 3 (no Google retries that time).
- **Latency:** walk 2 exposed a design flaw: extraction ran inside the upload request, so a Google 503 retry (20 s) plus our free-tier spacing (13 s) pushed two uploads past the phone's 30 s timeout. Nothing was lost (the backend had stored the capture; the retry got "200 exists"), but the end-to-end p90 hit 30.6 s. Fix: the server stores the photo and answers in 2 ms, extraction runs in the background, finalize waits for pending extractions (and answers 503 so the phone retries if one is still running). Walk 3: end to end 2.9 s median, 3.3 s p90, down from 14.7 s / 17.1 s on walk 1. Part of the walk-to-walk difference is Google's 503 retries (walks 1-2 hit them, walk 3 did not); the causal part is that the phone no longer waits for the model.
- The spoken confirmation is given after the local save (about 0.3 s after the command), so the tech never waits for the network or the model in any walk.
- Offline walk (walk 4, sample job_3): 0 uploads while offline, all 8 items uploaded in 0.15 s when the network returned, record complete 101 s later because the 8 extractions run one at a time, 13 s apart, under the free-tier rate guard; on a paid tier without the guard this would be ~8 x 2-4 s.

**Cost cut (target: half).** Image tokens and thinking tokens were most of the bill, and the answer repeated every value as `raw_text`. A `lean` profile (`FC_PROFILE=lean`) sets `mediaResolution: LOW`, `thinkingLevel: minimal` and drops `raw_text` from the nameplate answer:

| nameplate, eval split (13 plates, 52 fields) | baseline | lean |
|---|---|---|
| input tokens per call | 1346 | 508 |
| output + thinking tokens per call | 329 | 156 |
| cost per call | $0.00083 | **$0.00036 (43%)** |
| fields right | 44/52 | 43/52 |
| serial right | 10/13 | **8/13** |
| mean confidence on wrong answers | 0.41 | 0.44 |
| median latency | 4.3 s | 4.5 s (no gain) |

The cost halves, but low resolution loses two serial numbers and the wrong serials carry more confidence - the kind of regression we must not ship silently. Decision: the default stays `baseline` for nameplates; `lean` is kept as an opt-in. Next step (not run, to save quota): `MEDIA_RESOLUTION_MEDIUM` for nameplates only, and `lean` for damage photos (texture survives low resolution, but damage accuracy under `lean` is not measured yet). Latency was not cut: the call time is dominated by the model, not the image size. To cut latency next we would run extraction off the request path (store first, extract in a background task, finalize waits for pending extractions), which removes the model call from the upload round trip.

## Stretch A - size without depth

Not done. It needs damage photos with a reference object (card, A4, outlet cover) in the same plane and a tape-measured size; none of our test photos has one. The damage extractor already asks for the reference used and caps size confidence at 0.5 without a reference and 0.8 with one.
