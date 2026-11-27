# SDK notes (observed, not from docs)

Device: Samsung Galaxy S22 (SM-S901E), Android 16 / API 36. DAT 1.1.0, Meta CameraAccess sample, Mock Device Kit.

## 2026-11-26 - first run of CameraAccess on the mock

- Built with command-line tools only (no Android Studio): `./gradlew assembleDebug`, 4m11s cold on an 8 GB M1, APK 96 MB (many bundled native libs).
- **Meta AI app is NOT needed for the mock.** Enabling MockDeviceKit skipped the "Connect my glasses" / Meta AI redirect and the app went straight to "Ready" (registered).
- Mock setup order in the debug panel: Enable MockDeviceKit -> Pair Ray-Ban Meta -> Power, Donned, Unfolded toggles -> Camera source (Front / Back camera / Video file) -> Select image (for photo capture).
- A newly paired mock is powered off, folded and not worn; session will not start until all three are on.
- Back camera as feed: session `started`, stream `streaming` within ~5 s of tapping Preview. In-stream photo capture worked.
- Logcat is flooded by the Samsung camera HAL (`CamX ... Invalid focalLengthRatio: 0`, ~30/s) while the phone camera feeds the mock. Filter logs by our own tag.

## 2026-11-26 - Phase 0 probes (our app, `android/`, mock only)

Probe app: buttons drive the mock + DAT calls, every state change logged with tag `FC`. All results are **mock-only, untested on hardware**.

### Q1 - session lifecycle
- `createSession` -> `start()`: IDLE -> STARTING -> STARTED in <50 ms.
- Cap-touch tap: session PAUSED and stream PAUSED together. Tap again: session STARTED, stream STREAMING ~1.6 s later.
- `stop()`: STOPPING -> STOPPED. A new `createSession` afterwards works (STARTED again, camera streams).
- `AutoDeviceSelector()` throws `WearablesException` if built before `Wearables.initialize()` - create it lazily.
- `session.errors` / `speech.errors` / `inputs.errors` emit `null` once on subscribe - ignore nulls.

### Q2 - what fires on doff / fold / powerOff (during a streaming session)
| Mock action | What we observed | Design impact |
|---|---|---|
| doff | Inputs -> INACTIVE. **Session and stream keep running, frames keep flowing.** | Don't treat doff as disconnect; pause capture prompts. |
| don (after doff) | **Inputs do NOT come back** (stays INACTIVE; injected capture press not delivered). | Re-attach Inputs after don, or rely on voice only. |
| fold | Session -> STOPPED straight away (SDK log: `STOP_ERROR_SESSION_UNAVAILABLE`). Speech/stream torn down. | Terminal: create a new session for the same job. |
| powerOff | `activeDevice` -> null. **Session stays STARTED and stream stays STREAMING, but frames stop.** SDK logs only `HeartbeatMonitor: Heartbeat timeout (5000ms)`. Still "STARTED" 47 s later. After powerOn the dead session does not recover. | **Biggest gap.** Need our own watchdog: no frame for ~3 s or activeDevice == null -> stop session, recreate when device returns. |

