#!/usr/bin/env bash
set -euo pipefail
cd /opt/campusai/supabase
# Fail closed: the separate activation service must retain host resources.
test "$(cat /sys/fs/cgroup/campusai.slice/memory.max)" = 943718400
test "$(cat /sys/fs/cgroup/campusai.slice/memory.swap.max)" = 0
docker compose config --format json | python3 -c '
import json,sys
services=json.load(sys.stdin)["services"]
assert set(services)=={"db","rest","auth","imgproxy","api-gw","storage","realtime"}
assert all(s.get("cgroup_parent")=="campusai.slice" for s in services.values())
assert all(s.get("restart")=="on-failure:5" for s in services.values())
'
stop_on_failure() {
    result=$?
    if [ "$result" -ne 0 ]; then docker compose stop -t 5 || true; fi
    exit "$result"
}
trap stop_on_failure EXIT
# Serial startup avoids overlapping Node/Erlang initialization peaks.
for service in db rest auth imgproxy api-gw storage realtime; do
    docker compose up -d --no-deps --wait --wait-timeout 180 "$service"
done
