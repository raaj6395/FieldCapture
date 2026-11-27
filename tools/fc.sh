#!/bin/sh
for a in "$@"; do adb shell am broadcast -a com.fieldcapture.CMD -p com.fieldcapture --es do "'$a'" >/dev/null; done
