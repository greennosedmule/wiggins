#!/bin/bash
# Drive the emulator's UI by visible text, for scripted tests without a screen.
#
#   ui.sh dump            list the visible texts and their bounds
#   ui.sh tap "<text>"    tap the first element whose text matches (a grep regex)
#
# Uses uiautomator, so it sees ordinary windows only: a secure window such as the
# PIN prompt doesn't show up (see CLAUDE.md "Emulator").
set -euo pipefail
ADB=${ADB:-adb}
$ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
xml=$($ADB shell cat /sdcard/ui.xml)
case "${1:-}" in
dump)
    echo "$xml" | grep -o 'text="[^"]*"[^>]*bounds="[^"]*"' | grep -v '^text=""' |
        sed -E 's/text="([^"]*)".*bounds="([^"]*)"/\1  \2/'
    ;;
tap)
    bounds=$(echo "$xml" | grep -o "text=\"$2\"[^>]*bounds=\"[^\"]*\"" | head -1 |
        sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/')
    if [ -z "$bounds" ]; then
        echo "no '$2' on screen" >&2
        exit 1
    fi
    set -- $bounds
    $ADB shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
    ;;
*)
    echo "usage: $0 dump | tap <text>" >&2
    exit 2
    ;;
esac
