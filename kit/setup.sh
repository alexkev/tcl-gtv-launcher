#!/usr/bin/env bash
# Set up a TCL Google TV: back up, debloat, AdGuard DNS, install the launcher + Plezy, then make the
# launcher Home and disable Google TV's. Everything is a disable; README.md has the undo.
#
# Usage, from the repo root: kit/setup.sh <tv ip> [google photos shared-album link]
set -euo pipefail
cd "$(dirname "$0")/.."
export TV_HOST="${1:?usage: kit/setup.sh <tv ip> [album link]}"
ALBUM="${2:-}"
T=cleanyourtv/tools
PLEZY_URL=https://github.com/edde746/plezy/releases/download/2.24.0/plezy-android-armeabi-v7a.tar.gz
PLEZY_SHA=97e0b7696e4854a762dfff866f03cc4abcd41217715c90d60789323626dd8fae
P(){ $T/venv/bin/python $T/tv.py "$@"; }
step(){ printf '\n== %s\n' "$*"; }

[ -f $T/tv.py ] || git submodule update --init
if [ ! -x $T/venv/bin/python ]; then
  python3 -m venv $T/venv && $T/venv/bin/pip install -q -r $T/requirements.txt
fi

step "Connect (accept the 'Allow USB debugging' prompt on the TV if it appears)"
P "getprop ro.product.brand; getprop ro.product.model; getprop ro.build.version.sdk"
[ "$(P 'getprop ro.build.version.sdk' | tr -d '\r')" -ge 26 ] || { echo "Android below 8 (SDK 26): stop"; exit 1; }

step "Back up to backup/$TV_HOST/ (first run only, so a re-run keeps the stock state)"
B="backup/$TV_HOST"; mkdir -p shots
if [ ! -d "$B" ]; then
mkdir -p "$B"
P "pm list packages -f" > "$B/packages.txt"
P "pm list packages -d" > "$B/disabled.txt"
P "getprop" > "$B/getprop.txt"
for ns in global secure system; do P "settings list $ns" > "$B/settings-$ns.txt"; done
P "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME" > "$B/home-activities.txt"
P "settings get global private_dns_mode; settings get global private_dns_specifier" > "$B/private-dns-before.txt"
fi

step "Debloat (packages this model doesn't have are reported and skipped)"
$T/disable.sh kit/debloat.txt
P --screenshot "shots/$TV_HOST-01-debloated.png"

step "AdGuard Private DNS (undo: settings put global private_dns_mode opportunistic)"
P "settings put global private_dns_mode hostname; settings put global private_dns_specifier dns.adguard-dns.com"

step "Build and install the launcher"
(cd launcher && ./gradlew --no-daemon -q assembleRelease -PalbumUrl="$ALBUM")
P --push launcher/app/build/outputs/apk/release/app-release.apk /data/local/tmp/gtvhome.apk
P "pm install -r /data/local/tmp/gtvhome.apk; pm grant tv.cleanhome.gtv android.permission.READ_TV_LISTINGS; rm /data/local/tmp/gtvhome.apk"

step "Download, check and install Plezy"
C=kit/.cache; mkdir -p $C
[ -f $C/plezy.tar.gz ] || curl -fsSL -o $C/plezy.tar.gz "$PLEZY_URL"
echo "$PLEZY_SHA  $C/plezy.tar.gz" | shasum -a 256 -c
tar xzf $C/plezy.tar.gz -C $C plezy.apk
$T/venv/bin/python kit/push.py $C/plezy.apk /data/local/tmp/plezy.apk
P "pm install -r /data/local/tmp/plezy.apk; rm /data/local/tmp/plezy.apk"

step "Make the launcher Home"
# Android 12 ignores set-home-activity while Google TV's launcher is enabled, so: check the new launcher
# starts, disable the stock one, set Home, and put it all back if Home did not move.
P "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n tv.cleanhome.gtv/.MainActivity" \
  | grep -q 'Status: ok' || { echo "The launcher did not start: Google TV's left as Home"; exit 1; }
# Home guard: setupwraith must not own the saved Wi-Fi network, or the TV drops off Wi-Fi and ADB.
WRAITH=com.google.android.tungsten.setupwraith
P "dumpsys wifi | grep -o 'cname=[^ ]*'" | grep -q setupwraith && { echo "setupwraith owns Wi-Fi: keeping it"; WRAITH=; }
P "pm disable-user --user 0 com.google.android.apps.tv.launcherx; ${WRAITH:+pm disable-user --user 0 $WRAITH;} cmd package set-home-activity --user 0 tv.cleanhome.gtv/.MainActivity; input keyevent KEYCODE_HOME"
sleep 3
if ! P "dumpsys activity activities | grep mFocusedApp" | tee /dev/stderr | grep -q tv.cleanhome.gtv; then
  echo "Home did not move: restoring Google TV's launcher"
  P "pm enable com.google.android.apps.tv.launcherx; pm enable com.google.android.tungsten.setupwraith; cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity"
  exit 1
fi

step "Reboot and confirm"
P "reboot" >/dev/null 2>&1 || true; sleep 90
until P "getprop sys.boot_completed" 2>/dev/null | grep -q 1; do sleep 10; done; sleep 15
P "dumpsys activity activities | grep mFocusedApp"
P --screenshot "shots/$TV_HOST-02-home.png"
echo; echo "Done. Finish by hand: README.md, 'After the script'."
