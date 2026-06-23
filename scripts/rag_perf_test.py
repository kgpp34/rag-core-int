#!/usr/bin/env python3
"""
Lightweight non-functional test script for RAG API throughput and latency.

Examples:
  python3 scripts/rag_perf_test.py --url http://localhost:8080/api/v1/rag/answer
  python3 scripts/rag_perf_test.py --concurrency 20 --requests 200 --qps 10
  python3 scripts/rag_perf_test.py --endpoint search --url http://localhost:8080/api/v1/retrieval/search
  python3 scripts/rag_perf_test.py --stream --memory-mode per-request
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import statistics
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Any
from urllib import error, request


DEFAULT_QUERY = "请总结程序化交易异常报送流程。"


@dataclass(frozen=True)
class Result:
    index: int
    ok: bool
    status: int | None
    latency_ms: float
    bytes_read: int
    error: str | None = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a lightweight QPS/concurrency test against RAG APIs."
    )
    parser.add_argument(
        "--url",
        default="http://localhost:8080/api/v1/rag/answer",
        help="Target API URL.",
    )
    parser.add_argument(
        "--endpoint",
        choices=("answer", "search"),
        default="answer",
        help="Payload shape to use.",
    )
    parser.add_argument(
        "--requests",
        type=int,
        default=100,
        help="Total number of requests.",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=10,
        help="Max concurrent in-flight requests.",
    )
    parser.add_argument(
        "--qps",
        type=float,
        default=0,
        help="Target QPS. 0 means send as fast as concurrency allows.",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=120,
        help="Per-request timeout in seconds.",
    )
    parser.add_argument(
        "--query",
        default=DEFAULT_QUERY,
        help="Query text when --queries-file is not provided.",
    )
    parser.add_argument(
        "--queries-file",
        help="Optional file with one query per line. Requests rotate through the file.",
    )
    parser.add_argument(
        "--doc-id",
        action="append",
        dest="doc_ids",
        default=[],
        help="Document ID filter. Can be repeated.",
    )
    parser.add_argument(
        "--user-id",
        default="perf-user",
        help="User ID for answer requests.",
    )
    parser.add_argument(
        "--memory-mode",
        choices=("none", "shared", "per-request"),
        default="none",
        help="Conversation memory strategy for answer requests.",
    )
    parser.add_argument(
        "--stream",
        action="store_true",
        help="Use Accept: text/event-stream for answer requests.",
    )
    parser.add_argument(
        "--payload-file",
        help="Optional JSON payload template. Overrides generated payload except query replacement.",
    )
    parser.add_argument(
        "--header",
        action="append",
        default=[],
        help="Extra HTTP header, for example 'Authorization: Bearer xxx'. Can be repeated.",
    )
    parser.add_argument(
        "--print-errors",
        type=int,
        default=5,
        help="Number of sample errors to print.",
    )
    return parser.parse_args()


def load_queries(path: str | None, fallback: str) -> list[str]:
    if not path:
        return [fallback]
    with open(path, encoding="utf-8") as fh:
        queries = [line.strip() for line in fh if line.strip()]
    return queries or [fallback]


def load_payload_template(path: str | None) -> dict[str, Any] | None:
    if not path:
        return None
    with open(path, encoding="utf-8") as fh:
        payload = json.load(fh)
    if not isinstance(payload, dict):
        raise ValueError("--payload-file must contain a JSON object")
    return payload


def parse_headers(raw_headers: list[str], stream: bool) -> dict[str, str]:
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream" if stream else "application/json",
    }
    for raw in raw_headers:
        name, sep, value = raw.partition(":")
        if not sep or not name.strip():
            raise ValueError(f"Invalid header: {raw!r}")
        headers[name.strip()] = value.strip()
    return headers


def build_payload(
    args: argparse.Namespace,
    template: dict[str, Any] | None,
    query: str,
    index: int,
    shared_conversation_id: str,
) -> dict[str, Any]:
    payload = dict(template) if template else {}
    payload["query"] = query

    if args.endpoint == "search":
        payload.setdefault("docIds", args.doc_ids)
        return payload

    payload.setdefault("userId", args.user_id)
    payload.setdefault("docIds", args.doc_ids)
    if args.memory_mode == "shared":
        payload["memory"] = {"conversationId": shared_conversation_id}
    elif args.memory_mode == "per-request":
        payload["memory"] = {"conversationId": f"perf-{index}-{uuid.uuid4()}"}
    elif "memory" not in payload:
        payload.pop("memory", None)
    return payload


def do_request(
    index: int,
    args: argparse.Namespace,
    headers: dict[str, str],
    queries: list[str],
    payload_template: dict[str, Any] | None,
    shared_conversation_id: str,
) -> Result:
    query = queries[index % len(queries)]
    payload = build_payload(args, payload_template, query, index, shared_conversation_id)
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = request.Request(args.url, data=body, headers=headers, method="POST")

    start = time.perf_counter()
    status = None
    bytes_read = 0
    try:
        with request.urlopen(req, timeout=args.timeout) as resp:
            status = resp.getcode()
            while True:
                chunk = resp.read(8192)
                if not chunk:
                    break
                bytes_read += len(chunk)
        latency_ms = (time.perf_counter() - start) * 1000
        return Result(index, 200 <= status < 300, status, latency_ms, bytes_read)
    except error.HTTPError as exc:
        status = exc.code
        try:
            bytes_read = len(exc.read() or b"")
        except Exception:
            bytes_read = 0
        latency_ms = (time.perf_counter() - start) * 1000
        return Result(index, False, status, latency_ms, bytes_read, str(exc))
    except Exception as exc:
        latency_ms = (time.perf_counter() - start) * 1000
        return Result(index, False, status, latency_ms, bytes_read, repr(exc))


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = (len(ordered) - 1) * pct
    lower = int(rank)
    upper = min(lower + 1, len(ordered) - 1)
    weight = rank - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def print_summary(results: list[Result], elapsed_s: float) -> None:
    latencies = [item.latency_ms for item in results]
    ok_results = [item for item in results if item.ok]
    failed = [item for item in results if not item.ok]
    status_counts: dict[str, int] = {}
    for item in results:
        key = str(item.status) if item.status is not None else "NO_STATUS"
        status_counts[key] = status_counts.get(key, 0) + 1

    print("\n=== RAG Performance Summary ===")
    print(f"Total requests : {len(results)}")
    print(f"Success        : {len(ok_results)}")
    print(f"Failed         : {len(failed)}")
    print(f"Elapsed        : {elapsed_s:.2f}s")
    print(f"Actual QPS     : {(len(results) / elapsed_s) if elapsed_s > 0 else 0:.2f}")
    print(f"Status counts  : {json.dumps(status_counts, ensure_ascii=False, sort_keys=True)}")
    print(f"Bytes read     : {sum(item.bytes_read for item in results)}")

    if latencies:
        print(f"Latency avg    : {statistics.mean(latencies):.2f} ms")
        print(f"Latency min    : {min(latencies):.2f} ms")
        print(f"Latency p50    : {percentile(latencies, 0.50):.2f} ms")
        print(f"Latency p90    : {percentile(latencies, 0.90):.2f} ms")
        print(f"Latency p95    : {percentile(latencies, 0.95):.2f} ms")
        print(f"Latency p99    : {percentile(latencies, 0.99):.2f} ms")
        print(f"Latency max    : {max(latencies):.2f} ms")


def print_errors(results: list[Result], limit: int) -> None:
    errors = [item for item in results if not item.ok and item.error]
    if not errors or limit <= 0:
        return
    print("\n=== Sample Errors ===")
    for item in errors[:limit]:
        print(f"#{item.index} status={item.status} latency={item.latency_ms:.2f}ms error={item.error}")


def main() -> int:
    args = parse_args()
    if args.requests <= 0:
        raise ValueError("--requests must be positive")
    if args.concurrency <= 0:
        raise ValueError("--concurrency must be positive")
    if args.endpoint == "search" and args.stream:
        raise ValueError("--stream is only supported for --endpoint answer")

    queries = load_queries(args.queries_file, args.query)
    payload_template = load_payload_template(args.payload_file)
    headers = parse_headers(args.header, args.stream)
    shared_conversation_id = f"perf-shared-{uuid.uuid4()}"

    print("Target         :", args.url)
    print("Endpoint       :", args.endpoint)
    print("Requests       :", args.requests)
    print("Concurrency    :", args.concurrency)
    print("Target QPS     :", args.qps if args.qps > 0 else "unlimited")
    print("Stream         :", args.stream)
    print("Memory mode    :", args.memory_mode)

    start = time.perf_counter()
    results: list[Result] = []
    futures: set[concurrent.futures.Future[Result]] = set()
    next_submit_at = start

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        submitted = 0
        while submitted < args.requests or futures:
            while submitted < args.requests and len(futures) < args.concurrency:
                if args.qps > 0:
                    now = time.perf_counter()
                    if now < next_submit_at:
                        break
                    next_submit_at += 1.0 / args.qps
                futures.add(pool.submit(
                    do_request,
                    submitted,
                    args,
                    headers,
                    queries,
                    payload_template,
                    shared_conversation_id,
                ))
                submitted += 1

            if futures:
                done, futures = concurrent.futures.wait(
                    futures,
                    timeout=0.05,
                    return_when=concurrent.futures.FIRST_COMPLETED,
                )
                for future in done:
                    results.append(future.result())
            elif args.qps > 0:
                time.sleep(min(max(next_submit_at - time.perf_counter(), 0), 0.05))

    elapsed_s = time.perf_counter() - start
    results.sort(key=lambda item: item.index)
    print_summary(results, elapsed_s)
    print_errors(results, args.print_errors)
    return 0 if all(item.ok for item in results) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nInterrupted", file=sys.stderr)
        raise SystemExit(130)
