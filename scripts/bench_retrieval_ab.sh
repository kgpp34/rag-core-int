#!/usr/bin/env bash
#
# RAG 检索接口 A/B 性能对比（纯 bash + curl，内网无需 Python）
#
# 前置：问题清单是「每行一条问题」的纯文本文件。
#   从 Excel 导出最省事的两种办法：
#     A. 在 Excel/WPS 里选中第一列（跳过表头那行）→ 复制 → 粘到记事本 → 存成 questions.txt
#     B. 另存为「文本文件(制表符分隔).txt」，再用第一列：
#          cut -f1 questions.txt > questions.clean.txt
#   注意：保存时选 UTF-8 编码，Windows 记事本选「UTF-8」即可。
#
# 用法：
#   ./bench_retrieval_ab.sh -f questions.txt \
#       -A http://10.0.0.11:8080/api/v1 -a milvus \
#       -B http://10.0.0.12:8080/api/v1 -b kingbase \
#       -n 3 -w 3 -m HYBRID -o bench-results
#
#   ./bench_retrieval_ab.sh -f questions.txt -A ... -B ... --dry-run
#
# 默认跳过第 1 行（Excel 表头）。纯问题清单用 -s 0。
#
set -uo pipefail

# ---------------------------------------------------------------- 默认值
QUESTIONS=""
BASE_A=""
BASE_B=""
LABEL_A="milvus"
LABEL_B="kingbase"
PATH_SUFFIX="/retrieval/search"
PLAN_TYPE="STANDARD_RETRIEVAL"
RETRIEVAL_MODE=""
DOC_IDS=""
REPEAT=3
WARMUP=3
TIMEOUT=120
INTERVAL_MS=200
OUT_DIR="bench-results"
EXTRA_HEADER=()
DRY_RUN="no"
SKIP_LINES=1

usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_CYN=$'\033[36m'; C_OFF=$'\033[0m'
else
  C_RED=""; C_GRN=""; C_YEL=""; C_CYN=""; C_OFF=""
fi
die() { printf '%s[✗]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }
info() { printf '%s[*]%s %s\n' "$C_CYN" "$C_OFF" "$*"; }
ok() { printf '%s[✓]%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%s[!]%s %s\n' "$C_YEL" "$C_OFF" "$*"; }

# ---------------------------------------------------------------- 参数
while [ $# -gt 0 ]; do
  case "$1" in
    -f|--questions) QUESTIONS="$2"; shift 2 ;;
    -A) BASE_A="$2"; shift 2 ;;
    -B) BASE_B="$2"; shift 2 ;;
    -a) LABEL_A="$2"; shift 2 ;;
    -b) LABEL_B="$2"; shift 2 ;;
    -p|--path) PATH_SUFFIX="$2"; shift 2 ;;
    -n|--repeat) REPEAT="$2"; shift 2 ;;
    -w|--warmup) WARMUP="$2"; shift 2 ;;
    -t|--timeout) TIMEOUT="$2"; shift 2 ;;
    -i|--interval-ms) INTERVAL_MS="$2"; shift 2 ;;
    -o|--out) OUT_DIR="$2"; shift 2 ;;
    -m|--retrieval-mode) RETRIEVAL_MODE="$2"; shift 2 ;;
    -P|--plan-type) PLAN_TYPE="$2"; shift 2 ;;
    --doc-ids) DOC_IDS="$2"; shift 2 ;;
    -s|--skip) SKIP_LINES="$2"; shift 2 ;;
    -H|--header) EXTRA_HEADER+=("$2"); shift 2 ;;
    --dry-run) DRY_RUN="yes"; shift ;;
    -h|--help) usage 0 ;;
    *) die "未知参数：$1（用 -h 看用法）" ;;
  esac
done

[ -n "$QUESTIONS" ] || die "缺少 -f questions.txt"
[ -f "$QUESTIONS" ] || die "找不到问题文件：$QUESTIONS"
[ -n "$BASE_A" ] && [ -n "$BASE_B" ] || die "缺少 -A 或 -B"
command -v curl >/dev/null 2>&1 || die "没有 curl，内网机器请先装 curl"
command -v awk >/dev/null 2>&1 || die "没有 awk"

