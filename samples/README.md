# Sample job records

Three real walk-throughs on 2026-11-27, Samsung S22 + DAT Mock Device Kit (phone back camera as the glasses camera), commands spoken through the app's speech path by injection (`tools/walk.sh`: start job, 6 captures, 2 notes, end job). Backend with Gemini extraction on (`FC_VLM=1`). All three validate against `backend/schema/job_record.schema.json`.

| file | job | conditions | build |
|---|---|---|---|
| job_1.json | 7CA40F62 | online, default (baseline) extraction, extraction inside the upload request | before commit f49c700 |
| job_2.json | E8A4BE26 | online, `FC_PROFILE=lean`, extraction after the upload returns | 6a4b55a |
| job_3.json | 175A53BF | **fully offline** (airplane mode, Wi-Fi and data off) for the whole walk, then network on: 8 items uploaded in 0.15 s, record complete 101 s later | 6a4b55a |

What the photos show: the phone was lying on a desk, so the "nameplate" captures read the make "BenQ" from a monitor at low confidence (0.32, one reader only), and every damage photo is correctly classified `none`. The notes are parsed into location / issue / material and drive the line items (SUBF-RR, CAB-DR, DRY-EQ, all `needs_review` because they come from spoken notes).
