#!/usr/bin/env bash
# CPA-Android on-device smoke test.
#
# Verifies the runtime contracts that cannot be proven from the host alone:
#   C1  the APK installs and the foreground service keeps the Go kernel alive
#   C2  127.0.0.1:8317 really listens inside the device (via adb forward)
#   C3  filesDir/config.yaml is the hardened v8 template, filesDir/auths exists
#   C4  the bundled management panel answers and the local password is accepted
#   C5  the Antigravity auth URL is issued with the localhost:51121 redirect
#   C6  the :51121 redirect is captured by the kernel (zero copy/paste loop)
#   D1  editing config.yaml on the device hot-reloads without a restart
#   D2  2.0 defaults: hot update off, panel sanitised, SPA mounts, screens registered
#   D3  two streaming turns each terminate and the model list survives them
#
# Usage: scripts/device-smoke.sh [serial]
set -uo pipefail

ADB=${ADB:-adb}
SERIAL=${1:-}
PKG=me.routerfor.cpa
# Two different secrets, and conflating them hid a real defect: the management key opens
# /v8/management/* but a client (SillyTavern, curl) needs the key from access.api-keys, which is
# randomly generated at first start. This script used the management key for both, so the home
# screen once showed the wrong key and nothing here noticed.
MGMT_KEY=cpa-android-local
LOCAL_PORT=18317

if [ -n "$SERIAL" ]; then
  ADB="$ADB -s $SERIAL"
fi

pass=0; fail=0
ok()   { printf "  \033[32mPASS\033[0m %s\n" "$1"; pass=$((pass+1)); }
bad()  { printf "  \033[31mFAIL\033[0m %s\n" "$1"; fail=$((fail+1)); }
note() { printf "  ..   %s\n" "$1"; }

echo "== device =="
$ADB wait-for-device
$ADB shell getprop ro.product.model | tr -d "\r"
SDK=$($ADB shell getprop ro.build.version.sdk | tr -d "\r")
note "API level $SDK"

echo "== install =="
APK=$(ls -1 app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | head -1)
[ -n "$APK" ] || { echo "app-debug.apk missing; run ./gradlew assembleDebug first"; exit 1; }
$ADB install -r -t "$APK" 2>&1 | tail -2

echo "== C1 launch + foreground service =="
$ADB logcat -c 2>/dev/null
$ADB shell am force-stop $PKG
$ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 8
if $ADB shell pidof $PKG >/dev/null 2>&1; then ok "process alive"; else bad "process not running"; fi
RUNNING=$($ADB shell dumpsys activity services $PKG 2>/dev/null | grep -c "$PKG/.ProxyForegroundService")
[ "${RUNNING:-0}" -ge 1 ] && ok "ProxyForegroundService started" || bad "ProxyForegroundService not found"
WAKE=$($ADB shell dumpsys power 2>/dev/null | grep -c "$PKG:gateway")
[ "${WAKE:-0}" -ge 1 ] && ok "PARTIAL_WAKE_LOCK held" || bad "wake lock not held"

echo "== C2 kernel listening inside the device =="
$ADB forward --remove tcp:$LOCAL_PORT >/dev/null 2>&1
$ADB forward tcp:$LOCAL_PORT tcp:8317 >/dev/null 2>&1
for i in $(seq 1 30); do
  if curl -s -m 2 -o /dev/null "http://127.0.0.1:$LOCAL_PORT/management.html"; then break; fi
  sleep 1
done
curl -s -m 5 -o /dev/null -w "%{http_code}" "http://127.0.0.1:$LOCAL_PORT/management.html" | grep -q 200 \
  && ok "8317 reachable through adb forward" || bad "8317 not reachable"

