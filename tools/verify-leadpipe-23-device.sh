#!/usr/bin/env bash
set -euo pipefail

assets=$1
tmp=$(mktemp -d)
pkg=InfinityLoop1309.NewPipeEnhanced.debug.allfeatures
main="$pkg/org.schabi.newpipe.MainActivity"
db="/data/user/0/$pkg/databases/newpipe.db"
prefs="/data/user/0/$pkg/shared_prefs/${pkg}_preferences.xml"
adb() { command adb -s emulator-5580 "$@"; }

(cd "$assets" && sha256sum -c SHA256SUMS)
jq -e '.client_commit == "8038ec97bdd3363da56581a5d5f6f5ac2fd88e40"' "$assets/source-manifest.json"
apks=("$assets"/*-x86_64.apk)
candidate=${apks[0]}
python3 tools/verify-unofficial-apk.py "$candidate" --sdk "$ANDROID_HOME" --abi x86_64
curl --fail --silent --show-error --location --retry 3 \
    'https://github.com/darvilp/PipePipeClient/releases/download/all-features%2Fv5.6.0-unofficial.22/PipePipe-all-features-5.6.0-unofficial.22-466db9b96-20261123T020515Z-x86_64.apk' \
    --output "$tmp/baseline.apk"
(cd "$tmp" && echo '5492e6dbe9cb0118a74d4c36ad0605ffcf4e32d01246777b8139b26428a31963  baseline.apk' | sha256sum -c)

if [[ -c /dev/kvm ]]; then
    if [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
        sudo setfacl -m "u:$USER:rw" /dev/kvm
    fi
    accel=on
else
    export ANDROID_I_WANT_MY_TCG=yes
    accel=off
fi

command adb start-server
avdmanager create avd --name leadpipe-23 --package 'system-images;android-35;default;x86_64' --device pixel_6 </dev/null
"$ANDROID_HOME/emulator/emulator" -avd leadpipe-23 -port 5580 -accel "$accel" \
    -no-window -no-snapshot -no-audio -no-boot-anim -no-metrics -gpu swiftshader_indirect \
    -memory 3072 -cores 2 > "$tmp/emulator.log" 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true; tail -12 "$tmp/emulator.log" | grep -Ev "pubkey|auth_token" || true' EXIT
timeout 720 bash -c 'until [[ $(adb -s emulator-5580 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r") == 1 ]]; do sleep 5; done'
adb root
adb wait-for-device
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$scale" 0
done
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

adb install "$tmp/baseline.apk"
adb shell am start -W -n "$main"
for _ in {1..15}; do
    adb shell test -f "$db" && adb shell test -f "$prefs" && break
    sleep 2
done
adb shell test -f "$db"
adb shell test -f "$prefs"
adb shell am force-stop "$pkg"
adb shell -T "sqlite3 '$db'" <<'SQL'
.bail on
PRAGMA foreign_keys = ON;
INSERT INTO subscriptions (service_id,url,name,subscriber_count,notification_mode)
    VALUES (0,'https://example.invalid/leadpipe-test-channel','LeadPipe .23 Test Channel',42,0);
INSERT INTO playlists (name,thumbnail_url,display_index)
    VALUES ('LeadPipe .23 Test Playlist','',0);
INSERT INTO streams (service_id,url,title,stream_type,duration,uploader,is_paid)
    VALUES (0,'https://example.invalid/leadpipe-test-history','LeadPipe .23 Test History','VIDEO_STREAM',42,'Upgrade fixture',0);
INSERT INTO stream_history (stream_id,access_date,repeat_count)
    SELECT uid,1760878800000,1 FROM streams WHERE url='https://example.invalid/leadpipe-test-history';
SQL
adb exec-out cat "$prefs" > "$tmp/prefs.xml"
python3 - "$tmp/prefs.xml" <<'PY'
import sys
from xml.etree import ElementTree as ET

tree = ET.parse(sys.argv[1])
root = tree.getroot()
def put(tag, key, value):
    for node in root.findall(f"./*[@name='{key}']"):
        root.remove(node)
    node = ET.SubElement(root, tag, {"name": key})
    if tag == "string":
        node.text = value
    else:
        node.set("value", value)

put("int", "version_code", "112901")
put("float", "playback_speed_key", "1.25")
put("float", "playback_pitch_key", "0.9")
put("boolean", "update_app_key", "false")
tree.write(sys.argv[1], encoding="utf-8", xml_declaration=True)
PY
adb shell -T "cat > '$prefs'" < "$tmp/prefs.xml"
echo 'Signed .22 baseline installed and fixtures seeded.'

adb install -r "$candidate"
adb shell dumpsys package "$pkg" | grep -F 'versionName=5.6.0-unofficial.23'
state=$(adb shell -T "sqlite3 '$db'" <<'SQL'
.bail on
PRAGMA user_version;
PRAGMA quick_check;
SELECT name FROM subscriptions WHERE url='https://example.invalid/leadpipe-test-channel';
SELECT name FROM playlists WHERE name='LeadPipe .23 Test Playlist';
SELECT title FROM streams JOIN stream_history ON streams.uid=stream_history.stream_id
  WHERE url='https://example.invalid/leadpipe-test-history';
SQL
)
[[ "$state" == $'903\nok\nLeadPipe .23 Test Channel\nLeadPipe .23 Test Playlist\nLeadPipe .23 Test History' ]]
adb exec-out cat "$prefs" > "$tmp/prefs.xml"
python3 - "$tmp/prefs.xml" <<'PY'
import sys
from xml.etree import ElementTree as ET

prefs = ET.parse(sys.argv[1]).getroot()
for name, value in {"playback_speed_key": "1.25", "playback_pitch_key": "0.9", "update_app_key": "false"}.items():
    assert prefs.find(f"./*[@name='{name}']").get("value") == value, name
PY
echo 'PASS: in-place upgrade retained Room 903, subscription, playlist, history and settings.'

dump_screen() {
    adb shell uiautomator dump /sdcard/leadpipe-test.xml >/dev/null &&
        adb exec-out cat /sdcard/leadpipe-test.xml > "$tmp/screen.xml"
}

wait_for_screen() {
    for _ in {1..8}; do
        if dump_screen && grep -Fq "$1" "$tmp/screen.xml"; then return; fi
        sleep 2
    done
    echo "Missing UI text: $1" >&2
    return 1
}

tap_text() {
    read -r x y < <(python3 - "$tmp/screen.xml" "$1" <<'PY'
import re, sys
from xml.etree import ElementTree as ET
node = ET.parse(sys.argv[1]).find(f".//node[@text='{sys.argv[2]}']")
left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
print((left+right)//2, (top+bottom)//2)
PY
)
    adb shell input tap "$x" "$y"
}

adb shell am start -W -n "$main"
wait_for_screen 'What’s new in LeadPipe'
grep -Fq 'LeadPipe 5.6.0-unofficial.23 is installed' "$tmp/screen.xml"
grep -Fq 'Release notes' "$tmp/screen.xml"
tap_text OK
for _ in {1..10}; do
    if adb exec-out cat "$prefs" | grep -Eq 'name="version_code" value="113001"'; then break; fi
    sleep 1
done
adb exec-out cat "$prefs" | grep -Eq 'name="version_code" value="113001"'
adb shell am force-stop "$pkg"
adb shell am start -W -n "$main"
wait_for_screen "package=\"$pkg\""
if grep -Fq 'What’s new in LeadPipe' "$tmp/screen.xml"; then exit 1; fi
echo 'PASS: LeadPipe upgrade dialog appeared once and exposed version-specific release notes.'

fresh=$(adb shell pm create-user LeadPipeFresh | sed -n 's/.*id \([0-9]*\).*/\1/p')
[[ "$fresh" =~ ^[0-9]+$ ]]
adb shell am start-user -w "$fresh"
adb shell pm install-existing --user "$fresh" "$pkg"
adb shell am switch-user "$fresh"
adb shell wm dismiss-keyguard
adb shell am start -W --user "$fresh" -n "$main"
wait_for_screen 'Welcome to LeadPipe'
grep -Fq 'Enable update alerts' "$tmp/screen.xml"
tap_text 'Not now'
echo 'PASS: independently provisioned Android user saw the LeadPipe welcome and optional update alerts.'
