#!/bin/sh
cd "$(dirname "$0")/.."
adb shell am force-stop com.fieldcapture
adb logcat -c
adb shell am start -n com.fieldcapture/.MainActivity >/dev/null
sleep 6
tools/fc.sh start
sleep 6
for c in "capture nameplate" "capture damage kitchen north wall" "capture damage kitchen ceiling" "note subfloor soft by the cabinet" \
         "capture nameplate" "capture damage bathroom floor" "note water heater leaking at the base" "capture damage hallway wall"; do
  tools/fc.sh "say:$c"
  sleep 15
done
tools/fc.sh "say:end job"
sleep 5
adb logcat -d -s FC | grep -o "JOB [0-9A-F]\{8\}: IDLE -> CONNECTING" | tail -1 | cut -d' ' -f2 | tr -d ':'
