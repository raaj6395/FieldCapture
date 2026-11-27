# Live session prep

## Walk-through (20 min)
- 2-minute tour with the README diagram: glasses -> JobSession (job state machine outlives SDK sessions) -> on-phone quality check -> save before speaking -> WorkManager upload -> backend extract (ML Kit + Gemini, agreement) -> finalize -> mapping -> schema-validated record.
- Hardest call: **save locally before speaking, and cap retakes at 2 instead of blocking the tech.** A gate that refuses photos gets switched off by a tech in an attic with gloves on; one that keeps the photo marked `low` keeps the walk moving and sends the risk to the back office (line items from low photos become `needs_review`). Second candidate: turning the cut-off check off because it rejected good close-ups.
- Before the call: backend running with `FC_VLM=1`, `adb reverse tcp:8000 tcp:8000`, app installed from the tagged build, mock paired, demo video open as a backup.

## Live change: "capture moisture reading [value] [location]" (target < 20 min)
Files, in order (the same path a note takes, plus one table row):
1. `android/.../CommandParser.kt` - add `data class Moisture(val value: Double, val location: String?) : Command`; add `"moisture"` to KEYWORDS; one TABLE row `^capture moisture(?: reading)? ([\d.]+|<number words>)(?: percent)?(?: (.+))?$`; a small words-to-number helper ("eighteen", "twelve point five").
2. `android/src/test/.../CommandParserTest.kt` - 4 phrases: digits, words, no location, misheard "moisture" variant.
3. `android/.../JobSession.kt` - `handle()`: new branch saving it like a note (`job.notes += Note(..., text = "moisture 18 kitchen wall")`) or a `Reading` list; reply "Moisture 18 percent, kitchen wall." (no command words in replies - the echo filter would eat them).
4. `android/.../UploadWorker.kt` - nothing if stored as a note; else post to `/readings`.
5. `backend/app.py` + `backend/schema/job_record.schema.json` - `readings` array `{value, unit, location, confidence}`.
6. `backend/mapping.py` - rule: moisture > 17% on drywall -> DRY-RR + DRY-EQ; > 25% subfloor -> SUBF-RR (thresholds are assumptions, say so).
7. `backend/test_backend.py` - 2 mapping cases; re-run schema test.
Verify on camera: run both test suites, then inject `say:capture moisture reading 18 kitchen wall` with `tools/fc.sh` and show the record. Afterwards `git stash` so the submitted repo is unchanged.

## Failure drill
- #1 worst: `web_dmg_02` damage_type peeling_paint vs label water_stain at 0.95. Root cause: two damages in one frame and a single-label taxonomy; and damage type has one reader, so confidence never drops. Measurable fix in 20 min: ask for top-2 classes with probabilities, confidence = p1 - p2 margin; re-run `FC_VLM=1 tools/eval.py --split eval --types damage --run` (9 calls, count first) and compare T2/T4 before/after.
- Nameplate case: `web_np_15` model "163555-1" vs "163555-1-43" (field printed in two boxes). Fix: prompt asks for every box of the field; re-run 13 nameplate calls.

## Hardware and research
What breaks first on real glasses:
- Battery and heat during continuous streaming (we already drop to 7 fps on SEVERE thermal; untested on hardware).
- Bluetooth drops in basements/attics with the phone in a pocket: our reconnect + 5 s watchdog handles it; real reconnect times unknown.
- Low light: largest measured accuracy loss (nameplate 85% -> 71%, damage 56% -> 38%).
- Voice in noise (fans, dehumidifiers): commands 7/10, notes garble.
- Framing: head pose is not where the eyes look; nameplates low on appliances need the tech to crouch.

Week one with five techs:
- Day 1: each tech does 3 real jobs with logging on (every capture, retake, disconnect, voice miss is already logged).
- Metrics: usable-frame rate, retakes per job, voice error rate, disconnects per hour, battery % per hour, minutes per job vs their normal process, back-office edits per job record.
- 10-minute debrief daily, fix the top issue each day, compare day 1 vs day 5.

Questions for Meta: when Speech/Inputs leave experimental; real `capturePhoto()` resolution and latency, exposure control or a low-light mode; thermal throttling point and time; which states survive a short BT drop; custom vocabulary for speech; on-glasses compute for the quality check.
