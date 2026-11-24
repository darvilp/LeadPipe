#!/usr/bin/env bash
set -euo pipefail

probe_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$(pwd)/docs/wiki/images/subscription-groups"
tmp=$(mktemp -d)
pkg=InfinityLoop1309.NewPipeEnhanced.debug.allfeatures
main="$pkg/org.schabi.newpipe.MainActivity"
db="/data/user/0/$pkg/databases/newpipe.db"
adb() { command adb -s emulator-5580 "$@"; }
screen() { python3 "$probe_dir/screen.py" "$@"; }

mkdir -p "$out"
curl -fsSL --retry 3 'https://github.com/darvilp/PipePipeClient/releases/download/all-features%2Fv5.6.0-unofficial.24/PipePipe-all-features-5.6.0-unofficial.24-0c3636379-20261124T084125Z-x86_64.apk' -o "$tmp/app.apk"
(cd "$tmp" && echo '0904227b747910e11af7a4bc879c9c08d84d52e314de3e2fb8d38e1d639c9956  app.apk' | sha256sum -c)
python3 tools/verify-unofficial-apk.py "$tmp/app.apk" --sdk "$ANDROID_HOME" --abi x86_64

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
export ANDROID_AVD_HOME="$tmp/avd"
mkdir "$ANDROID_AVD_HOME"
avdmanager create avd --name leadpipe-guide --package 'system-images;android-35;default;x86_64' \
    --device pixel_6 --path "$ANDROID_AVD_HOME/leadpipe-guide.avd" </dev/null
"$ANDROID_HOME/emulator/emulator" -avd leadpipe-guide -port 5580 -accel "$accel" \
    -no-window -no-snapshot -no-audio -no-boot-anim -no-metrics -gpu swiftshader_indirect \
    -memory 3072 -cores 2 > "$tmp/emulator.log" 2>&1 &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT

booted=
deadline=$((SECONDS+620))
while (( SECONDS < deadline )); do
    kill -0 "$pid"
    if [[ "$(timeout 8 adb -s emulator-5580 shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" == 1 ]]; then
        booted=1
        break
    fi
    sleep 5
done
[[ "$booted" == 1 ]]
adb root
adb wait-for-device
adb shell wm size 900x1720
adb shell wm density 360
adb shell cmd uimode night no
adb shell settings put system screen_off_timeout 600000
adb shell settings put secure show_ime_with_hard_keyboard 1
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$scale" 0
done
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

adb install "$tmp/app.apk"
adb shell am start -W -n "$main"
for _ in {1..15}; do
    if adb shell test -f "$db"; then break; fi
    sleep 2
done
adb shell test -f "$db"
adb shell am force-stop "$pkg"
adb shell -T "sqlite3 '$db'" < "$probe_dir/demo-feed.sql"
adb shell am start -W -n "$main"

screen tap 'Not now' --require 'Welcome to LeadPipe'
screen tap 'Subscriptions'
screen save "$out/01-channel-groups.png" --require 'Channel groups' --require 'Studio Live'
screen tap 'New' --require 'Channel groups'
screen tap group_name_input --id
screen input Tech
adb shell input keyevent KEYCODE_BACK
screen tap select_channel_button --id
for channel in 'Byte Workshop' 'Circuit Bench' 'Studio Live'; do screen tap "$channel"; done
screen tap confirm_button --id
screen tap content_rules_button --id
screen tap default_content_shorts --id
screen tap default_content_live --id
screen save "$out/02-group-default.png" --require 'Default content'
screen tap 'Add channel exception'
screen tap 'Videos, Live'
screen save "$out/03-exception-types.png" --require 'Add channel exception' --require 'Videos, Live'
screen tap 'OK'
screen tap 'Studio Live'
screen save "$out/04-exception-channel.png" --require 'Studio Live'
screen tap confirm_button --id
screen save "$out/05-saved-rules.png" --require 'Channel exceptions' --require 'Videos, Live'
screen tap confirm_button --id
screen tap confirm_button --id
screen tap Tech --require 'Channel groups'
screen save "$out/06-filtered-feed.png" --require 'Studio Q&A' --require 'What to check before soldering'
screen show > "$tmp/final-screen.txt"
if grep -qE 'Short: three cable tips|Live soldering session' "$tmp/final-screen.txt"; then
    echo 'Filtered items unexpectedly visible'
    exit 1
fi
actual=$(adb shell -T "sqlite3 '$db'" <<'SQL'
SELECT g.name, g.content_selection, s.name, x.content_selection_override
FROM feed_group AS g
JOIN feed_group_subscription_join AS x ON x.group_id=g.uid
JOIN subscriptions AS s ON s.uid=x.subscription_id
ORDER BY s.name;
SQL
)
[[ "$actual" == $'Tech|1|Byte Workshop|\nTech|1|Circuit Bench|\nTech|1|Studio Live|5' ]]
echo 'Verified default Videos and Studio Live exception in the saved group.'