# 额外请求头拼成 curl 参数数组
CURL_HEADERS=()
if [ ${#EXTRA_HEADER[@]} -gt 0 ]; then
  for h in "${EXTRA_HEADER[@]}"; do
    CURL_HEADERS+=(-H "$h")
  done
fi

# 去掉 Windows 换行和 BOM，并按 --skip 跳过表头，生成干净的问题文件
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
sed -e 's/\r$//' -e '1s/^\xEF\xBB\xBF//' "$QUESTIONS" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' \
  | grep -v '^$' | tail -n "+$((SKIP_LINES + 1))" > "$WORK_DIR/questions.txt"

TOTAL_Q=$(wc -l < "$WORK_DIR/questions.txt" | tr -d ' ')
[ "$TOTAL_Q" -gt 0 ] || die "问题文件里没有内容"

URL_A="${BASE_A%/}/${PATH_SUFFIX#/}"
URL_B="${BASE_B%/}/${PATH_SUFFIX#/}"

# ---------------------------------------------------------------- 请求体
# JSON 转义：反斜杠、双引号、换行、制表符
json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\t'/ }"
  s="${s//$'\r'/}"
  s="${s//$'\n'/ }"
  printf '%s' "$s"
}

build_payload() {
  local query="$1" escaped mode_part="" doc_part=""
  escaped="$(json_escape "$query")"
  if [ -n "$RETRIEVAL_MODE" ]; then
    mode_part=",\"retrievalMode\":\"${RETRIEVAL_MODE}\""
  fi
  if [ -n "$DOC_IDS" ]; then
    doc_part=",\"docIds\":[${DOC_IDS}]"
  fi
  printf '{"query":"%s","planType":"%s"%s%s}' "$escaped" "$PLAN_TYPE" "$mode_part" "$doc_part"
}

# ---------------------------------------------------------------- 单次调用
# 输出一行：ok|status|latency_ms|chunks|error
call_once() {
  local url="$1" payload="$2" body_file="$WORK_DIR/body" err_file="$WORK_DIR/err"
  local meta code seconds latency chunks=0 err=""

  meta="$(curl -sS --max-time "$TIMEOUT" \
      -o "$body_file" 2>"$err_file" \
      -w '%{http_code} %{time_total}' \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      ${CURL_HEADERS[@]+"${CURL_HEADERS[@]}"} \
      --data-binary "$payload" \
      "$url")" || true

  code="$(printf '%s' "$meta" | awk '{print $1}')"
  seconds="$(printf '%s' "$meta" | awk '{print $2}')"
  [ -n "$code" ] || code="000"
  [ -n "$seconds" ] || seconds="0"

  latency="$(awk -v s="$seconds" 'BEGIN { printf "%.1f", s * 1000 }')"

  if [ -f "$body_file" ]; then
    chunks="$(grep -o '"chunkId"' "$body_file" 2>/dev/null | wc -l | tr -d ' ')"
  fi
  if [ -s "$err_file" ]; then
    err="$(head -c 200 "$err_file" | tr '\n' ' ' | tr ',' ';')"
  fi

  if [ "$code" -ge 200 ] 2>/dev/null && [ "$code" -lt 300 ]; then
    printf '1|%s|%s|%s|%s' "$code" "$latency" "$chunks" "$err"
  else
    [ -n "$err" ] || err="http_${code}"
    printf '0|%s|%s|%s|%s' "$code" "$latency" "$chunks" "$err"
  fi
}

# ---------------------------------------------------------------- dry-run
if [ "$DRY_RUN" = "yes" ]; then
  info "读到 ${TOTAL_Q} 条问题"
  info "A ($LABEL_A): $URL_A"
  info "B ($LABEL_B): $URL_B"
  head -3 "$WORK_DIR/questions.txt" | while IFS= read -r q; do
    printf '\n--- 请求体 ---\n%s\n' "$(build_payload "$q")"
  done
  exit 0
fi

# ---------------------------------------------------------------- 预热
info "读到 ${TOTAL_Q} 条问题，每条重复 ${REPEAT} 次，warmup ${WARMUP} 次"
info "A ($LABEL_A): $URL_A"
info "B ($LABEL_B): $URL_B"

if [ "$WARMUP" -gt 0 ]; then
  first_q="$(head -1 "$WORK_DIR/questions.txt")"
  warm_payload="$(build_payload "$first_q")"
  i=0
  while [ "$i" -lt "$WARMUP" ]; do
    call_once "$URL_A" "$warm_payload" >/dev/null
    call_once "$URL_B" "$warm_payload" >/dev/null
    i=$((i + 1))
  done
  ok "warmup 完成"
fi

# ---------------------------------------------------------------- 压测
mkdir -p "$OUT_DIR"
RAW_CSV="$OUT_DIR/raw.csv"
printf 'question_index,label,run,ok,status,latency_ms,chunks,error,question\n' > "$RAW_CSV"

TOTAL_CALLS=$((TOTAL_Q * REPEAT * 2))
done_calls=0
q_index=0

emit() {
  local label="$1" url="$2" payload="$3" qi="$4" run="$5" question="$6" result
  result="$(call_once "$url" "$payload")"
  IFS='|' read -r okflag status latency chunks err <<< "$result"
  done_calls=$((done_calls + 1))
  # 问题里可能带逗号，CSV 里加引号并转义内部引号
  local escaped_q="${question//\"/\"\"}"
  printf '%s,%s,%s,%s,%s,%s,%s,"%s","%s"\n' \
    "$qi" "$label" "$run" "$okflag" "$status" "$latency" "$chunks" "$err" "$escaped_q" >> "$RAW_CSV"

  if [ -t 1 ]; then
    printf '\r[%d/%d] Q%02d r%d %-10s %s %8sms' \
      "$done_calls" "$TOTAL_CALLS" "$qi" "$run" "$label" \
      "$([ "$okflag" = "1" ] && echo 'ok ' || echo 'ERR')" "$latency"
  else
    printf '[%d/%d] Q%02d r%d %-10s %s %8sms\n' \
      "$done_calls" "$TOTAL_CALLS" "$qi" "$run" "$label" \
      "$([ "$okflag" = "1" ] && echo 'ok ' || echo 'ERR')" "$latency"
  fi
}

while IFS= read -r question; do
  [ -n "$question" ] || continue
  q_index=$((q_index + 1))
  payload="$(build_payload "$question")"
  run=1
  while [ "$run" -le "$REPEAT" ]; do
    # 交替先后顺序，抵消两台机器在不同时间点的抖动
    if [ $(( (q_index + run) % 2 )) -eq 0 ]; then
      emit "$LABEL_A" "$URL_A" "$payload" "$q_index" "$run" "$question"
      emit "$LABEL_B" "$URL_B" "$payload" "$q_index" "$run" "$question"
    else
      emit "$LABEL_B" "$URL_B" "$payload" "$q_index" "$run" "$question"
      emit "$LABEL_A" "$URL_A" "$payload" "$q_index" "$run" "$question"
    fi
    run=$((run + 1))
  done
  if [ "$INTERVAL_MS" -gt 0 ]; then
    sleep "$(awk -v ms="$INTERVAL_MS" 'BEGIN { printf "%.3f", ms / 1000 }')"
  fi
done < "$WORK_DIR/questions.txt"
[ -t 1 ] && printf '\n'

# ---------------------------------------------------------------- 统计
SUMMARY_CSV="$OUT_DIR/summary.csv"

awk -F',' '
NR == 1 { next }
{
  label = $2
  total[label]++
  if ($4 == "1") {
    okn[label]++
    n[label]++
    v[label, n[label]] = $6 + 0
  }
}
END {
  print "label,calls,ok,success_rate,mean_ms,p50_ms,p90_ms,p99_ms,min_ms,max_ms" > "'"$SUMMARY_CSV"'"
  for (label in total) {
    cnt = n[label] + 0
    if (cnt == 0) {
      printf "%-12s %5d %5d %7.1f%%  (全部失败)\n", label, total[label], 0, 0.0
      printf "%s,%d,0,0,0,0,0,0,0,0\n", label, total[label] >> "'"$SUMMARY_CSV"'"
      continue
    }
    # 插入排序
    for (i = 2; i <= cnt; i++) {
      key = v[label, i]
      j = i - 1
      while (j >= 1 && v[label, j] > key) {
        v[label, j + 1] = v[label, j]
        j--
      }
      v[label, j + 1] = key
    }
    sum = 0
    for (i = 1; i <= cnt; i++) sum += v[label, i]
    mean = sum / cnt
    p50 = pick(label, cnt, 0.50)
    p90 = pick(label, cnt, 0.90)
    p99 = pick(label, cnt, 0.99)
    rate = okn[label] / total[label] * 100
    printf "%-12s %5d %5d %7.1f%% %8.0f %8.0f %8.0f %8.0f %8.0f %8.0f\n", \
      label, total[label], okn[label], rate, mean, p50, p90, p99, v[label, 1], v[label, cnt]
    printf "%s,%d,%d,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f\n", \
      label, total[label], okn[label], rate, mean, p50, p90, p99, v[label, 1], v[label, cnt] >> "'"$SUMMARY_CSV"'"
  }
}
function pick(label, cnt, pct,   rank, lo, hi) {
  rank = pct * (cnt - 1) + 1
  lo = int(rank)
  hi = lo + 1
  if (hi > cnt) hi = cnt
  if (lo < 1) lo = 1
  return v[label, lo] + (v[label, hi] - v[label, lo]) * (rank - lo)
}
' "$RAW_CSV" | {
  printf '\n=== 汇总 ===\n'
  printf '%-12s %5s %5s %8s %8s %8s %8s %8s %8s %8s\n' \
    "目标" "调用" "成功" "成功率" "均值" "P50" "P90" "P99" "最小" "最大"
  cat
}

