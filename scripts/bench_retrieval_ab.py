#!/usr/bin/env python3
"""
RAG 检索接口 A/B 性能对比脚本（例如 Milvus 环境 vs Kingbase 环境）。

同一批问题、同一份请求体，分别打到两台机器，比较端到端延迟。

要点（保证对比公平）：
  1. 每台机器都先 warmup，再开始计时；
  2. 同一条问题交替 A/B 先后顺序，抵消网络/GC 的阶段性抖动；
  3. 每个问题重复 N 次，统计用中位数与分位数，不用单次值；
  4. 连接复用（装了 requests 就走 Session），避免 TCP 握手摊到延迟里；
  5. 请求体完全一致，只有 base URL 不同。

⚠️ 前提：两边知识库内容必须一致（同一份数据、同一 embedding 模型），
   否则比的是数据量差异，不是存储引擎差异。如果两边的 embedding / rerank
   服务不是同一个，检索延迟里会混入模型服务差异，对比结论不成立。

示例：
  # 1. 先干跑，确认问题条数和请求体对不对
  python3 scripts/bench_retrieval_ab.py \
      --excel ~/questions.xlsx --sheet Sheet1 --column 1 --start-row 2 --end-row 52 \
      --base-a http://10.0.0.11:8080/api/v1 --label-a milvus \
      --base-b http://10.0.0.12:8080/api/v1 --label-b kingbase \
      --dry-run

  # 2. 正式压测（每个问题跑 3 次）
  python3 scripts/bench_retrieval_ab.py \
      --excel ~/questions.xlsx --column 1 --start-row 2 --end-row 52 \
      --base-a http://10.0.0.11:8080/api/v1 --label-a milvus \
      --base-b http://10.0.0.12:8080/api/v1 --label-b kingbase \
      --repeat 3 --warmup 3 --out bench-results

  # 3. 只想比向量检索、不要混合检索
  ... --retrieval-mode SEMANTIC

依赖：openpyxl（读 Excel）；requests 可选（有则复用连接，没有则退化为 urllib）。
  pip install openpyxl requests
"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib import error, request

try:  # 有 requests 就用它，连接复用更接近真实调用
    import requests  # type: ignore
except ImportError:  # pragma: no cover
    requests = None  # type: ignore


# ------------------------------------------------------------------ 数据结构


@dataclass
class Sample:
    question_index: int
    question: str
    label: str
    run: int
    latency_ms: float
    status: int | None
    ok: bool
    chunk_count: int | None = None
    response_bytes: int = 0
    request_id: str | None = None
    error: str | None = None


@dataclass
class Aggregate:
    label: str
    total: int
    ok: int
    mean: float
    p50: float
    p90: float
    p99: float
    min: float
    max: float
    std: float

    @property
    def success_rate(self) -> float:
        return self.ok / self.total if self.total else 0.0


@dataclass
class Host:
    label: str
    base_url: str
    url: str
    samples: list[Sample] = field(default_factory=list)


# ------------------------------------------------------------------ HTTP 调用


class HttpClient:
    """极简 HTTP 客户端：优先 requests（连接复用），否则退化为 urllib。"""

    def __init__(self, timeout: float, headers: dict[str, str]) -> None:
        self.timeout = timeout
        self.headers = headers
        self._session = requests.Session() if requests is not None else None
        if self._session is not None:
            self._session.headers.update(headers)

    def post_json(self, url: str, payload: dict[str, Any]) -> tuple[int | None, float, int, str | None, Any]:
        """返回 (status, latency_ms, response_bytes, error, parsed_json)。"""
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        started = time.perf_counter()
        if self._session is not None:
            try:
                resp = self._session.post(url, data=body, timeout=self.timeout)
                elapsed_ms = (time.perf_counter() - started) * 1000.0
                raw = resp.content
                return resp.status_code, elapsed_ms, len(raw), None, _maybe_json(raw)
            except Exception as exc:  # noqa: BLE001 网络异常都算一次失败样本
                elapsed_ms = (time.perf_counter() - started) * 1000.0
                return None, elapsed_ms, 0, f"{type(exc).__name__}: {exc}", None

        req = request.Request(url, data=body, headers=self.headers, method="POST")
        try:
            with request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                elapsed_ms = (time.perf_counter() - started) * 1000.0
                return resp.status, elapsed_ms, len(raw), None, _maybe_json(raw)
        except error.HTTPError as exc:
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            raw = exc.read()
            return exc.code, elapsed_ms, len(raw), None, _maybe_json(raw)
        except Exception as exc:  # noqa: BLE001
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            return None, elapsed_ms, 0, f"{type(exc).__name__}: {exc}", None


def _maybe_json(raw: bytes) -> Any:
    try:
        return json.loads(raw.decode("utf-8", errors="replace"))
    except Exception:  # noqa: BLE001
        return None


# ------------------------------------------------------------------ 问题来源


def load_questions(args: argparse.Namespace) -> list[str]:
    if args.questions_file:
        lines = Path(args.questions_file).read_text(encoding="utf-8").splitlines()
        return [line.strip() for line in lines[args.start_row - 1 :] if line.strip()]

    try:
        from openpyxl import load_workbook
    except ImportError:
        sys.exit(
            "缺少 openpyxl，无法读取 Excel。\n"
            "  安装：pip install openpyxl\n"
            "  或者用 --questions-file 直接传一个每行一条问题的文本文件。"
        )

    workbook = load_workbook(filename=args.excel, read_only=True, data_only=True)
    if args.sheet not in workbook.sheetnames:
        sys.exit(f"找不到 sheet '{args.sheet}'，可用的有：{workbook.sheetnames}")
    sheet = workbook[args.sheet]

    column = _column_index(args.column)
    questions: list[str] = []
    for row_idx, row in enumerate(sheet.iter_rows(min_col=column, max_col=column, values_only=True), start=1):
        if row_idx < args.start_row:
            continue
        if args.end_row and row_idx > args.end_row:
            break
        cell = row[0] if row else None
        if cell is None:
            continue
        text = str(cell).strip()
        if text:
            questions.append(text)
    workbook.close()
    return questions


def _column_index(column: str) -> int:
    """支持 '1'、'A' 两种写法，返回 1-based 列号。"""
    if column.isdigit():
        return int(column)
    index = 0
    for char in column.upper():
        if not char.isalpha():
            sys.exit(f"--column 只支持数字或字母，收到：{column}")
        index = index * 26 + (ord(char) - ord("A") + 1)
    return index


# ------------------------------------------------------------------ 统计


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (pct / 100.0) * (len(ordered) - 1)
    low = int(rank)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (rank - low)


def aggregate(label: str, samples: list[Sample]) -> Aggregate:
    latencies = [s.latency_ms for s in samples]
    ok = sum(1 for s in samples if s.ok)
    return Aggregate(
        label=label,
        total=len(samples),
        ok=ok,
        mean=statistics.fmean(latencies) if latencies else 0.0,
        p50=percentile(latencies, 50),
        p90=percentile(latencies, 90),
        p99=percentile(latencies, 99),
        min=min(latencies) if latencies else 0.0,
        max=max(latencies) if latencies else 0.0,
        std=statistics.pstdev(latencies) if len(latencies) > 1 else 0.0,
    )


def per_question_median(samples: list[Sample]) -> dict[int, float]:
    grouped: dict[int, list[float]] = {}
    for sample in samples:
        if sample.ok:
            grouped.setdefault(sample.question_index, []).append(sample.latency_ms)
    return {index: statistics.median(values) for index, values in grouped.items()}


# ------------------------------------------------------------------ 执行


def build_payload(template: dict[str, Any], question: str, args: argparse.Namespace) -> dict[str, Any]:
    payload = dict(template)
    payload["query"] = question
    if args.plan_type and "planType" not in payload:
        payload["planType"] = args.plan_type
    if args.retrieval_mode and "retrievalMode" not in payload:
        payload["retrievalMode"] = args.retrieval_mode
    if args.doc_ids and "docIds" not in payload:
        payload["docIds"] = args.doc_ids
    return payload


def call_once(
    client: HttpClient,
    host: Host,
    payload: dict[str, Any],
    question_index: int,
    question: str,
    run: int,
) -> Sample:
    status, latency_ms, size, err, parsed = client.post_json(host.url, payload)
    ok = status is not None and 200 <= status < 300 and err is None
    chunk_count = None
    request_id = None
    if isinstance(parsed, dict):
        chunks = parsed.get("chunks")
        if isinstance(chunks, list):
            chunk_count = len(chunks)
        request_id = parsed.get("requestId")
        if not ok and parsed.get("message"):
            err = str(parsed.get("message"))[:300]
    return Sample(
        question_index=question_index,
        question=question,
        label=host.label,
        run=run,
        latency_ms=latency_ms,
        status=status,
        ok=ok,
        chunk_count=chunk_count,
        response_bytes=size,
        request_id=request_id,
        error=err,
    )


def run_benchmark(args: argparse.Namespace, questions: list[str], template: dict[str, Any]) -> tuple[Host, Host]:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    for raw in args.header:
        if "=" not in raw:
            sys.exit(f"--header 格式应为 KEY=VALUE，收到：{raw}")
        key, value = raw.split("=", 1)
        headers[key.strip()] = value.strip()

    host_a = Host(args.label_a, args.base_a, _join(args.base_a, args.path))
    host_b = Host(args.label_b, args.base_b, _join(args.base_b, args.path))
    client = HttpClient(args.timeout, headers)

    print(f"[*] 目标 A ({host_a.label}): {host_a.url}")
    print(f"[*] 目标 B ({host_b.label}): {host_b.url}")
    print(f"[*] 问题数 {len(questions)}，每个重复 {args.repeat} 次，warmup {args.warmup} 次")

    if args.warmup > 0:
        warmup_payload = build_payload(template, questions[0], args)
        for host in (host_a, host_b):
            for _ in range(args.warmup):
                call_once(client, host, warmup_payload, 0, questions[0], run=-1)
            print(f"[*] {host.label} warmup 完成")

    total_calls = len(questions) * args.repeat * 2
    done = 0
    line_end = "\r" if sys.stdout.isatty() else "\n"
    for question_index, question in enumerate(questions, start=1):
        payload = build_payload(template, question, args)
        for run in range(1, args.repeat + 1):
            # 交替先后顺序，抵消两台机器在不同时间点的抖动
            order = (question_index + run) % 2
            hosts = (host_a, host_b) if order == 0 else (host_b, host_a)
            for host in hosts:
                sample = call_once(client, host, payload, question_index, question, run)
                host.samples.append(sample)
                done += 1
                flag = "ok " if sample.ok else "ERR"
                print(
                    f"[{done}/{total_calls}] Q{question_index:02d} r{run} "
                    f"{host.label:<10} {flag} {sample.latency_ms:8.1f}ms",
                    end=line_end,
                    flush=True,
                )
        # 每个问题之间留一点间隔，避免把两台机器都压到排队状态
        if args.interval_ms > 0:
            time.sleep(args.interval_ms / 1000.0)
    print()
    return host_a, host_b


def _join(base: str, path: str) -> str:
    return base.rstrip("/") + "/" + path.lstrip("/")


# ------------------------------------------------------------------ 输出


def write_outputs(args: argparse.Namespace, host_a: Host, host_b: Host, aggregates: list[Aggregate]) -> Path:
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    raw_path = out_dir / "raw.csv"
    with raw_path.open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.writer(fh)
        writer.writerow(
            ["question_index", "target", "run", "ok", "status", "latency_ms",
             "chunk_count", "response_bytes", "request_id", "error", "question"]
        )
        for host in (host_a, host_b):
            for sample in host.samples:
                writer.writerow([
                    sample.question_index, sample.label, sample.run, sample.ok, sample.status,
                    f"{sample.latency_ms:.3f}", sample.chunk_count, sample.response_bytes,
                    sample.request_id, sample.error or "", sample.question,
                ])

    summary_path = out_dir / "summary.csv"
    with summary_path.open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.writer(fh)
        writer.writerow(["target", "calls", "ok", "success_rate",
                         "mean_ms", "p50_ms", "p90_ms", "p99_ms", "min_ms", "max_ms", "std_ms"])
        for agg in aggregates:
            writer.writerow([
                agg.label, agg.total, agg.ok, f"{agg.success_rate:.4f}",
                f"{agg.mean:.1f}", f"{agg.p50:.1f}", f"{agg.p90:.1f}", f"{agg.p99:.1f}",
                f"{agg.min:.1f}", f"{agg.max:.1f}", f"{agg.std:.1f}",
            ])

    median_a = per_question_median(host_a.samples)
    median_b = per_question_median(host_b.samples)
    per_question_path = out_dir / "per_question.csv"
    with per_question_path.open("w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.writer(fh)
        writer.writerow(["question_index", f"{host_a.label}_median_ms", f"{host_b.label}_median_ms",
                         "delta_ms", f"{host_b.label}_slower_ratio"])
        for index in sorted(set(median_a) | set(median_b)):
            a = median_a.get(index)
            b = median_b.get(index)
            delta = (b - a) if (a is not None and b is not None) else ""
            ratio = (b / a) if (a and b) else ""
            writer.writerow([
                index,
                f"{a:.1f}" if a is not None else "",
                f"{b:.1f}" if b is not None else "",
                f"{delta:+.1f}" if isinstance(delta, float) else "",
                f"{ratio:.2f}" if isinstance(ratio, float) else "",
            ])

    report_path = out_dir / "report.md"
    report_path.write_text(render_report(args, host_a, host_b, aggregates, median_a, median_b), encoding="utf-8")
    return out_dir


def render_report(
    args: argparse.Namespace,
    host_a: Host,
    host_b: Host,
    aggregates: list[Aggregate],
    median_a: dict[int, float],
    median_b: dict[int, float],
) -> str:
    lines: list[str] = []
    lines.append("# 检索接口 A/B 性能对比\n")
    lines.append(f"- 目标 A：`{host_a.label}` — {host_a.url}")
    lines.append(f"- 目标 B：`{host_b.label}` — {host_b.url}")
    lines.append(f"- 重复次数：{args.repeat}　warmup：{args.warmup}　问题数：{len(median_a) or len(median_b)}")
    lines.append(f"- 请求体：`{json.dumps(build_payload({}, '<question>', args), ensure_ascii=False)}`\n")

    lines.append("## 汇总\n")
    lines.append("| 目标 | 调用 | 成功 | 成功率 | 均值 | P50 | P90 | P99 | 最小 | 最大 |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for agg in aggregates:
        lines.append(
            f"| {agg.label} | {agg.total} | {agg.ok} | {agg.success_rate:.1%} | {agg.mean:.0f}ms | "
            f"{agg.p50:.0f}ms | {agg.p90:.0f}ms | {agg.p99:.0f}ms | {agg.min:.0f}ms | {agg.max:.0f}ms |"
        )
    lines.append("")

    if len(aggregates) == 2 and aggregates[0].p50 and aggregates[1].p50:
        a, b = aggregates[0], aggregates[1]
        lines.append(
            f"**P50 对比**：{b.label} 是 {a.label} 的 **{b.p50 / a.p50:.2f}x**"
            f"（{a.p50:.0f}ms → {b.p50:.0f}ms，差 {b.p50 - a.p50:+.0f}ms）；"
            f"P99 为 **{b.p99 / a.p99:.2f}x**（{a.p99:.0f}ms → {b.p99:.0f}ms）。\n"
        )

    failures = [s for h in (host_a, host_b) for s in h.samples if not s.ok]
    if failures:
        lines.append("## 失败样本\n")
        lines.append("| 目标 | 问题序号 | 状态 | 错误 |")
        lines.append("|---|---|---|---|")
        for sample in failures[:30]:
            lines.append(f"| {sample.label} | {sample.question_index} | {sample.status} | {(sample.error or '')[:120]} |")
        lines.append("")

    lines.append("## 逐问题中位数\n")
    lines.append(f"| # | {host_a.label} | {host_b.label} | 差值 | B/A | 问题 |")
    lines.append("|---|---|---|---|---|---|")
    question_text = {s.question_index: s.question for h in (host_a, host_b) for s in h.samples}
    rows = []
    for index in sorted(set(median_a) | set(median_b)):
        a = median_a.get(index)
        b = median_b.get(index)
        delta = f"{b - a:+.0f}ms" if (a is not None and b is not None) else "-"
        ratio = f"{b / a:.2f}" if (a and b) else "-"
        text = question_text.get(index, "")
        text = text if len(text) <= 40 else text[:40] + "…"
        rows.append((ratio, index, a, b, delta, text))
    for ratio, index, a, b, delta, text in rows:
        lines.append(
            f"| {index} | {a:.0f}ms | {b:.0f}ms | {delta} | {ratio} | {text} |"
        ) if a is not None and b is not None else lines.append(
            f"| {index} | {a or '-'} | {b or '-'} | {delta} | {ratio} | {text} |"
        )
    lines.append("")

    if rows:
        worst = max(
            (row for row in rows if row[0] != "-"),
            key=lambda row: float(row[0]),
            default=None,
        )
        if worst:
            lines.append(f"**B 相对最慢的一条**：#{worst[1]}（{worst[0]}x，{worst[5] or ''}）\n")

    lines.append("## 怎么读这份报告\n")
    lines.append("- 只看 **P50/P90**：单次最小值受缓存影响大，最大值受 GC 影响大。")
    lines.append("- 成功率必须先相等，否则延迟对比无意义。")
    lines.append("- 两边的 embedding / rerank 服务必须相同，否则差异不来自存储引擎。")
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------------ 入口


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="对两个 rag-core-int 环境的检索接口做 A/B 延迟对比。",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    source = parser.add_argument_group("问题来源")
    source.add_argument("--excel", help="Excel 文件路径；第一列是问题")
    source.add_argument("--sheet", default="Sheet1", help="sheet 名")
    source.add_argument("--column", default="1", help="问题所在列：数字(1) 或字母(A)")
    source.add_argument("--start-row", type=int, default=2, help="问题起始行（第 1 行通常是表头）")
    source.add_argument("--end-row", type=int, default=52, help="问题结束行（含），0 表示读到文件末尾")
    source.add_argument("--questions-file", help="备用：每行一条问题的文本文件")

    target = parser.add_argument_group("目标")
    target.add_argument("--base-a", required=True, help="A 环境地址，如 http://10.0.0.11:8080/api/v1")
    target.add_argument("--label-a", default="milvus", help="A 环境显示名")
    target.add_argument("--base-b", required=True, help="B 环境地址")
    target.add_argument("--label-b", default="kingbase", help="B 环境显示名")
    target.add_argument("--path", default="/retrieval/search", help="检索接口路径")

    payload = parser.add_argument_group("请求体")
    payload.add_argument("--payload-file", help="请求体模板 JSON；query 会被逐条替换")
    payload.add_argument("--plan-type", default="STANDARD_RETRIEVAL",
                         help="PlanType 枚举名；必须是 STANDARD_RETRIEVAL 或 AGENTIC_RAG")
    payload.add_argument("--retrieval-mode", help="SEMANTIC / FULL_TEXT / HYBRID，不传则用知识库默认")
    payload.add_argument("--doc-ids", nargs="*", default=None, help="限定文档范围，不传表示不限")
    payload.add_argument("--header", action="append", default=[], help="额外请求头 KEY=VALUE，可重复")

    run = parser.add_argument_group("执行")
    run.add_argument("--repeat", type=int, default=3, help="每条问题重复次数")
    run.add_argument("--warmup", type=int, default=3, help="正式计时前的预热次数")
    run.add_argument("--timeout", type=float, default=120.0, help="单次请求超时（秒）")
    run.add_argument("--interval-ms", type=int, default=200, help="每条问题之间的间隔（毫秒）")
    run.add_argument("--out", default="bench-results", help="结果输出目录")
    run.add_argument("--dry-run", action="store_true", help="只读问题并打印前几条请求体，不发请求")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if not args.excel and not args.questions_file:
        sys.exit("必须提供 --excel 或 --questions-file")

    template: dict[str, Any] = {}
    if args.payload_file:
        template = json.loads(Path(args.payload_file).read_text(encoding="utf-8"))

    questions = load_questions(args)
    if not questions:
        sys.exit("没有读到任何问题，检查 --sheet/--column/--start-row/--end-row")

    # 去重但不丢顺序，避免同一问题被重复计数
    seen: set[str] = set()
    unique = [q for q in questions if not (q in seen or seen.add(q))]

    print(f"[*] 读到 {len(questions)} 条问题，去重后 {len(unique)} 条")
    if args.end_row:
        expected = args.end_row - args.start_row + 1
        if len(questions) < expected:
            print(f"[!] 预期 {expected} 条（第 {args.start_row}~{args.end_row} 行），实际 {len(questions)} 条，检查空行/合并单元格")

    if args.dry_run:
        for index, question in enumerate(unique[:3], start=1):
            print(f"\n--- 第 {index} 条 ---")
            print(json.dumps(build_payload(template, question, args), ensure_ascii=False, indent=2))
        print(f"\n[*] dry-run：共 {len(unique)} 条问题，未发起请求")
        return

    if requests is None:
        print("[!] 没装 requests，退化为 urllib（每次新建连接），两边一致所以仍然可比，但绝对值会偏高。")

    host_a, host_b = run_benchmark(args, unique, template)
    aggregates = [aggregate(host_a.label, host_a.samples), aggregate(host_b.label, host_b.samples)]

    print("\n=== 汇总 ===")
    print(f"{'目标':<12}{'调用':>6}{'成功':>6}{'均值':>10}{'P50':>10}{'P90':>10}{'P99':>10}")
    for agg in aggregates:
        print(f"{agg.label:<12}{agg.total:>6}{agg.ok:>6}{agg.mean:>9.0f}ms{agg.p50:>9.0f}ms"
              f"{agg.p90:>9.0f}ms{agg.p99:>9.0f}ms")
    if aggregates[0].p50 and aggregates[1].p50:
        a, b = aggregates[0], aggregates[1]
        print(f"\nP50: {b.label} 是 {a.label} 的 {b.p50 / a.p50:.2f}x")
        print(f"P99: {b.label} 是 {a.label} 的 {b.p99 / a.p99:.2f}x")

    out_dir = write_outputs(args, host_a, host_b, aggregates)
    print(f"\n[✓] 结果已写入 {out_dir}/ （report.md / summary.csv / per_question.csv / raw.csv）")


if __name__ == "__main__":
    main()