echo "== C3 seeded files =="
CFG=$($ADB shell run-as $PKG cat files/config.yaml 2>/dev/null | tr -d "\r")
[ -n "$CFG" ] && ok "filesDir/config.yaml exists ($(printf %s "$CFG" | wc -l) lines)" || bad "config.yaml missing"
for needle in \
  "config-version: 8" \
  "signature-cache-enabled: true" \
  "antigravity-credits: true" \
  "keepalive-seconds: 15" \
  "send-keepalive-before-ft: true"; do
  printf %s "$CFG" | grep -q "$needle" && ok "config contains $needle" || bad "config missing $needle"
done
printf %s "$CFG" | grep -q "Claude Agent SDK" && ok "sensitive-words seeded" || bad "sensitive-words missing"
$ADB shell run-as $PKG ls files/auths >/dev/null 2>&1 && ok "filesDir/auths exists" || bad "auths dir missing"

echo "== C4 management panel =="
UNAUTH=$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://127.0.0.1:$LOCAL_PORT/v8/management/config")
[ "$UNAUTH" = "401" ] || [ "$UNAUTH" = "403" ] && ok "management API rejects anonymous ($UNAUTH)" \
  || note "anonymous status $UNAUTH"
BODY=$(curl -s -m 8 -H "Authorization: Bearer $MGMT_KEY" "http://127.0.0.1:$LOCAL_PORT/v8/management/config")
printf %s "$BODY" | grep -q antigravity && ok "v8 config served with the local key" || bad "authenticated config fetch failed"

