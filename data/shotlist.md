# Phase 1 shot list (short version, 42 frames)

Shoot **in this order**, one photo per row. Skipped a row or took an extra? Just tell me the number - I check a contact sheet before renaming.

## Camera settings
- Ratio **9:16**. **Scene optimizer OFF, Night mode OFF, flash OFF.** High efficiency pictures OFF (JPEG).
- Phone at **eye height**, 0.5-1 m away, like a head camera. No macro. No people, mirrors, documents.

| # | ID | What to shoot |
|---|---|---|
| 1 | `np_01_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 2 | `np_01_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 3 | `np_01_blur` | Nameplate: SAME spot, move the phone while pressing shutter |
| 4 | `np_02_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 5 | `np_02_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 6 | `np_02_blur` | Nameplate: SAME spot, move the phone while pressing shutter |
| 7 | `np_03_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 8 | `np_03_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 9 | `np_03_blur` | Nameplate: SAME spot, move the phone while pressing shutter |
| 10 | `np_04_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 11 | `np_04_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 12 | `np_04_blur` | Nameplate: SAME spot, move the phone while pressing shutter |
| 13 | `np_05_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 14 | `np_05_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 15 | `np_05_angle` | Nameplate: SAME plate from ~45 deg to the side (torch glare if you can) |
| 16 | `np_06_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 17 | `np_06_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 18 | `np_06_angle` | Nameplate: SAME plate from ~45 deg to the side (torch glare if you can) |
| 19 | `np_07_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 20 | `np_07_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 21 | `np_07_cut` | Nameplate: half out of the frame edge |
| 22 | `np_08_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 23 | `np_08_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 24 | `np_08_far` | Nameplate: step back 2-3 m, plate tiny |
| 25 | `np_09_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 26 | `np_09_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 27 | `np_10_good` | Nameplate: normal light, steady, plate ~1/3 of frame |
| 28 | `np_10_dark` | Nameplate: SAME spot, lights OFF / curtains shut |
| 29 | `dmg_wall_01_good` | Wall damage: normal light, steady, plate ~1/3 of frame |
| 30 | `dmg_wall_01_dark` | Wall damage: SAME spot, lights OFF / curtains shut |
| 31 | `dmg_wall_02_good` | Wall damage: normal light, steady, plate ~1/3 of frame |
| 32 | `dmg_wall_02_blur` | Wall damage: SAME spot, move the phone while pressing shutter |
| 33 | `dmg_wall_03_good` | Wall damage: normal light, steady, plate ~1/3 of frame |
| 34 | `dmg_wall_03_cut` | Wall damage: half out of the frame edge |
| 35 | `dmg_wall_04_good` | Wall damage: normal light, steady, plate ~1/3 of frame |
| 36 | `dmg_wall_05_good` | Wall damage: normal light, steady, plate ~1/3 of frame |
| 37 | `dmg_ceil_01_good` | Ceiling damage: normal light, steady, plate ~1/3 of frame |
| 38 | `dmg_ceil_01_dark` | Ceiling damage: SAME spot, lights OFF / curtains shut |
| 39 | `dmg_ceil_02_good` | Ceiling damage: normal light, steady, plate ~1/3 of frame |
| 40 | `dmg_ceil_03_good` | Ceiling damage: normal light, steady, plate ~1/3 of frame |
| 41 | `empty_01_floor` | plain floor |
| 42 | `empty_02_covered` | finger over the lens (black) |

## After the 42: ground-truth pass (not part of the test set)
1. Tell me "test shots done" (or note the time).
2. Then take **one sharp close-up of each plate** np_01..np_10, in order, lights on, phone right up to it. I read make/model/serial/date from these at the desk; check any 0/O, 1/I, 8/B, 5/S by eye while you are there.
3. Tape-measure each damage spot. Paste: `wall_01: room / type / W x H cm / real or staged` (types: water stain, mould, crack, peeling paint, hole, swelling).
4. Also note the room + appliance for each plate: `np_01: kitchen / fridge`.

Walk-through videos: later, during the Phase 9 demo recording.

Counts: 42 frames; nameplate frames 28 (10 plates); low-light 13; damage 12.
