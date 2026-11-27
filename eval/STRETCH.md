## Stretch D - cost and latency

Prices: gemini-3.1-flash-lite paid tier $0.25 / 1M input tokens, $1.50 / 1M output tokens (thinking billed as output). We ran on the AI Studio free tier, so the billed amount is $0; the dollar figures below are what the same tokens would cost on the paid tier. Measured from `backend/logs/calls.jsonl` (every call logged with tokens and latency).

| call (baseline) | n | cost per call | median latency | p90 latency |
|---|---|---|---|---|
| nameplate (image 640 px + prompt) | 38 | $0.00083 | 4.3 s | 6.0 s |
| damage (image 640 px + prompt) | 17 | $0.00066 | 4.6 s | 5.6 s |
| note (text only) | 12 | $0.00043 | 3.0 s | 3.5 s |

Estimated cost per job for a typical walk (2 nameplates, 5 damage photos, 3 notes): **$0.0062**. On-device work is free: ML Kit OCR median 71 ms, p90 119 ms on the phone (n=125); the quality check runs inside the capture.

Where the time goes (from the timestamps added to every capture: voice command -> quality check -> OCR -> save -> upload -> extraction -> stored): the model call (3-5 s) dominates; the spoken confirmation is given after the local save, so the tech never waits for the network or the model. `tools/cost_latency.py <job ids>` prints the per-stage median/p90 table for real walk-throughs (needs a walk recorded with the final build - see README, Known limitations).

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
