#!/usr/bin/env bash

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8585}"
LOGIN_UID="${LOGIN_UID:-Hypernova101}"
PASSWORD="${PASSWORD:-password}"
ASSIGNMENT_NAME="${ASSIGNMENT_NAME:-Assignment API Smoke Test}"
NOTES="${NOTES:-Uploaded by automated test script}"
TEST_FILE="${TEST_FILE:-scripts/random_assignment_submission.txt}"

echo "[1/4] Updating test file: ${TEST_FILE}"
mkdir -p "$(dirname "${TEST_FILE}")"
{
  echo "Assignment submission test payload"
  echo "Timestamp: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Nonce: ${RANDOM}-${RANDOM}-${RANDOM}"
} > "${TEST_FILE}"

echo "[2/4] Authenticating as ${LOGIN_UID}"
AUTH_RESPONSE="$(curl -sS -i -X POST "${BASE_URL}/authenticate" \
  -H "Content-Type: application/json" \
  -d "{\"uid\":\"${LOGIN_UID}\",\"password\":\"${PASSWORD}\"}")"

JWT_TOKEN="$(printf '%s\n' "${AUTH_RESPONSE}" | tr -d '\r' | awk -F'jwt_java_spring=' '/^Set-Cookie: jwt_java_spring=/{split($2,a,";"); print a[1]; exit}')"

if [[ -z "${JWT_TOKEN}" ]]; then
  echo "Authentication failed. Full response:"
  printf '%s\n' "${AUTH_RESPONSE}"
  exit 1
fi

echo "[3/4] Fetching authenticated user profile"
ME_JSON="$(curl -sS "${BASE_URL}/api/person/get" -H "Cookie: jwt_java_spring=${JWT_TOKEN}")"
USER_ID="$(printf '%s' "${ME_JSON}" | grep -o '"id":[0-9]\+' | head -n1 | cut -d: -f2 || true)"
USER_UID="$(printf '%s' "${ME_JSON}" | sed -n 's/.*"uid":"\([^"]*\)".*/\1/p' | head -n1)"

if [[ -z "${USER_ID}" || -z "${USER_UID}" ]]; then
  echo "Could not parse user profile from /api/person/get. Response:"
  printf '%s\n' "${ME_JSON}"
  exit 1
fi

echo "[4/4] Uploading assignment submission"
UPLOAD_RESPONSE="$(curl -sS -X POST "${BASE_URL}/api/assignment-submissions/upload" \
  -H "Cookie: jwt_java_spring=${JWT_TOKEN}" \
  -F "assignmentName=${ASSIGNMENT_NAME}" \
  -F "userId=${USER_ID}" \
  -F "username=${USER_UID}" \
  -F "file=@${TEST_FILE}" \
  -F "notes=${NOTES}")"

echo "Upload API response:"
printf '%s\n' "${UPLOAD_RESPONSE}"

if printf '%s' "${UPLOAD_RESPONSE}" | grep -q "Assignment submission uploaded successfully"; then
  echo "PASS: assignment submission upload succeeded."
else
  echo "FAIL: assignment submission upload did not return success marker."
  exit 1
fi
