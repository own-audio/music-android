#!/usr/bin/env python3
"""Takes the Google Play screenshots on an emulator, in English and Czech, from the public demo library.

Drives the play debug build with adb: clears its data, signs in with Try the Demo Library, plays
The Run and captures Home, the albums, the album, the player, search and (phone) the widgets on
the home screen. Buttons are found by their text, read from the app's own strings, so the same
steps work in both languages. The device language is switched with `cmd locale` and put back
afterwards; the status bar is in demo mode (9:41, full battery, Wi-Fi) while it runs.

Raw captures go to build/store-screenshots/<locale>/<device>/; tools/store-graphics.py frames
them into fastlane/metadata/android/<locale>/images/.

Before the first phone run, add the own.music Now Playing and Recently Played widgets to the
emulator's first home screen by hand (long-press the home screen, Widgets): they stay there.

  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ANDROID_SERIAL=emulator-5556 ./gradlew :app:installPlayDebug
  tools/store-screenshots.py emulator-5556 phone            # also: seven, ten
  tools/store-screenshots.py emulator-5556 phone cs-CZ      # one language only
"""
import pathlib, re, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKAGE = "audio.own.musicplayer"
ALBUM, QUERY = "The Run", "lee"
STRINGS = [ROOT / "app/src/main/res", ROOT / "core/strings/src/main/res"]

if len(sys.argv) < 3 or sys.argv[2] not in ("phone", "seven", "ten"):
    sys.exit(__doc__)
SERIAL, DEVICE = sys.argv[1], sys.argv[2]
LOCALES = sys.argv[3:] or ["en-US", "cs-CZ"]


def adb(*args, check=True):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, check=check).stdout


def shell(command):
    return adb("shell", command).decode()


def string(name, locale):
    folder = "values" if locale.startswith("en") else f"values-{locale.split('-')[0]}"
    for res in STRINGS:
        path = res / folder / "strings.xml"
        if path.exists():
            for element in ET.parse(path).getroot().iter("string"):
                if element.get("name") == name:
                    return (element.text or "").replace("\\'", "'")
    sys.exit(f"no string {name} for {locale}")


def nodes():
    shell("uiautomator dump /sdcard/store-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/store-ui.xml").decode()
    found = []
    for node in re.findall(r"<node [^>]*>", xml):
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, bounds.groups())
        for key in ("text", "content-desc"):
            value = re.search(key + r'="([^"]*)"', node)
            if value and value.group(1):
                found.append((value.group(1).replace("&amp;", "&"), (x1 + x2) // 2, (y1 + y2) // 2))
    return found


def size():
    width, height = re.search(r"(\d+)x(\d+)", shell("wm size").splitlines()[-1]).groups()
    return int(width), int(height)


def find(text, index=0, scroll=False, wait=20):
    deadline = time.time() + wait
    while time.time() < deadline:
        hits = [(x, y) for value, x, y in nodes() if value == text]
        if len(hits) > index:
            return hits[index]
        if scroll:
            width, height = size()
            shell(f"input swipe {width // 2} {height * 2 // 3} {width // 2} {height // 3} 400")
        time.sleep(1)
    sys.exit(f"not on screen: {text}")


def tap(text, index=0, scroll=False, wait=20):
    x, y = find(text, index, scroll, wait)
    shell(f"input tap {x} {y}")
    time.sleep(1.5)


def scroll_to_top():
    width, height = size()
    for _ in range(3):
        shell(f"input swipe {width // 2} {height // 3} {width // 2} {height * 5 // 6} 200")
    time.sleep(1)


def capture(folder, name):
    time.sleep(1.5)
    (folder / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))
    print(f"  {name}")


def demo_mode(on):
    def demo(*args):
        shell("am broadcast -a com.android.systemui.demo -e command " + " ".join(args))
    if not on:
        demo("exit")
        return
    shell("settings put global sysui_demo_allowed 1")
    demo("enter")
    demo("clock", "-e", "hhmm", "0941")
    demo("battery", "-e", "level", "100", "-e", "plugged", "false")
    demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
    demo("network", "-e", "mobile", "hide")
    demo("notifications", "-e", "visible", "false")


def run(locale):
    s = lambda name: string(name, locale)
    folder = ROOT / "build/store-screenshots" / locale / DEVICE
    folder.mkdir(parents=True, exist_ok=True)
    print(f"== {DEVICE}, {locale}")
    shell(f"cmd locale set-device-locale {locale}")
    time.sleep(3)
    demo_mode(True)
    shell(f"pm clear {PACKAGE}")
    shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
    shell("cmd uimode night no")
    shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1")
    tap(s("try_the_demo"), scroll=True)
    find(s("tab_home"), wait=60)
    time.sleep(4)  # the covers

    if DEVICE == "phone":
        phone(s, folder)
    else:
        tablet(s, folder)


def open_player(folder):
    # The mini player: the last "Seeker" on the album page.
    titles = [(x, y) for value, x, y in nodes() if value == "Seeker"]
    x, y = titles[-1]
    shell(f"input tap {x} {y}")
    time.sleep(3)
    capture(folder, "player")


def play_album(s):
    tap(s("play"))
    shell("cmd media_session volume --stream 3 --set 0")
    time.sleep(4)


def search(s, folder):
    tap(s("search"))
    shell(f"input text {QUERY}")
    time.sleep(3)
    shell("input keyevent 4")  # the keyboard away
    capture(folder, "search")


def phone(s, folder):
    """With The Run playing, so the mini player shows on every page."""
    tap(s("library"))
    tap(s("albums"))
    tap(ALBUM, scroll=True)
    play_album(s)
    capture(folder, "album")
    open_player(folder)
    tap(s("close_player"))
    shell("input keyevent 4")  # back to the albums
    time.sleep(1.5)
    scroll_to_top()
    capture(folder, "albums")
    tap(s("tab_home"))
    scroll_to_top()
    capture(folder, "home")
    search(s, folder)
    shell("input keyevent 3")
    time.sleep(4)
    capture(folder, "widgets")


def tablet(s, folder):
    """Pages before anything plays: beside the rail, the mini player sits under the gesture
    handle (it does not pad for the navigation bar), so it is kept out of these pictures."""
    capture(folder, "home")
    search(s, folder)
    tap(s("library"))
    tap(s("albums"))
    capture(folder, "albums")
    tap(ALBUM, scroll=True)
    capture(folder, "album")
    play_album(s)
    open_player(folder)


previous = shell("cmd locale get-device-locale").strip()
try:
    for locale in LOCALES:
        run(locale)
finally:
    demo_mode(False)
    shell(f"cmd locale set-device-locale {previous or 'en-US'}")
print(f"raw screenshots in {ROOT / 'build/store-screenshots'}")