# The key a client must present to /v1 is access.api-keys, not the management key. They were one
# constant until api-keys became random, and the home screen kept showing the management key: the
# user copied it into a client and got a 401 that looked like a broken gateway.
CLIENT_KEY=$(printf %s "$BODY" | sed -n 's/.*"api-keys":\[ *"\([^"]*\)".*/\1/p' | head -1)
if [ -n "$CLIENT_KEY" ]; then
  note "client api key: $CLIENT_KEY"
  CODE=$(curl -s -m 8 -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $CLIENT_KEY" \
    "http://127.0.0.1:$LOCAL_PORT/v1/models")
  [ "$CODE" = "200" ] && ok "/v1 accepts the client api key" || bad "/v1 rejected the client api key ($CODE)"
  CODE=$(curl -s -m 8 -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $MGMT_KEY" \
    "http://127.0.0.1:$LOCAL_PORT/v1/models")
  [ "$CODE" = "401" ] && ok "the management key is not a client key ($CODE)" \
    || note "management key also opens /v1 ($CODE) - config may still carry the legacy shared key"
else
  bad "could not read access.api-keys out of the served config"
fi

echo "== C5 OAuth auth-url =="
AUTH=$(curl -s -m 15 -H "Authorization: Bearer $MGMT_KEY" \
  "http://127.0.0.1:$LOCAL_PORT/v8/management/oauth/auth-url?provider=antigravity&is_webui=1")
printf %s "$AUTH" | grep -q "accounts.google.com" && ok "consent URL issued" || bad "no consent URL: ${AUTH:0:160}"
STATE=$(printf %s "$AUTH" | sed -n 's/.*"state":"\([^"]*\)".*/\1/p')
printf %s "$AUTH" | grep -qE "localhost(:|%3A)51121" && ok "redirect_uri targets localhost:51121" \
  || bad "redirect_uri is not localhost:51121"
note "state=$STATE"

echo "== C6 zero-paste callback capture =="
if [ -n "$STATE" ]; then
  $ADB forward --remove tcp:51121 >/dev/null 2>&1
  $ADB forward tcp:51121 tcp:51121 >/dev/null 2>&1
  sleep 2
  CODE=$(curl -s -m 8 -o /dev/null -w "%{http_code}" \
    "http://127.0.0.1:51121/oauth-callback?code=fake-code&state=$STATE")
  [ "$CODE" = "302" ] && ok "forwarder relayed the redirect (302)" || note "forwarder returned $CODE"
  sleep 2
  STATUS=$(curl -s -m 8 -H "Authorization: Bearer $MGMT_KEY" \
    "http://127.0.0.1:$LOCAL_PORT/v8/management/oauth/status?state=$STATE")
  note "session status: ${STATUS:0:160}"
  printf %s "$STATUS" | grep -qE '"status":"(wait|error|ok)"' \
    && ok "kernel consumed the callback (session moved)" || bad "callback not consumed"
  $ADB forward --remove tcp:51121 >/dev/null 2>&1
else
  bad "no state, cannot exercise the callback"
fi

echo "== D1 config hot reload =="
CFG_TMP=$(mktemp /tmp/cpa-cfg.XXXXXX.yaml)
$ADB shell run-as $PKG cat files/config.yaml > "$CFG_TMP" 2>/dev/null
BEFORE_VAL=$(sed -n "s/^ *keepalive-seconds: *\([0-9][0-9]*\).*/\1/p" "$CFG_TMP" | head -1)
if [ "${BEFORE_VAL:-0}" = "15" ]; then TARGET=25; else TARGET=15; fi
note "keepalive on device: ${BEFORE_VAL:-unknown} -> target $TARGET"
sed -i.bak "s/^\( *keepalive-seconds: *\)[0-9][0-9]*/\1$TARGET/" "$CFG_TMP"
RELOADS_BEFORE=$($ADB shell run-as $PKG grep -c "config successfully reloaded" files/cpa-engine.log 2>/dev/null | tr -d "\r" | sed "s/.*://" | tr -dc "0-9")
$ADB push "$CFG_TMP" /data/local/tmp/cpa-cfg.yaml >/dev/null 2>&1
$ADB shell chmod 644 /data/local/tmp/cpa-cfg.yaml
$ADB shell run-as $PKG cp /data/local/tmp/cpa-cfg.yaml files/config.yaml 2>/dev/null
sleep 6
AFTER=$(curl -s -m 8 -H "Authorization: Bearer $MGMT_KEY" "http://127.0.0.1:$LOCAL_PORT/v8/management/config" | grep -o '"keepalive-seconds":[0-9]*' | head -1)
note "engine now reports: ${AFTER:-unknown}"
printf %s "$AFTER" | grep -q ":$TARGET" && ok "engine hot-reloaded keepalive ${BEFORE_VAL} -> $TARGET without a restart" || bad "hot reload did not apply (want $TARGET)"
RELOADS_AFTER=$($ADB shell run-as $PKG grep -c "config successfully reloaded" files/cpa-engine.log 2>/dev/null | tr -d "\r" | sed "s/.*://" | tr -dc "0-9")
[ "${RELOADS_AFTER:-0}" -gt "${RELOADS_BEFORE:-0}" ] && ok "watcher logged a new reload (${RELOADS_BEFORE:-0} -> ${RELOADS_AFTER:-0})" || bad "no new reload line in the engine log"
sed -i.bak2 "s/^\( *keepalive-seconds: *\)[0-9][0-9]*/\1${BEFORE_VAL:-15}/" "$CFG_TMP"
$ADB push "$CFG_TMP" /data/local/tmp/cpa-cfg.yaml >/dev/null 2>&1
$ADB shell run-as $PKG cp /data/local/tmp/cpa-cfg.yaml files/config.yaml 2>/dev/null
rm -f "$CFG_TMP" "$CFG_TMP.bak" "$CFG_TMP.bak2"

echo "== D2 hot update defaults to OFF =="
# This check used to assert on the leftover preference. That made it fail on any device where
# somebody had legitimately turned hot update ON - it was measuring the previous run, not the
# code. It now establishes its own precondition: force-stop, clear the staged kernel, force the
# pref off, restart, and only then assert what the loader did.
$ADB shell am force-stop $PKG >/dev/null 2>&1
$ADB shell run-as $PKG rm -rf app_hotlibs >/dev/null 2>&1
PREFS_TMP=$(mktemp)
$ADB shell run-as $PKG cat shared_prefs/cpa-prefs.xml 2>/dev/null | tr -d "\r" > "$PREFS_TMP"
sed -i.bak 's/name="hot-update-enabled" value="true"/name="hot-update-enabled" value="false"/' "$PREFS_TMP"
$ADB push "$PREFS_TMP" /data/local/tmp/cpa-prefs.xml >/dev/null 2>&1
$ADB shell run-as $PKG cp /data/local/tmp/cpa-prefs.xml shared_prefs/cpa-prefs.xml 2>/dev/null
rm -f "$PREFS_TMP" "$PREFS_TMP.bak"
RAW=$($ADB shell run-as $PKG cat shared_prefs/cpa-prefs.xml 2>/dev/null | tr -d "\r")
HOT=$(printf %s "$RAW" | sed -n 's/.*name="hot-update-enabled" value="\([a-z]*\)".*/\1/p')
note "hot-update-enabled after reset: ${HOT:-unset}"
case "${HOT:-false}" in
  false) ok "hot update reads OFF" ;;
  *)     bad "could not reset hot-update-enabled (got ${HOT:-unset})" ;;
