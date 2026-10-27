#!/usr/bin/env bash
set -u
SCEN="${1:-normal}"
PROBE="${2:-ok}"
DLY="${3:-0}"
cd /root/AgyAndriod/diag
ps -eo pid,comm --no-headers | awk '$2=="cpasrv"{print $1}' | xargs -r kill 2>/dev/null
ps -eo pid,args --no-headers | grep 'mock_upstream.py' | grep -v grep | awk '{print $1}' | xargs -r kill 2>/dev/null
sleep 1
SCENARIO="$SCEN" PROBE="$PROBE" DELAY="$DLY" setsid nohup python3 mock_upstream.py 9911 > /tmp/mock.log 2>&1 < /dev/null &
sleep 1.5
setsid nohup /tmp/cpasrv --config /root/AgyAndriod/diag/config.yaml --local-model > /tmp/cpa.log 2>&1 < /dev/null &
sleep 12
echo "== ready scenario=$SCEN delay=$DLY =="
curl -s -m 8 http://127.0.0.1:18317/v1/models -H 'Authorization: Bearer cpa-android-local' | head -c 160
echo
