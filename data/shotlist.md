# Phase 1 shot list

Shoot **in this order**, one photo per row. If you skip a row or take an extra shot, just tell me the ID - I check every photo on a contact sheet before renaming.

## Camera settings (Samsung camera, before you start)
- Ratio **9:16** (top bar) - matches the glasses frame.
- **Scene optimizer OFF**, **Night mode / auto night OFF**, flash OFF - glasses have none of these; dark must stay dark.
- Settings -> Advanced picture options -> **High efficiency pictures OFF** (JPEG, not HEIC).
- Location tags OFF (we strip GPS anyway).
- Hold the phone at **eye height**, ~0.5-1 m from the plate, like a head camera. No macro close-ups.
- No people, mirrors, windows with people, family photos or documents in frame.

## Ground truth - read IN PERSON, not off the photo
Note in your Notes app and paste to me; I fill labels.csv:
```
np_01: <make> / <model> / <serial> / <mfg date or 'none'> / <room> / <appliance>
wall_01: <room> / <damage type> / <width> x <height> cm / real or staged
```
Damage types: water stain, mould, crack, peeling paint, hole, swelling. Tape-measure every damage area. Put a credit card next to 3-4 of them (size reference).

| # | ID | What to shoot |
|---|---|---|
| 1 | `np_01_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 2 | `np_01_dark` | Appliance nameplate: SAME spot, lights OFF / curtains shut |
| 3 | `np_01_blur` | Appliance nameplate: SAME spot, move the phone while pressing shutter |
| 4 | `np_02_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 5 | `np_02_dark` | Appliance nameplate: SAME spot, lights OFF / curtains shut |
| 6 | `np_02_blur` | Appliance nameplate: SAME spot, move the phone while pressing shutter |
| 7 | `np_03_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 8 | `np_03_dark` | Appliance nameplate: SAME spot, lights OFF / curtains shut |
| 9 | `np_03_blur` | Appliance nameplate: SAME spot, move the phone while pressing shutter |
| 10 | `np_04_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 11 | `np_04_dark` | Appliance nameplate: SAME spot, lights OFF / curtains shut |
| 12 | `np_04_blur` | Appliance nameplate: SAME spot, move the phone while pressing shutter |
| 13 | `np_05_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 14 | `np_05_dark` | Appliance nameplate: SAME spot, lights OFF / curtains shut |
| 15 | `np_05_angle` | Appliance nameplate: SAME plate from ~45 deg to the side (torch on metal for glare if you can) |
| 16 | `np_06_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 17 | `np_06_dark` | Appliance nameplate: SAME spot, lights OFF / curtains shut |
| 18 | `np_06_angle` | Appliance nameplate: SAME plate from ~45 deg to the side (torch on metal for glare if you can) |
| 19 | `np_07_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 20 | `np_07_dark` | Appliance nameplate: SAME spot, lights OFF / curtains shut |
| 21 | `np_07_angle` | Appliance nameplate: SAME plate from ~45 deg to the side (torch on metal for glare if you can) |
| 22 | `np_08_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 23 | `np_08_angle` | Appliance nameplate: SAME plate from ~45 deg to the side (torch on metal for glare if you can) |
| 24 | `np_09_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 25 | `np_09_far` | Appliance nameplate: step back 2-3 m, plate tiny in frame |
| 26 | `np_10_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 27 | `np_10_cut` | Appliance nameplate: plate/mark half out of the frame edge |
| 28 | `np_11_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 29 | `np_12_good` | Appliance nameplate: normal light, plate filling ~1/3 of frame, steady |
| 30 | `dmg_wall_01_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 31 | `dmg_wall_01_dark` | Wall damage: SAME spot, lights OFF / curtains shut |
| 32 | `dmg_wall_02_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 33 | `dmg_wall_02_dark` | Wall damage: SAME spot, lights OFF / curtains shut |
| 34 | `dmg_wall_03_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 35 | `dmg_wall_03_dark` | Wall damage: SAME spot, lights OFF / curtains shut |
| 36 | `dmg_wall_04_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 37 | `dmg_wall_04_cut` | Wall damage: plate/mark half out of the frame edge |
| 38 | `dmg_wall_05_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 39 | `dmg_wall_05_blur` | Wall damage: SAME spot, move the phone while pressing shutter |
| 40 | `dmg_wall_06_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 41 | `dmg_wall_07_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 42 | `dmg_wall_08_good` | Wall damage: normal light, plate filling ~1/3 of frame, steady |
| 43 | `dmg_ceil_01_good` | Ceiling damage: normal light, plate filling ~1/3 of frame, steady |
| 44 | `dmg_ceil_01_dark` | Ceiling damage: SAME spot, lights OFF / curtains shut |
| 45 | `dmg_ceil_02_good` | Ceiling damage: normal light, plate filling ~1/3 of frame, steady |
| 46 | `dmg_ceil_02_blur` | Ceiling damage: SAME spot, move the phone while pressing shutter |
| 47 | `dmg_ceil_03_good` | Ceiling damage: normal light, plate filling ~1/3 of frame, steady |
| 48 | `dmg_ceil_04_good` | Ceiling damage: normal light, plate filling ~1/3 of frame, steady |
| 49 | `empty_01_floor` | plain floor, nothing useful |
| 50 | `empty_02_blankwall` | blank clean wall |
| 51 | `empty_03_covered` | finger over the lens (black frame) |

## Then: 3 walk-through videos
- Portrait, eye height, 1-2 min each, three different rooms.
- Walk like a technician: stop 3-4 s at each nameplate and damage area.

Counts: nameplate frames 29 (12 plates), low-light 11, damage 19, empty 3, total 51. Fewer plates/marks is fine - minimum 10 plates, 40 frames.
