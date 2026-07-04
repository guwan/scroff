#!/usr/bin/env bash
# =============================================================
# Scroff Server - Local Development Runner
# =============================================================
# Usage:
#   ./scripts/run-local.sh         # start with local profile
#   ./scripts/run-local.sh --fg    # (reserved) explicit foreground
#
# What it does:
#   1. Ensures src/main/resources/application-local.yml exists.
#      First run will copy from .example template and ask you to
#      fill in your local DB password, then re-run.
#   2. Runs `./gradlew bootRun -Dspring.profiles.active=local`.
#
# Stop: Ctrl+C
# =============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RES_DIR="$SERVER_DIR/src/main/resources"
LOCAL_YML="$RES_DIR/application-local.yml"
EXAMPLE_YML="$RES_DIR/application-local.yml.example"

cd "$SERVER_DIR"

# 1. Check local config
if [ ! -f "$LOCAL_YML" ]; then
    echo "============================================================"
    echo " [SETUP] application-local.yml not found."
    echo " [SETUP] Copying from .example template ..."
    echo "============================================================"
    cp "$EXAMPLE_YML" "$LOCAL_YML"
    echo ""
    echo "  -> Created: $LOCAL_YML"
    echo ""
    echo "  ! Please open the file and replace YOUR_LOCAL_DB_PASSWORD"
    echo "    with your real MariaDB password, then run this script again."
    echo ""
    echo "  Hint: sed -i '' 's/YOUR_LOCAL_DB_PASSWORD/<your_pw>/' \\"
    echo "        $LOCAL_YML"
    echo ""
    exit 1
fi

# 2. Sanity check: warn if the placeholder is still there
if grep -q "YOUR_LOCAL_DB_PASSWORD" "$LOCAL_YML"; then
    echo "============================================================"
    echo " [WARN] application-local.yml still contains placeholder:"
    echo "        YOUR_LOCAL_DB_PASSWORD"
    echo " [WARN] Please replace it with your real DB password."
    echo "============================================================"
    exit 1
fi

# 3. Sanity check: warn if the adb executable path doesn't exist
ADB_PATH=$(grep -E '^\s*executable:' "$LOCAL_YML" | head -1 | sed -E 's/.*executable:\s*//' | tr -d '"' | tr -d "'")
if [ -n "$ADB_PATH" ] && [ ! -x "$ADB_PATH" ] && ! command -v "$ADB_PATH" >/dev/null 2>&1; then
    echo "============================================================"
    echo " [WARN] ADB executable not found: $ADB_PATH"
    echo " [WARN] Update 'scroff.adb.executable' in application-local.yml"
    echo "        or set ADB_EXECUTABLE env var before starting."
    echo "============================================================"
fi

# 4. Run
echo "============================================================"
echo " Scroff Server - Local Dev (profile=local)"
echo "============================================================"
echo "  Config : $LOCAL_YML"
echo "  Profile: local"
echo "  Stop   : Ctrl+C"
echo ""
exec ./gradlew bootRun -Dspring.profiles.active=local