# ---------------------------------------------------------------- 逐问题
PER_Q_CSV="$OUT_DIR/per_question.csv"

awk -F',' -v a="$LABEL_A" -v b="$LABEL_B" '
NR == 1 { next }
{
  label = $2
  qi = $1
  if ($4 != "1") next
  vals[qi, label, ++cnt[qi, label]] = $6 + 0
  if (!(qi in seen)) { seen[qi] = 1; order[++nq] = qi }
}
END {
  print "question_index," a "_median_ms," b "_median_ms,delta_ms," b "_slower_ratio" > "'"$PER_Q_CSV"'"
  for (idx = 1; idx <= nq; idx++) {
    qi = order[idx]
    ma = median(qi, a)
    mb = median(qi, b)
    if (ma > 0 && mb > 0) {
      printf "%d,%.1f,%.1f,%+.1f,%.2f\n", qi, ma, mb, mb - ma, mb / ma >> "'"$PER_Q_CSV"'"
    } else {
      printf "%d,%.1f,%.1f,,\n", qi, ma, mb >> "'"$PER_Q_CSV"'"
    }
  }
}
function median(qi, label,   k, c, arr, i, j, t) {
  c = cnt[qi, label] + 0
  if (c == 0) return 0
  for (i = 1; i <= c; i++) arr[i] = vals[qi, label, i]
  for (i = 2; i <= c; i++) {
    t = arr[i]; j = i - 1
    while (j >= 1 && arr[j] > t) { arr[j + 1] = arr[j]; j-- }
    arr[j + 1] = t
  }
  return (c % 2 == 1) ? arr[(c + 1) / 2] : (arr[c / 2] + arr[c / 2 + 1]) / 2
}
' "$RAW_CSV"

# ---------------------------------------------------------------- 失败样本
FAILS="$(awk -F',' 'NR > 1 && $4 != "1" { c++ } END { print c + 0 }' "$RAW_CSV")"
if [ "$FAILS" -gt 0 ]; then
  warn "有 ${FAILS} 次调用失败，前 5 条："
  awk -F',' 'NR > 1 && $4 != "1" { printf "    %s Q%s status=%s %s\n", $2, $1, $5, $8; n++; if (n >= 5) exit }' "$RAW_CSV"
else
  ok "全部调用成功"
fi

echo
ok "结果已写入 ${OUT_DIR}/ （summary.csv / per_question.csv / raw.csv）"
printf '  看汇总：  column -s, -t %s\n' "$SUMMARY_CSV" 2>/dev/null || true
