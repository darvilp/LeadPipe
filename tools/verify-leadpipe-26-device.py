import os
import re
import subprocess
import sys
import time
from textwrap import dedent
from pathlib import Path
from xml.etree import ElementTree as ET

ADB = ["adb", "-P", os.environ.get("LEADPIPE_TEST_ADB_PORT", "5037"),
       "-s", "emulator-5584"]
PACKAGE = "InfinityLoop1309.NewPipeEnhanced.debug.allfeatures"
PREFS = f"/data/user/0/{PACKAGE}/shared_prefs/{PACKAGE}_preferences.xml"
DATABASE = f"/data/user/0/{PACKAGE}/databases/newpipe.db"
MAIN = f"{PACKAGE}/org.schabi.newpipe.MainActivity"
SETTINGS = f"{PACKAGE}/org.schabi.newpipe.settings.SettingsActivity"


def adb(*args, data=None):
    return subprocess.run([*ADB, *args], input=data, stdout=subprocess.PIPE,
                          check=True, timeout=200).stdout


def wait_for_boot():
    deadline = time.monotonic() + 480
    while time.monotonic() < deadline:
        try:
            if adb("shell", "getprop", "sys.boot_completed").strip() == b"1":
                break
        except (subprocess.SubprocessError, OSError):
            pass
        time.sleep(5)
    else:
        raise RuntimeError("Disposable Android emulator did not boot")


def sqlite(query):
    return adb("shell", "-T", f"sqlite3 '{DATABASE}'",
               data=dedent(query).encode()).decode().strip()


def prefs():
    return ET.fromstring(adb("exec-out", "cat", PREFS))


def seed(baseline):
    wait_for_boot()
    adb("root")
    adb("wait-for-device")
    for name in ("window_animation_scale", "transition_animation_scale",
                 "animator_duration_scale"):
        adb("shell", "settings", "put", "global", name, "0")
    adb("shell", "wm", "dismiss-keyguard")
    print(adb("install", baseline).decode().strip(), flush=True)
    adb("shell", "am", "start", "-W", "-n", MAIN)
    for _ in range(20):
        if adb("shell", f"test -f '{PREFS}' && test -f '{DATABASE}'; echo $?").strip() == b"0":
            break
        time.sleep(2)
    adb("shell", "am", "force-stop", PACKAGE)
    assert sqlite("PRAGMA user_version;") == "903"
    sqlite("""
    .bail on
    INSERT INTO subscriptions (service_id,url,name,subscriber_count,notification_mode)
      VALUES (0,'https://example.invalid/leadpipe-26-test','Upgrade test subscription',42,0);
    INSERT INTO playlists (name,thumbnail_url,display_index)
      VALUES ('Upgrade test playlist','',0);
    """)
    root = prefs()
    for name, kind, value in [
        ("version_code", "int", "113201"),
        ("playback_speed_key", "float", "1.25"),
        ("update_app_key", "boolean", "false"),
    ]:
        for node in root.findall(f"*[@name='{name}']"):
            root.remove(node)
        ET.SubElement(root, kind, {"name": name, "value": value})
    adb("shell", "-T", f"cat > '{PREFS}'",
        data=ET.tostring(root, encoding="utf-8", xml_declaration=True))
    print("Seeded a disposable installation of signed .25 with Room 903", flush=True)


def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/leadpipe-26-test.xml")
    return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/leadpipe-26-test.xml")).iter("node"))


def find(value, *, exact=True, by="text"):
    for _ in range(6):
        try:
            nodes = screen()
        except (subprocess.SubprocessError, ET.ParseError):
            continue
        for node in nodes:
            text = node.get(by, "")
            if (text.casefold() == value.casefold() if exact else value.casefold() in text.casefold()):
                return node
    raise AssertionError(value)


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap_text(value):
    tap(find(value))


def open_sponsorblock():
    adb("shell", "am", "start", "-W", "-n", SETTINGS)
    for _ in range(3):
        nodes = screen()
        for node in nodes:
            if node.get("text", "").casefold() == "sponsorblock":
                tap(node)
                return
        adb("shell", "input", "swipe", "500", "1850", "500", "650", "320")
    raise AssertionError("Could not find SponsorBlock settings")


def set_server(title, value, replace=""):
    tap_text(title)
    tap(find("android:id/edit", by="resource-id"))
    if replace:
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        adb("shell", "input", "keyevent", *["KEYCODE_DEL"] * len(replace))
    if value:
        adb("shell", "input", "text", value)
    tap_text("OK")


def verify(candidate):
    print(adb("install", "-r", candidate).decode().strip(), flush=True)
    package = adb("shell", "dumpsys", "package", PACKAGE).decode()
    assert "versionName=5.6.0.26" in package and "versionCode=113303" in package
    assert sqlite("""
    PRAGMA user_version;
    PRAGMA quick_check;
    SELECT name FROM subscriptions WHERE url='https://example.invalid/leadpipe-26-test';
    SELECT name FROM playlists WHERE name='Upgrade test playlist';
    """) == "903\nok\nUpgrade test subscription\nUpgrade test playlist"
    assert prefs().find("*[@name='playback_speed_key']").get("value") == "1.25"
    print("PASS .25 → .26 in-place upgrade, Room 903, subscription, playlist and setting", flush=True)
    adb("shell", "am", "start", "-W", "-n", MAIN)
    find("LeadPipe 5.6.0.26", exact=False)
    tap_text("OK")
    open_sponsorblock()
    find("Default: https://sponsor.ajay.app/api/")
    find("Default: https://bsbsb.top/api/")
    youtube = "YouTube SponsorBlock server"
    bilibili = "BiliBili SponsorBlock server"
    url_youtube = "https://youtube.example.test"
    url_bilibili = "https://bilibili.example.test/sb/api"
    set_server(youtube, url_youtube)
    find(url_youtube + "/api/")
    set_server(bilibili, url_bilibili)
    find(url_bilibili + "/")
    set_server(youtube, "http://192.168.1.50:8080", replace=url_youtube)
    find(url_youtube + "/api/")
    assert prefs().find("*[@name='sponsor_block_youtube_api_url']").text == url_youtube
    assert prefs().find("*[@name='sponsor_block_bilibili_api_url']").text == url_bilibili
    set_server(youtube, "", replace=url_youtube)
    find("Default: https://sponsor.ajay.app/api/")
    find(url_bilibili + "/")
    print("PASS signed Android UI defaults, both HTTPS overrides, HTTP rejected, blank reset", flush=True)


if __name__ == "__main__":
    globals()[sys.argv[1]](str(Path(sys.argv[2]).resolve()))