esac
$ADB logcat -c 2>/dev/null
$ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 8
$ADB logcat -d -s CpaHotSo:* 2>/dev/null | grep -q "hot update disabled, loaded packaged kernel" \
  && ok "loader took the packaged-kernel branch" || bad "packaged-kernel branch never logged"
STAGED=$($ADB shell run-as $PKG ls app_hotlibs 2>/dev/null | tr -d "\r" | grep -c libgojni)
[ "${STAGED:-0}" -eq 0 ] && ok "nothing staged while hot update is off" \
  || bad "a staged .so survived with hot update off"

echo "== D3 two streaming turns keep the model list =="
# The regression this covers: a second turn used to hang (no bound before Do() returned) and a
# single non-quota failure on the only credential used to empty /v1/models until a restart.
# Both are observable from here without touching the panel. Upstream availability is not this
# app's contract, so a round that never reaches the provider is a note, not a failure; what is
# asserted is that each round terminates on its own and the model list is still there after it.
MODELS_BEFORE=$(curl -s -m 10 -H "Authorization: Bearer $CLIENT_KEY" \
  "http://127.0.0.1:$LOCAL_PORT/v1/models" | grep -o '"id":' | wc -l | tr -d ' ')
note "models before the rounds: ${MODELS_BEFORE:-0}"
STREAMED=0
for ROUND in 1 2; do
  T0=$(date +%s)
  RES=$(curl -s -m 45 -o /tmp/cpa-round-$ROUND.txt -w "%{http_code}" \
    -H "Authorization: Bearer $CLIENT_KEY" -H "Content-Type: application/json" \
    -d '{"model":"gemini-3.8-flash-low","stream":true,"max_tokens":48,"messages":[{"role":"user","content":"Reply with the single word: ok"}]}' \
    "http://127.0.0.1:$LOCAL_PORT/v1/chat/completions" 2>/dev/null)
  T1=$(date +%s)
  if [ "$RES" = "200" ]; then
    STREAMED=$((STREAMED + 1))
    ok "round $ROUND completed on its own in $((T1 - T0))s"
    grep -q '\[DONE\]' /tmp/cpa-round-$ROUND.txt \
      && ok "round $ROUND ended with a terminal frame" || bad "round $ROUND has no [DONE]"
  else
    note "round $ROUND returned ${RES:-nothing} (no upstream credential or offline; not this app's contract)"
  fi
done
rm -f /tmp/cpa-round-1.txt /tmp/cpa-round-2.txt
MODELS_AFTER=$(curl -s -m 10 -H "Authorization: Bearer $CLIENT_KEY" \
  "http://127.0.0.1:$LOCAL_PORT/v1/models" | grep -o '"id":' | wc -l | tr -d ' ')
