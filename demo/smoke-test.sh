#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BASE_URL=${BASE_URL:-http://127.0.0.1:8765}
OUTPUT_DIR=${OUTPUT_DIR:-"$SCRIPT_DIR/output"}
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/datamaster-smoke.XXXXXX")

cleanup() {
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

check_status() {
  local expected=$1
  local actual=$2
  local label=$3

  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: %s returned HTTP %s, expected %s\n' "$label" "$actual" "$expected" >&2
    return 1
  fi
}

extract_first_id() {
  grep -Eo '"id"[[:space:]]*:[[:space:]]*"[^"]+"' "$1" \
    | head -n 1 \
    | sed -E 's/^"id"[[:space:]]*:[[:space:]]*"([^"]+)"$/\1/'
}

assert_nonempty_file() {
  local path=$1
  local label=$2

  if [[ ! -s "$path" ]]; then
    fail "$label is empty: $path"
  fi
}

assert_json_number() {
  local path=$1
  local field=$2
  local expected_pattern=$3

  if ! grep -Eq "\"${field}\"[[:space:]]*:[[:space:]]*${expected_pattern}[[:space:]]*[,}]" "$path"; then
    fail "JSON field $field does not match expected value: $expected_pattern"
  fi
}

assert_office_file() {
  local path=$1
  local label=$2
  local signature

  assert_nonempty_file "$path" "$label"
  signature=$(LC_ALL=C head -c 2 "$path")
  if [[ "$signature" != "PK" ]]; then
    fail "$label is not a valid Office ZIP package: $path"
  fi
}

command -v curl >/dev/null 2>&1 || fail "curl is required"
mkdir -p "$OUTPUT_DIR"

printf '[1/6] Health check\n'
health_json="$WORK_DIR/health.json"
status=$(curl --silent --show-error --output "$health_json" --write-out '%{http_code}' \
  "$BASE_URL/api/health")
check_status 200 "$status" "GET /api/health" || {
  cat "$health_json" >&2
  exit 1
}
grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' "$health_json" \
  || fail "health response does not contain status=UP"

printf '[2/6] Multi-CSV upload\n'
upload_json="$WORK_DIR/upload.json"
status=$(curl --silent --show-error --output "$upload_json" --write-out '%{http_code}' \
  --request POST \
  --form "files=@$SCRIPT_DIR/samples/经营明细_2026-01.csv;type=text/csv" \
  --form "files=@$SCRIPT_DIR/samples/经营明细_2026-02.csv;type=text/csv" \
  --form "files=@$SCRIPT_DIR/samples/经营明细_2026-03.csv;type=text/csv" \
  "$BASE_URL/api/analysis/upload")
check_status 200 "$status" "POST /api/analysis/upload" || {
  cat "$upload_json" >&2
  exit 1
}
analysis_id=$(extract_first_id "$upload_json")
[[ -n "$analysis_id" ]] || fail "upload response has no id"
assert_json_number "$upload_json" "sourceFileCount" "3"
assert_json_number "$upload_json" "rowCount" "28"
assert_json_number "$upload_json" "revenue" "240900(\\.0+)?"
assert_json_number "$upload_json" "operatingProfit" "15850(\\.0+)?"
assert_json_number "$upload_json" "missingCustomer" "1"
grep -q '完全重复' "$upload_json" || fail "quality report is missing the duplicate-row warning"
grep -q '负数' "$upload_json" || fail "quality report is missing the negative-row warning"

printf '[3/6] Provider configuration and model catalog\n'
providers_json="$WORK_DIR/providers.json"
status=$(curl --silent --show-error --output "$providers_json" --write-out '%{http_code}' \
  "$BASE_URL/api/providers")
check_status 200 "$status" "GET /api/providers" || {
  cat "$providers_json" >&2
  exit 1
}
grep -Eq '"id"[[:space:]]*:[[:space:]]*"deepseek"' "$providers_json" \
  || fail "providers response is missing deepseek"
grep -Eq '"id"[[:space:]]*:[[:space:]]*"bailian"' "$providers_json" \
  || fail "providers response is missing bailian"
grep -q 'deepseek-v4-pro' "$providers_json" \
  || fail "providers response is missing the DeepSeek model catalog"
grep -q 'qwen3.7-plus' "$providers_json" \
  || fail "providers response is missing the Bailian model catalog"
grep -Eq '"customModelAllowed"[[:space:]]*:[[:space:]]*true' "$providers_json" \
  || fail "providers response does not allow custom model ids"

printf '[4/6] Account sync status\n'
sync_json="$WORK_DIR/sync.json"
status=$(curl --silent --show-error --output "$sync_json" --write-out '%{http_code}' \
  "$BASE_URL/api/sync/status")
check_status 200 "$status" "GET /api/sync/status" || {
  cat "$sync_json" >&2
  exit 1
}
grep -Eq '"siteUrl"[[:space:]]*:[[:space:]]*"https://' "$sync_json" \
  || fail "sync status is missing its HTTPS site URL"

printf '[5/6] Excel export\n'
xlsx_path="$OUTPUT_DIR/datamaster-analysis.xlsx"
status=$(curl --silent --show-error --output "$xlsx_path" --write-out '%{http_code}' \
  "$BASE_URL/api/analysis/$analysis_id/export.xlsx")
check_status 200 "$status" "GET /api/analysis/{id}/export.xlsx" || exit 1
assert_office_file "$xlsx_path" "Excel export"

printf '[6/6] Word export\n'
docx_path="$OUTPUT_DIR/datamaster-report.docx"
status=$(curl --silent --show-error --output "$docx_path" --write-out '%{http_code}' \
  "$BASE_URL/api/analysis/$analysis_id/report.docx")
check_status 200 "$status" "GET /api/analysis/{id}/report.docx" || exit 1
assert_office_file "$docx_path" "Word export"

printf 'PASS: analysis=%s\n' "$analysis_id"
printf 'Excel: %s\n' "$xlsx_path"
printf 'Word:  %s\n' "$docx_path"
