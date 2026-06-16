#!/usr/bin/env bash
set -euo pipefail

URL="${1:-http://localhost:8080/api/v1/rag/answer}"
QUERY="${2:-请总结程序化交易异常报送流程。}"
USER_ID="${3:-user-001}"

curl_args=(
  -N
  --no-buffer
  -H "Accept: text/event-stream"
  -H "Content-Type: application/json"
  -X POST
  "$URL"
  -d @-
)

curl "${curl_args[@]}" <<JSON
{
  "query": "$QUERY",
  "userId": "$USER_ID",
  "docIds": []
}
JSON
