#!/usr/bin/env bash
# =============================================================
# Scroff Server - Local Development Runner
# =============================================================
# Usage:
#   ./scripts/run-local.sh
#
# What it does:
#   1. Ensures src/main/resources/application-local.yml exists.
#      First run will copy from .example template and ask you to
#      fill in your local DB password, then re-run.
#   2. Sets SPRING_PROFILES_ACTIVE=local (env var) so Spring Boot
#      loads application-local.yml. This is more reliable than
#      passing -D to gradle.
#   3. Enables Spring Boot debug logging so the loaded property
#      sources are visible (look for "application-local.yml").
#   4. Runs `./gradlew bootRun`.
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
    echo "  *** Please open the file and replace YOUR_LOCAL_DB_PASSWORD"
    echo "      with your real MariaDB password, use quotes around the value."
    echo "      Then run this script again."
    echo ""
    echo "  Hint: sed -i '' 's/YOUR_LOCAL_DB_PASSWORD/\"your_pw\"/' \\"
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

# 3. Sanity check: warn if active ADB profile's executable is missing (optional)
ACTIVE_PROFILE=$(grep -E '^\s*active-profile-id:' "$LOCAL_YML" 2>/dev/null | head -1 | sed -E 's/.*active-profile-id:\s*//' | tr -d '"' | tr -d "'")
if [ -n "$ACTIVE_PROFILE" ]; then
    # 找对应 profile 块下的 executable
    ADB_PATH=$(awk -v p="^- id: $ACTIVE_PROFILE\$" '$0 ~ p {flag=1; next} /^- id:/ {flag=0} flag && /executable:/ {sub(/.*executable:[ \t]*/, ""); gsub(/^"|"$/, ""); print; exit}' "$LOCAL_YML")
    if [ -n "$ADB_PATH" ] && [ ! -x "$ADB_PATH" ] && ! command -v "$ADB_PATH" >/dev/null 2>&1; then
        echo "============================================================"
        echo " [WARN] ADB profile '$ACTIVE_PROFILE' executable not found: $ADB_PATH"
        echo " [WARN] Update profile in application-local.yml or /settings page"
        echo "============================================================"
    fi
fi

# 4. Set profile via env var (most reliable way for Spring Boot).
#    Also pass -Dspring.profiles.active as a fallback in case some
#    IDE/launcher strips env vars.
export SPRING_PROFILES_ACTIVE=local

echo "============================================================"
echo " Scroff Server - Local Dev"
echo "============================================================"
echo "  Profile    : $SPRING_PROFILES_ACTIVE  (env var)"
echo "  Local yml  : $LOCAL_YML"
echo "  Stop       : Ctrl+C"
echo ""
echo "  Verify: after startup, you should see in the log:"
echo "    'The following 1 profile is active: \"local\"'"
echo "    '...Tomcat started on port 8880...'"
echo ""

exec ./gradlew bootRun -Dspring.profiles.active=local