note "models after the rounds: ${MODELS_AFTER:-0}"
if [ "${STREAMED:-0}" -gt 0 ]; then
  [ "${MODELS_AFTER:-0}" -ge "${MODELS_BEFORE:-0}" ] \
    && ok "the model list survived ${STREAMED} streaming round(s)" \
    || bad "the model list shrank after streaming (${MODELS_BEFORE} -> ${MODELS_AFTER})"
fi

# Panel: the activity is exported=false on purpose, so \`am start\` is refused by the system.
# Drive it the way a user does - dump the home screen, tap the 管理面板 button, wait for the fetch.
# Assert this check's own precondition: the phone is a real, in-use device, so between the D3
# rounds and this tap somebody can pick it up and land on another app. The dump below would then
# find 管理面板 nowhere, or worse, a stale-looking layout whose coordinates belong to that app.
# Bring our activity back and confirm it is the resumed one before trusting any coordinates.
PANEL_OPENED=0
PANEL_SKIPPED=0
$ADB shell am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 3
TOP=$($ADB shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | tr -d "\r")
case "$TOP" in
  *$PKG*) : ;;
  *) note "our activity is not in front (${TOP:-unknown}); skipping the panel check rather than tapping another app"
     PANEL_SKIPPED=1 ;;
esac
if [ "$PANEL_SKIPPED" = "0" ]; then
$ADB shell uiautomator dump /sdcard/cpa-ui.xml >/dev/null 2>&1
BOUNDS=$($ADB shell cat /sdcard/cpa-ui.xml 2>/dev/null | tr '>' '\n'   | grep 'text="管理面板"' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
if [ -n "$BOUNDS" ]; then
  # "bounds=[x1,y1][x2,y2]" -> tap the centre
  COORDS=$(printf %s "$BOUNDS" | tr -c '0-9' ' ')
  set -- $COORDS
  TAP_X=$(( ($1 + $3) / 2 ))
  TAP_Y=$(( ($2 + $4) / 2 ))
  note "tapping 管理面板 at $TAP_X,$TAP_Y"
  $ADB shell input tap $TAP_X $TAP_Y
  sleep 16
  PANEL_OPENED=1
else
  note "管理面板 button not found in the home dump"
fi
fi

LOGS_PANEL=$($ADB logcat -d -s CpaPanel:* 2>/dev/null)
LOGS_WEB=$($ADB logcat -d -s CpaWeb:* 2>/dev/null)
if [ "$PANEL_SKIPPED" = "1" ]; then
  note "panel assertions skipped: the device was showing another app"
elif [ "$PANEL_OPENED" = "1" ]; then
  printf %s "$LOGS_PANEL" | grep -q "sponsor=false" \
    && ok "panel served without relay-station promotion" || bad "panel sanitisation did not run"
  printf %s "$LOGS_PANEL" | grep -qE "clean=[0-9]+" \
    && ok "panel rewrote the document before the WebView saw it" || bad "no panel rewrite log"
  printf %s "$LOGS_WEB" | grep -q "panel mounted after" \
    && ok "panel SPA mounted, no blank-screen false positive" || bad "panel never reported a mount"
  printf %s "$LOGS_WEB" | grep -q cdn-cgi \
    && bad "RUM beacons still reach the local engine" || ok "RUM beacons suppressed"
  $ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 1
else
  bad "could not open the panel from the home screen"
fi

# The 2.0 screens must all be registered activities.
for ACT in MainActivity SettingsActivity PanelActivity; do
  $ADB shell cmd package resolve-activity --brief $PKG/.$ACT 2>/dev/null | grep -q "$PKG" \
    && ok "$ACT registered" || bad "$ACT missing from the manifest"
done

echo "== engine log tail =="
$ADB shell run-as $PKG tail -n 12 files/cpa-engine.log 2>/dev/null | tr -d "\r"

$ADB forward --remove tcp:$LOCAL_PORT >/dev/null 2>&1
echo
printf "== %d passed, %d failed ==\n" "$pass" "$fail"
[ "$fail" -eq 0 ]