### Q3 - Speech in Developer Mode
- `addSpeech()` + `start()` works with app id/token = "0": STARTING -> STARTED.
- Injected text arrives as FINAL with the given confidence (`"capture damage kitchen ceiling" conf=0.9`).
- Live phone-mic ASR (`MockSpeechSource.LIVE_DEVICE_ASR`, backed by Google's on-device recognizer): works, ~0.6 s from last partial to FINAL.
  - "capture nameplate" -> `Capture nameplate`, 2 of 2 correct.
  - "note: water heater leaking at base" -> `Note what are heated looking at base`. Command word fine, free text garbled.
  - Partials report conf=1.0, **FINAL reports conf=0.0** every time -> ASR confidence is useless here; don't gate on it.
  - Output is capitalised, no colon ("Note ..."), so the parser must be case-insensitive and treat "note" as a keyword without punctuation.
  - Implication: commands (short, fixed words) are reliable; note text needs a read-back ("Saved note: ...") and back-office review flag. Phase 3 command eval should use real spoken audio, not just typed strings.
- Decision: **DAT Speech** (works in dev mode; same code path for mock and glasses). Android SpeechRecognizer stays as the fallback if live ASR proves weak.

### Q4 - Inputs in Developer Mode
- `addInputs(InputsConfiguration(consumeBack = false))` -> ACTIVATING -> ACTIVE. Injected capture press arrives as `Capture(pressType=SHORT_PRESS, source=CAPTURE_BUTTON)`.
- Caveat: goes INACTIVE on doff and stays INACTIVE after don (see Q2).

### Q5 - capturePhoto
- Requested `VideoQuality.HIGH` (720x1280) at 15 fps; the phone-camera mock delivered **480x640 YUV at ~30 fps** - the mock ignores both settings.
- `capturePhoto()` returned `PhotoData.HEIC` in ~480 ms, but the bytes are a **640x480 JPEG** with EXIF `Rotate 90 CW` (26 KB). Same pixel count as the stream - no resolution gain from capturePhoto in the mock.
- We must apply EXIF orientation before quality checks/OCR. Real-glasses resolution: unknown (question for Meta).

### Q6 - Meta AI app needed for mock -> no (see above)

### Other
- Debug build of our app: 1m24s with a warm Gradle cache.
- Samsung: a stray Back gesture finishes the activity; the process is then killed fast in the background. The real app needs a foreground service while a job is active.

## 2026-11-27 - Phase 2/3 drills (JobSession on the mock)

Driven with `tools/fc.sh` (adb broadcast -> same calls as the on-screen buttons, works with the screen off). Mock only, untested on hardware.

### Our job vs the SDK session
- One `JobRecord` (IDLE / CONNECTING / ACTIVE / PAUSED / RECONNECTING / STOPPED) outlives any number of `DeviceSession`s. Persisted to `jobs/<id>/session.json` on every transition.
- Drop signals handled: session STOPPED, stream CLOSED / CRITICAL_STREAM_ERROR / HINGE_CLOSED, active device -> null, no frame for 5 s (watchdog).
- Reconnect: drop the dead session (never reuse), wait until the device is back **and unfolded and donned**, new session, backoff 1/2/4/8/15/30 s on failures, never gives up while the job is open.

### Drill results
| Drill | Observed | Result |
|---|---|---|
| start -> pause -> resume -> stop | events logged in order; pause/resume via `session.pause()/resume()` | pass |
| powerOff mid-walk | `activeDevice` -> null at once -> RECONNECTING in <0.1 s (the 5 s watchdog never needed on the mock) | pass |
| powerOn | new session STARTED -> stream STREAMING -> ACTIVE in 0.7 s, same job_id | pass |
| 2 captures, powerOff, powerOn, 1 more | c01-c03 all under job CB778C22 | pass |
| doff | SDK did not pause by itself; we call `pause()` -> PAUSED in <0.1 s, "Glasses off. Paused." | pass |
| don | we `resume()` -> ACTIVE (stream back after ~1.6 s) | pass |
| fold | session STOPPED at once; **mock fold also sets don=DOFFED** | pass |
| unfold (still doffed) | `createSession` OK but start fails: `No eligible device available` -> a doffed device can't host a session. Reconnect now waits for don and says "Put the glasses on to continue." | pass after fix |
| captouch tap / tap | session + stream PAUSED -> PAUSED; tap again -> ACTIVE | pass |
| thermal SEVERE / NONE | metadata thermalLevel updates; we removeCamera + addCamera LOW 7 fps and back to HIGH 15 fps. **Mock still delivers 480x640** (ignores config) | pass (logic only) |
| battery 10% | spoken warning once, re-arms above 20% | pass |
| screen off ~2 min | foreground service (connectedDevice+camera+microphone) + partial wake lock: capture and a powerOff/powerOn recovery both worked with the screen off | pass |
| force-stop / reinstall mid-job | relaunch -> "restored job CB778C22 (10 captures)" -> RECONNECTING -> ACTIVE in 2.7 s | pass |

### SDK / mock gotchas found
- **Inputs after don**: re-adding Inputs while the session is PAUSED fails with a misleading `Session has already been stopped`. Re-add on the next STARTED instead (removeInputs + addInputs) -> capture button works again.
- **Speech keeps working while the session is PAUSED** -> voice "resume" works; so pause can be a real `session.pause()`.
- **Injected speech dies after a reconnect (mock bug?)**: on a second DAT session in the same process, `simulateTranscription` reports `isListening=true`, our new Speech is STARTED, but no transcription arrives. Failed on 11 of 13 reconnects tested; `speech.stop()/start()` and `removeSpeech()+addSpeech()` don't fix it; only an app restart does. Live phone ASR after reconnect: TODO (needs a human voice).
- `transcriptions` is a StateFlow: two identical injected results in a row arrive once. Live ASR is unaffected (partials in between).
- Mock powerOn gives the glasses a **new device identifier** each time.

### Voice layer
- Final transcripts only; parser is pure Kotlin (38 phrases in `CommandParserTest`).
- **TTS echo**: in the mock the phone mic hears the phone speaker. "Resumed." came back as `Resume` 2.2-3.5 s later, longer than any sane mute window. Fix: mute while speaking + 0.5 s, and drop a final transcript that is contained in what we said in the last 6 s. Real glasses: speaker is centimetres from the mic, so the same filter is needed.
- Too long a mute drops real commands (the e2e test caught this: command 2.5 s after "Ready." was dropped with a 1.5 s mute).
- **Live recognition measured (one speaker, phone mic via `LIVE_DEVICE_ASR`, S22, about 1 m, normal pace).** Score = first attempt gives the right command and the right room/surface (for notes, the command only).
  | Condition | Commands right | Note text exact | Notes |
  |---|---|---|---|
  | Quiet room, 20 commands (before fixes) | **15/20** | 3/4 | "capture nameplate" 2/4 ("name please", "Captain nameplate", "name hit"); "capture" misheard as "Captain"/"Capital" 4x; "basement subfloor" -> "basements of floor" |
  | Fan/TV on, 10 commands (after fixes) | **7/10** | 0/2 | "water stain" -> "Water Street"; "drywall soft behind the toilet" -> "drywall behind soft wallet"; "garage wall" -> "garage volume"; "Note" heard as "No" |
  - Commands (short, fixed words) hold up in noise; **free-text notes don't** -> notes are read back ("Noted: ...") and must be flagged for back-office review.
  - ASR confidence on FINAL is always 0.0 -> useless as a signal (confirms Phase 0).
  - Bugs found in the quiet run and fixed before the noisy run: (1) the reply "Paused. Say resume." contained the next command, so the echo filter ate the tech's real "resume" 3 times (echo arrives 2.8-4.5 s after our reply, a human answers 4-6 s after -> timing can't separate them; replies now never contain command words); (2) "Capture damage living" took the *last* room (bathroom) silently -> last room is only used when nothing else was said; (3) parser fixes "captain/capital" -> capture, "name please" -> nameplate, "basements" -> basement. These fixes were tuned on the quiet run, so the quiet 15/20 is the pre-fix number; the noisy 7/10 first-attempt score does not depend on them (one retry did: "Captain nameplate").
