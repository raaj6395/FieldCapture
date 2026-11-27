# Research memo: Meta AI glasses for water-damage field estimation

All findings below are from our own runs of DAT 1.1.0 on the Mock Device Kit (Samsung S22) and our own evaluation; nothing was tested on real glasses. Accuracy figures come from the development set in `eval/REPORT.md` (web photos plus synthetic dark copies) and will be replaced by our own-frame test set.

**1. What the glasses can do today.** A walk can be run with no hands on the phone: voice commands drive capture, and the glasses' speech service keeps working while the session is paused, so "resume" works by voice. Short, fixed commands hold up: 15/20 right in a quiet room, 7/10 next to a fan/TV. Nameplates are readable from a 640x480 still when two readers agree: make 85%, model 77%, serial 77%, date 100% (n=13 plates), and wrong answers carry clearly lower confidence (serial 0.28 vs 0.69), so the back office can tell which fields to check. Capture is robust to the field: the app saves before it speaks, survives force-stop and power loss, and an offline walk uploaded completely 6 s after signal returned with 0 duplicates.

**2. What they cannot do (yet).**
- *Low light:* nameplate field accuracy falls from 85% to 71% and damage type from 56% to 38% on dark frames; confidence halves. In our own phone tests the first prompt in a cupboard is "Too dark".
- *Free text:* notes garble in noise ("water heater leaking at base" -> "what are heated looking at base"); speech-recognition confidence on final results is always 0.0, so it cannot be used to flag bad transcripts.
- *Resolution:* `capturePhoto()` returned the same 640x480 as the stream (the mock ignores `VideoQuality.HIGH` and the frame-rate setting). Small serial digits are where we lose accuracy, and low image resolution cost 2 of 13 serials in our cost test.
- *Size:* there is no depth; a single photo gives a size only with a reference object in the same plane.
- *Damage judgement:* one model call labels damage type at 56%, with confidence ~0.9 whether right or wrong.

**3. SDK behaviour the docs leave out.**
- `powerOff` mid-stream: the session stays `STARTED` and the stream `STREAMING` for 47 s+ while no frames arrive; only a heartbeat log line appears. Apps need their own frame watchdog and must treat `activeDevice == null` as the disconnect signal. A dead session never recovers; create a new one.
- `fold` stops the session immediately and also sets the wearer to doffed; a doffed device cannot host a new session ("No eligible device available"), so reconnect must wait for don.
- After doff/don the Inputs service stays `INACTIVE`; re-adding it while paused fails with a misleading "Session has already been stopped". Re-add on the next `STARTED`.
- Each mock power-on gives the glasses a new device id. `transcriptions` is a StateFlow, so two identical results in a row arrive once. Injected mock speech stopped working after a reconnect in 11 of 13 tries (live mic unaffected).
- Our own spoken replies were picked up as commands ("Resumed." heard as "Resume" 2-4 s later): echo filtering is needed on any glasses with open-ear speakers.

**4. First hardware change: a forward work light co-aimed with the camera.** Low light is the largest single loss we measured that hardware can fix: 14 points on nameplates and 18 points on damage type, plus confidence halving so more fields go to manual review. It is also the first retake prompt a tech hears in a cupboard, attic or under a sink, where these jobs happen. A light switched by the capture command (on for the shot only, to save battery and heat) fixes the input instead of asking the model to guess. Second: a higher-resolution still (at least 12 MP for nameplates); we would ask Meta first whether `capturePhoto()` on real glasses exceeds the stream resolution.

**Questions for Meta:** When do Speech and Inputs leave experimental? Real `capturePhoto()` resolution and latency? At which thermal level does the stream throttle, after how long in a warm attic? Can a session survive a short Bluetooth drop instead of stopping? Custom vocabulary for speech (room names, "nameplate", brands)?