- e2e instrumentation test (`VoiceE2ETest`): injected "capture nameplate", "Capture damage, kitchen, north wall.", a note, "capture damage ceiling" (room filled from last), chatter -> 3 captures + 1 note under one job. **Pass.**

### README draft - audio path
Real glasses: glasses mic -> on-glasses ASR -> DAT Speech -> app. Mock: phone mic -> Android on-device recogniser (`LIVE_DEVICE_ASR`) -> DAT Speech -> app. Same app code; the mock has no microphone of its own, so in the demo the voice physically goes into the phone. Replies use Android TextToSpeech (on glasses: BT audio to the glasses speakers). Capture button (DAT Inputs) repeats the last capture as a glove/noise backup.

### What the mock cannot simulate
Real battery drain curve and charge-while-recording, real heat build-up and the throttling the glasses apply themselves, Bluetooth range / interference / slow reconnects, glasses camera optics, exposure and low-light noise (the mock uses the phone camera), real glasses-mic audio in wind/noise, speaker-to-mic echo on the glasses, firmware-initiated session stops, multiple glasses nearby.

## 2026-11-27 - Phase 4 quality gate tuning

Data: 25 web nameplates and 18 web damage photos (`data/web/labels.csv`, CC/PD from Wikimedia Commons, provenance per row), 151 synthetic variants of them (`tools/synth.py`: dark, blurry, glare, cut_off; each variant keeps its parent's split), and 8 of the user's own phone photos with a visual sharp/blurry label (`data/real_quality.csv`). Web, synthetic and real are reported separately. No real glasses frames yet, so `thresholds.json` stays `provisional`.

Method (`tools/tune_thresholds.py --write`): per check, pick the threshold on the tune split that catches the most synthetic failures while rejecting at most 10% of good originals, then take the middle of the gap between classes (geometric middle for blur, since Laplacian variance scales multiplicatively). `edge_e` is not tuned because the Kotlin/Python parity fixtures depend on it.

| check | value | tune: catches / false-rejects |
|---|---|---|
| dark_mean | 45.0 | 100% / 0% |
| dark_frac | off (1.0) | black plates (Miele, Sony, Luftwerk) are legitimately mostly dark; dark_mean alone already catches every dark variant |
| nameplate blur | 70.7 | 100% / 0% |
| damage blur | 5.6 | 100% / 0% |
| nameplate glare_frac | 0.32 | 100% / 0% |
| nameplate cut_off | off (null) | best fit caught 58% but rejected 3/13 good eval plates and the user's sharp Razer label: text touching the frame edge is also what a well-filled close-up looks like, and a false reject ends in a "low" capture whose line items all go to review |

End-to-end with shipped thresholds:

| set | good passed | dark | blurry | glare | cut_off |
|---|---|---|---|---|---|
| web nameplate, tune | 12/12 | 12/12 | 12/12 | 12/12 | 0/12 (off) |
| web nameplate, eval | 12/13 (Luftwerk black plate -> dark) | 13/13 | 13/13 | 12/13 | 3/13 flagged for other reasons |
| web damage, tune | 9/9 | 9/9 | 9/9 | 0/9 (not checked for damage) | - |
| web damage, eval | 9/9 | 8/8 | 8/8 | 0/8 | - |
| user phone photos | 5/5 | - | 2/3 (dim out-of-focus logo passes at blur 94) | - | - |

Gotchas:
- The first synthetic blur was too strong: every blurred variant scored <= 20 and every good photo >= 245, so any value in that gap fit the web data. The arithmetic middle (132) would have rejected the user's sharp Razer label (120). Real photos put the boundary between 94 and 117; the geometric middle (71) sits inside it.
- Wikimedia only serves standard thumbnail widths (330, 1280...); 320px returned 404 HTML that was saved as .jpg. `fetch_web.py` now rejects non-image bytes.
- Excluded from the web set on purpose: faces, mugshots, police sketches, Nazi insignia, a memorial plate with a person's name and death date, and FBI evidence photos (Epstein Files Transparency Act release).

Still needed: real frames from glasses (or the mock camera) for blur and glare, since phone and web photos are sharper than a 7-15 fps head-mounted stream; then rerun the tuner on those and drop `provisional`.

## 2026-11-27 - Phase 6 offline drill (phone RZCWA0ERVFA, mock glasses, backend on laptop via `adb reverse tcp:8000 tcp:8000`)

| step | result |
|---|---|
| airplane mode on | Samsung kept Wi-Fi connected in airplane mode; also needed `svc wifi disable` + `svc data disable`. Verified "Active default network: none", ping unreachable |
| start job 83790B46, 3 captures + 2 notes by injected speech | all saved to disk with spoken confirmation; no upload attempted (WorkManager waits on CONNECTED) |
| force-stop with 6 items unsent | process gone, no uploaded.txt |
| reopen | "restored job 83790B46 (3 captures, 2 notes) -> RECONNECTING", streaming again in 1.7 s; injected speech worked (fresh process) |
| 1 more capture, end job | c04 saved, "Job ended. 4 photos, 2 notes." |
| network back on | all 6 items 201 + finalize 200 **6 s** after re-enabling |
| backend check | job_record.json status complete, missing [], 0 schema errors, 0 duplicate ids; events start, connected, disconnect, reconnect, stop; max upload delay 127 s (offline time) |
| replay: delete phone markers, relaunch | every item re-sent -> 200 "exists", store still 4 captures + 2 notes, record unchanged -> crash between POST and marker is safe |

Side result: on first launch (before network was off) the worker flushed 3 older stopped jobs that were still queued from earlier sessions (A963C600, 5F08C2E2, 005D1978), all finalized.

Damage captures map to INSP-REV (needs_review) because there is no extraction yet; Phase 5 fills damage_type/size.
