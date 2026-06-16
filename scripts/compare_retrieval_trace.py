#!/usr/bin/env python3
import argparse
import hashlib
import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


UUID_RE = re.compile(
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
)
COLLECTION_RE = re.compile(r"Vector_index_(.+?)_Node")
EVENT_START_RE = re.compile(br'\{"source"')
SCORE_TOLERANCE = 1e-6
VECTOR_TOLERANCE = 1e-6


@dataclass
class LoadResult:
    events: list[dict[str, Any]]
    bad_fragments: int = 0
    repaired_fragments: int = 0


@dataclass
class SearchSide:
    request: dict[str, Any] | None = None
    response: dict[str, Any] | None = None


@dataclass
class DatasetTrace:
    dataset_id: str
    dense: SearchSide = field(default_factory=SearchSide)
    sparse: SearchSide = field(default_factory=SearchSide)


def main() -> None:
    parser = argparse.ArgumentParser(description="对比 Dify 和 rag-core-int 的检索 trace JSONL 文件。")
    parser.add_argument("--dify", required=True, help="Dify trace 目录或 events.jsonl 文件")
    parser.add_argument("--rag", required=True, help="rag-core-int trace 目录或 events.jsonl 文件")
    parser.add_argument("--out", required=True, help="Markdown 报告输出路径")
    args = parser.parse_args()

    dify = load_events(Path(args.dify))
    rag = load_events(Path(args.rag))
    report = build_report(dify, rag)
    Path(args.out).write_text(report, encoding="utf-8")


def load_events(path: Path) -> LoadResult:
    file = path if path.is_file() else path / "events.jsonl"
    events: list[dict[str, Any]] = []
    bad = 0
    repaired = 0
    for raw_line in file.read_bytes().splitlines():
        raw_line = raw_line.strip()
        if not raw_line:
            continue
        fragments = [raw_line]
        if not can_parse(raw_line):
            starts = [m.start() for m in EVENT_START_RE.finditer(raw_line)]
            fragments = [raw_line[s:e].strip() for s, e in zip(starts, starts[1:] + [len(raw_line)])] or [raw_line]
        for fragment in fragments:
            try:
                events.append(json.loads(fragment.decode("utf-8")))
                if len(fragments) > 1:
                    repaired += 1
            except (UnicodeDecodeError, json.JSONDecodeError):
                bad += 1
    return LoadResult(events=events, bad_fragments=bad, repaired_fragments=repaired)


def can_parse(raw_line: bytes) -> bool:
    try:
        json.loads(raw_line.decode("utf-8"))
        return True
    except (UnicodeDecodeError, json.JSONDecodeError):
        return False


def build_report(dify_load: LoadResult, rag_load: LoadResult) -> str:
    dify_events = dify_load.events
    rag_events = rag_load.events
    dify_datasets = collect_datasets(dify_events)
    rag_datasets = collect_datasets(rag_events)
    dify_non_empty = non_empty_datasets(dify_datasets)
    rag_non_empty = non_empty_datasets(rag_datasets)
    dataset_ids = sorted(set(dify_non_empty) | set(rag_non_empty))

    lines = ["# 检索 Trace 对比报告", ""]
    lines.extend(summary_section(dify_load, rag_load, dify_non_empty, rag_non_empty))
    lines.extend(query_section(dify_events, rag_events))
    lines.extend(embedding_section(dify_events, rag_events))
    lines.extend(dataset_section(dify_non_empty, rag_non_empty))
    lines.extend(milvus_section(dataset_ids, dify_non_empty, rag_non_empty))
    lines.extend(postprocess_section(dify_events, rag_events, dataset_ids))
    lines.extend(global_ranking_section(dify_events, rag_events))
    return "\n".join(lines) + "\n"


def summary_section(
    dify_load: LoadResult,
    rag_load: LoadResult,
    dify_datasets: dict[str, DatasetTrace],
    rag_datasets: dict[str, DatasetTrace],
) -> list[str]:
    return [
        "## 摘要",
        "",
        f"- Dify 成功解析事件数：{len(dify_load.events)}",
        f"- rag-core-int 成功解析事件数：{len(rag_load.events)}",
        f"- Dify 跳过的损坏片段数：{dify_load.bad_fragments}",
        f"- rag-core-int 跳过的损坏片段数：{rag_load.bad_fragments}",
        f"- rag-core-int 从拼接行中修复出的片段数：{rag_load.repaired_fragments}",
        f"- Dify 非空 Milvus dataset 数：{len(dify_datasets)}",
        f"- rag-core-int 非空 Milvus dataset 数：{len(rag_datasets)}",
        "",
    ]


def query_section(dify_events: list[dict[str, Any]], rag_events: list[dict[str, Any]]) -> list[str]:
    dify_query = first_query(dify_events)
    rag_query = first_query(rag_events)
    return [
        "## 原始 Query",
        "",
        "| 字段 | Dify | rag-core-int | 是否一致 |",
        "| --- | --- | --- | --- |",
        f"| 原始 query | {md(dify_query)} | {md(rag_query)} | {yes_no(dify_query == rag_query)} |",
        "",
    ]


def embedding_section(dify_events: list[dict[str, Any]], rag_events: list[dict[str, Any]]) -> list[str]:
    dify = first_payload(dify_events, "embedding.query")
    rag = first_payload(rag_events, "embedding.query")
    dify_vector = vector_payload(dify)
    rag_vector = vector_payload(rag)
    raw_cmp = compare_vectors(dify_vector.get("vector"), rag_vector.get("vector"), normalize=False)
    normalized_cmp = compare_vectors(dify_vector.get("vector"), rag_vector.get("vector"), normalize=True)
    return [
        "## Embedding 对比",
        "",
        "| 字段 | Dify | rag-core-int | 是否一致 |",
        "| --- | --- | --- | --- |",
        f"| 输入文本 | {md(dify.get('input'))} | {md(rag.get('input'))} | {yes_no(dify.get('input') == rag.get('input'))} |",
        f"| 模型 | {md(dify.get('model'))} | {md(rag.get('model'))} | {yes_no(dify.get('model') == rag.get('model'))} |",
        f"| 维度 | {md(dify_vector.get('dim'))} | {md(rag_vector.get('dim'))} | {yes_no(dify_vector.get('dim') == rag_vector.get('dim'))} |",
        f"| L2 范数 | {fmt_float(dify_vector.get('l2Norm'))} | {fmt_float(rag_vector.get('l2Norm'))} | {yes_no(close_float(dify_vector.get('l2Norm'), rag_vector.get('l2Norm')))} |",
        f"| 记录的 sha256 | {short_hash(dify_vector.get('sha256'))} | {short_hash(rag_vector.get('sha256'))} | {yes_no(dify_vector.get('sha256') == rag_vector.get('sha256'))} |",
        f"| 原始向量最大绝对差 | {raw_cmp['max_abs_diff']} |  | {yes_no(raw_cmp['same'])} |",
        f"| 原始向量余弦相似度 | {raw_cmp['cosine']} |  |  |",
        f"| 正则化后向量最大绝对差 | {normalized_cmp['max_abs_diff']} |  | {yes_no(normalized_cmp['same'])} |",
        f"| 正则化后向量余弦相似度 | {normalized_cmp['cosine']} |  |  |",
        "",
    ]


def dataset_section(
    dify_datasets: dict[str, DatasetTrace],
    rag_datasets: dict[str, DatasetTrace],
) -> list[str]:
    only_dify = len(set(dify_datasets) - set(rag_datasets))
    only_rag = len(set(rag_datasets) - set(dify_datasets))
    both = len(set(dify_datasets) & set(rag_datasets))
    return [
        "## Dataset 覆盖情况",
        "",
        f"- 非空 dataset 集合是否一致：{yes_no(set(dify_datasets) == set(rag_datasets))}",
        f"- 两边都有的 dataset 数：{both}",
        f"- 仅 Dify 有的 dataset 数：{only_dify}",
        f"- 仅 rag-core-int 有的 dataset 数：{only_rag}",
        "",
        "| Dataset ID | Dify 是否检索 | rag-core-int 是否检索 | Dify filter 文档数 | rag-core-int filter 文档数 | 文档数是否一致 | 向量检索请求 | 关键词检索请求 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
        *dataset_rows(sorted(set(dify_datasets) | set(rag_datasets)), dify_datasets, rag_datasets),
        "",
    ]


def dataset_rows(
    dataset_ids: list[str],
    dify_datasets: dict[str, DatasetTrace],
    rag_datasets: dict[str, DatasetTrace],
) -> list[str]:
    rows = []
    for dataset_id in dataset_ids:
        dify = dify_datasets.get(dataset_id)
        rag = rag_datasets.get(dataset_id)
        dify_doc_count = dataset_filter_count(dify)
        rag_doc_count = dataset_filter_count(rag)
        rows.append(
            "| "
            + " | ".join(
                [
                    md(dataset_id),
                    yes_no(dify is not None),
                    yes_no(rag is not None),
                    count_text(dify_doc_count),
                    count_text(rag_doc_count),
                    yes_no(dify_doc_count is not None and dify_doc_count == rag_doc_count),
                    request_match_text(dify.dense.request if dify else None, rag.dense.request if rag else None),
                    request_match_text(dify.sparse.request if dify else None, rag.sparse.request if rag else None),
                ]
            )
            + " |"
        )
    return rows


def milvus_section(
    dataset_ids: list[str],
    dify_datasets: dict[str, DatasetTrace],
    rag_datasets: dict[str, DatasetTrace],
) -> list[str]:
    lines = [
        "## Milvus 请求与返回",
        "",
        "下表按正则化后的 collection 名排序。报告刻意隐藏 UUID；一致性按 document_id 集合、候选文本哈希和分数计算。",
        "",
        "| Dataset ID | Dify 向量请求 | rag 向量请求 | 向量返回 | Dify 关键词请求 | rag 关键词请求 | 关键词返回 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for dataset_id in dataset_ids:
        dify = dify_datasets.get(dataset_id)
        rag = rag_datasets.get(dataset_id)
        dense_cmp = compare_response(dify.dense.response if dify else None, rag.dense.response if rag else None)
        sparse_cmp = compare_response(dify.sparse.response if dify else None, rag.sparse.response if rag else None)
        lines.append(
            "| "
            + " | ".join(
                [
                    md(dataset_id),
                    request_summary(dify.dense.request if dify else None),
                    request_summary(rag.dense.request if rag else None),
                    response_summary(dense_cmp),
                    request_summary(dify.sparse.request if dify else None),
                    request_summary(rag.sparse.request if rag else None),
                    response_summary(sparse_cmp),
                ]
            )
            + " |"
        )
    lines.append("")
    lines.extend(candidate_detail_section(dataset_ids, dify_datasets, rag_datasets))
    return lines


def postprocess_section(
    dify_events: list[dict[str, Any]],
    rag_events: list[dict[str, Any]],
    dataset_ids: list[str],
) -> list[str]:
    dify_kb_final = collect_dataset_payloads(dify_events, {"kb.final", "retrieval.dataset.response"})
    rag_kb_final = collect_dataset_payloads(rag_events, {"kb.final"})
    dify_kb_rerank = collect_dataset_payloads(dify_events, {"kb.rerank.response"})
    rag_kb_rerank = collect_dataset_payloads(rag_events, {"kb.rerank.response"})
    dify_kb_skipped = collect_dataset_payloads(dify_events, {"kb.rerank.skipped"})
    rag_kb_skipped = collect_dataset_payloads(rag_events, {"kb.rerank.skipped"})
    all_dataset_ids = sorted(set(dataset_ids) | set(dify_kb_final) | set(rag_kb_final))

    lines = [
        "## 单库后处理与 Rerank",
        "",
        "这一节比较 Milvus 返回之后、每个 dataset 自己输出之前的变化。候选一致性按文本 hash 有序列表和分数判断。",
        "",
        "| Dataset ID | Dify 单库输出 | rag 单库输出 | 单库输出一致 | Dify 单库 rerank | rag 单库 rerank | 单库 rerank 输出一致 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for dataset_id in all_dataset_ids:
        dify_final = dify_kb_final.get(dataset_id)
        rag_final = rag_kb_final.get(dataset_id)
        dify_rerank = dify_kb_rerank.get(dataset_id)
        rag_rerank = rag_kb_rerank.get(dataset_id)
        lines.append(
            "| "
            + " | ".join(
                [
                    md(dataset_id),
                    stage_summary(dify_final),
                    stage_summary(rag_final),
                    compare_stage_summary(dify_final, rag_final),
                    rerank_state(dify_rerank, dify_kb_skipped.get(dataset_id)),
                    rerank_state(rag_rerank, rag_kb_skipped.get(dataset_id)),
                    compare_stage_summary(dify_rerank, rag_rerank),
                ]
            )
            + " |"
        )
    lines.append("")
    return lines


def global_ranking_section(dify_events: list[dict[str, Any]], rag_events: list[dict[str, Any]]) -> list[str]:
    rows = [
        ("多库合并后，rerank 前", "global.merge.before_rerank"),
        ("全局 rerank 请求", "global.rerank.request"),
        ("全局 rerank 返回", "global.rerank.response"),
        ("最终输出", "global.final"),
        ("Workflow 最终输出", "workflow.retrieval.final"),
    ]
    lines = [
        "## 多库合并与全局 Rerank",
        "",
        "| 阶段 | Dify | rag-core-int | 候选是否一致 |",
        "| --- | --- | --- | --- |",
    ]
    for title, event_name in rows:
        dify = first_payload(dify_events, event_name)
        rag = first_payload(rag_events, event_name)
        lines.append(
            "| "
            + " | ".join(
                [
                    md(title),
                    stage_summary(dify),
                    stage_summary(rag),
                    compare_stage_summary(dify, rag),
                ]
            )
            + " |"
        )

    dify_skip = first_payload(dify_events, "global.rerank.skipped")
    rag_skip = first_payload(rag_events, "global.rerank.skipped")
    if dify_skip or rag_skip:
        lines.extend([
            "",
            f"- Dify 全局 rerank 跳过原因：{md(dify_skip.get('reason')) if dify_skip else '未跳过'}",
            f"- rag-core-int 全局 rerank 跳过原因：{md(rag_skip.get('reason')) if rag_skip else '未跳过'}",
        ])
    lines.append("")
    return lines


def candidate_detail_section(
    dataset_ids: list[str],
    dify_datasets: dict[str, DatasetTrace],
    rag_datasets: dict[str, DatasetTrace],
) -> list[str]:
    lines = [
        "## 召回内容明细",
        "",
        "按知识库分组展示 Dify 与 rag-core-int 各路召回的候选文本预览（前 50 字）与分数。",
        "",
    ]
    for dataset_id in dataset_ids:
        dify = dify_datasets.get(dataset_id)
        rag = rag_datasets.get(dataset_id)
        lines.append(f"### {md(dataset_id)}")
        lines.append("")

        # Dense
        dify_dense = candidates(dify.dense.response) if dify else []
        rag_dense = candidates(rag.dense.response) if rag else []
        lines.extend(candidate_table("向量召回", dify_dense, rag_dense))

        # Sparse
        dify_sparse = candidates(dify.sparse.response) if dify else []
        rag_sparse = candidates(rag.sparse.response) if rag else []
        lines.extend(candidate_table("关键词召回", dify_sparse, rag_sparse))

    return lines


def candidate_table(
    route_name: str,
    dify_candidates: list[dict[str, Any]],
    rag_candidates: list[dict[str, Any]],
) -> list[str]:
    lines = [
        f"**{route_name}**",
        "",
        "| 排名 | Dify 文本预览 | Dify 分数 | rag 文本预览 | rag 分数 |",
        "| --- | --- | --- | --- | --- |",
    ]
    max_rows = max(len(dify_candidates), len(rag_candidates))
    for rank in range(max_rows):
        dify_c = dify_candidates[rank] if rank < len(dify_candidates) else None
        rag_c = rag_candidates[rank] if rank < len(rag_candidates) else None
        lines.append(
            "| "
            + " | ".join([
                str(rank + 1),
                candidate_text_preview(dify_c),
                candidate_score_text(dify_c),
                candidate_text_preview(rag_c),
                candidate_score_text(rag_c),
            ])
            + " |"
        )
    lines.append("")
    return lines


def candidate_text_preview(candidate: dict[str, Any] | None) -> str:
    if candidate is None:
        return ""
    text = candidate.get("textPreview", "")
    if not text:
        text = candidate.get("content", "")
    return md(text[:50])


def candidate_score_text(candidate: dict[str, Any] | None) -> str:
    if candidate is None:
        return ""
    for key in ["score", "rankingScore", "vectorScore", "sparseScore"]:
        val = candidate.get(key)
        if val is not None:
            return fmt_float(val)
    return ""


def collect_datasets(events: list[dict[str, Any]]) -> dict[str, DatasetTrace]:
    datasets: dict[str, DatasetTrace] = {}
    for event in events:
        name = event.get("event")
        payload = event.get("payload", {})
        request = payload.get("request", payload)
        collection = request.get("collectionName") or payload.get("collectionName")
        dataset_id = dataset_key(payload, collection)
        if not dataset_id:
            continue
        trace = datasets.setdefault(dataset_id, DatasetTrace(dataset_id=dataset_id))
        if name == "milvus.dense.request":
            trace.dense.request = request
        elif name == "milvus.sparse.request":
            trace.sparse.request = request
        elif name == "milvus.dense.response":
            trace.dense.response = payload
        elif name == "milvus.sparse.response":
            trace.sparse.response = payload
    return datasets


def collect_dataset_payloads(
    events: list[dict[str, Any]],
    event_names: set[str],
) -> dict[str, dict[str, Any]]:
    payloads: dict[str, dict[str, Any]] = {}
    for event in events:
        if event.get("event") not in event_names:
            continue
        payload = event.get("payload", {})
        dataset_id = dataset_key(payload, payload.get("collectionName"))
        if not dataset_id and payload.get("datasetId"):
            dataset_id = normalize_id(str(payload["datasetId"]))
        if dataset_id:
            payloads[dataset_id] = payload
    return payloads


def non_empty_datasets(datasets: dict[str, DatasetTrace]) -> dict[str, DatasetTrace]:
    return {
        dataset_id: trace
        for dataset_id, trace in datasets.items()
        if any(
            count is not None and count > 0
            for count in [
                filter_doc_count(trace.dense.request),
                filter_doc_count(trace.sparse.request),
            ]
        )
    }


def dataset_key(payload: dict[str, Any], collection: str | None) -> str | None:
    if payload.get("knowledgeBaseId"):
        return normalize_id(payload["knowledgeBaseId"])
    if collection:
        match = COLLECTION_RE.search(collection)
        if match:
            return normalize_id(match.group(1))
    return None


def normalize_id(value: str) -> str:
    return value.replace("_", "-")


def first_query(events: list[dict[str, Any]]) -> Any:
    for event_name in ["retrieval.multiple.request", "retrieval.request", "query.preprocess"]:
        payload = first_payload(events, event_name)
        for key in ["query", "rawQuery", "effectiveQuery"]:
            if payload.get(key):
                return payload[key]
    return None


def first_payload(events: list[dict[str, Any]], event_name: str) -> dict[str, Any]:
    for event in events:
        if event.get("event") == event_name:
            return event.get("payload", {})
    return {}


def vector_payload(payload: dict[str, Any]) -> dict[str, Any]:
    vector = payload.get("vector", {})
    return vector if isinstance(vector, dict) else {}


def compare_vectors(left: Any, right: Any, normalize: bool) -> dict[str, Any]:
    if not isinstance(left, list) or not isinstance(right, list) or len(left) != len(right):
        return {"same": False, "max_abs_diff": "不可用", "cosine": "不可用"}
    lvec = [float(v) for v in left]
    rvec = [float(v) for v in right]
    if normalize:
        lvec = normalize_vector(lvec)
        rvec = normalize_vector(rvec)
    max_abs_diff = max(abs(a - b) for a, b in zip(lvec, rvec)) if lvec else 0.0
    cosine = cosine_similarity(lvec, rvec)
    return {
        "same": max_abs_diff <= VECTOR_TOLERANCE,
        "max_abs_diff": fmt_float(max_abs_diff),
        "cosine": fmt_float(cosine),
    }


def normalize_vector(vector: list[float]) -> list[float]:
    norm = math.sqrt(sum(v * v for v in vector))
    if norm == 0:
        return vector
    return [v / norm for v in vector]


def cosine_similarity(left: list[float], right: list[float]) -> float:
    left_norm = math.sqrt(sum(v * v for v in left))
    right_norm = math.sqrt(sum(v * v for v in right))
    if left_norm == 0 or right_norm == 0:
        return 0.0
    return sum(a * b for a, b in zip(left, right)) / (left_norm * right_norm)


def request_match_text(left: dict[str, Any] | None, right: dict[str, Any] | None) -> str:
    if left is None and right is None:
        return "两边均无"
    if left is None or right is None:
        return "缺失"
    checks = [
        ("topK 不一致", left.get("topK") == right.get("topK")),
        ("filter 文档不一致", filter_doc_ids(left) == filter_doc_ids(right)),
        ("检索字段不一致", left.get("annsField") == right.get("annsField")),
    ]
    if all(ok for _, ok in checks):
        return "一致"
    return ", ".join(name for name, ok in checks if not ok)


def request_summary(request: dict[str, Any] | None) -> str:
    if request is None:
        return "缺失"
    return f"topK={request.get('topK')}，filter 文档数={count_text(filter_doc_count(request))}，检索字段={md(request.get('annsField'))}"


def compare_response(left: dict[str, Any] | None, right: dict[str, Any] | None) -> dict[str, Any]:
    if left is None and right is None:
        return {"status": "两边均无", "left": 0, "right": 0, "overlap": 0}
    left_candidates = candidates(left)
    right_candidates = candidates(right)
    left_keys = [candidate_key(c) for c in left_candidates]
    right_keys = [candidate_key(c) for c in right_candidates]
    ordered_same = left_keys == right_keys and scores_same(left_candidates, right_candidates)
    overlap = len(set(left_keys) & set(right_keys))
    return {
        "status": "一致" if ordered_same else "不一致",
        "left": len(left_candidates),
        "right": len(right_candidates),
        "overlap": overlap,
    }


def response_summary(cmp: dict[str, Any]) -> str:
    status = cmp["status"]
    if status == "两边均无":
        return status
    return f"{status}；返回数量 {cmp['left']}/{cmp['right']}；重叠 {cmp['overlap']}"


def candidates(response: dict[str, Any] | None) -> list[dict[str, Any]]:
    if not response:
        return []
    return response.get("candidates", []) or response.get("chunks", []) or response.get("resources", []) or []


def stage_summary(payload: dict[str, Any] | None) -> str:
    if not payload:
        return "缺失"
    rows = candidates(payload)
    count = payload.get("count", payload.get("outputCount", payload.get("inputCount", len(rows))))
    extra = []
    if payload.get("rerankingMode"):
        extra.append(f"模式={payload.get('rerankingMode')}")
    if payload.get("rankingMode"):
        extra.append(f"排序={payload.get('rankingMode')}")
    if payload.get("topK") is not None:
        extra.append(f"topK={payload.get('topK')}")
    if payload.get("scoreThreshold") is not None:
        extra.append(f"阈值={fmt_float(payload.get('scoreThreshold'))}")
    suffix = "，" + "，".join(extra) if extra else ""
    return f"数量={count}{suffix}"


def rerank_state(response: dict[str, Any] | None, skipped: dict[str, Any] | None) -> str:
    if response:
        return stage_summary(response)
    if skipped:
        return f"跳过：{md(skipped.get('reason'))}"
    return "缺失"


def compare_stage_summary(left: dict[str, Any] | None, right: dict[str, Any] | None) -> str:
    cmp = compare_response(left, right)
    return response_summary(cmp)


def candidate_key(candidate: dict[str, Any]) -> str:
    text_hash = candidate.get("textHash")
    if text_hash:
        return str(text_hash)
    preview = candidate.get("textPreview")
    if preview:
        return hashlib.sha256(str(preview).encode("utf-8")).hexdigest()
    return ""


def scores_same(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> bool:
    if len(left) != len(right):
        return False
    return all(close_float(candidate_score(l), candidate_score(r)) for l, r in zip(left, right))


def candidate_score(candidate: dict[str, Any]) -> Any:
    for key in ["score", "rankingScore", "vectorScore", "sparseScore"]:
        if candidate.get(key) is not None:
            return candidate.get(key)
    return None


def dataset_filter_count(trace: DatasetTrace | None) -> int | None:
    if trace is None:
        return None
    dense_count = filter_doc_count(trace.dense.request)
    sparse_count = filter_doc_count(trace.sparse.request)
    if dense_count is not None:
        return dense_count
    return sparse_count


def filter_doc_count(request: dict[str, Any] | None) -> int | None:
    ids = filter_doc_ids(request)
    if ids is None:
        return None
    return len(ids)


def filter_doc_ids(request: dict[str, Any] | None) -> set[str] | None:
    if not request:
        return None
    filter_text = request.get("filter")
    if not filter_text:
        return None
    return set(UUID_RE.findall(str(filter_text)))


def count_text(value: int | None) -> str:
    return "缺失" if value is None else str(value)


def close_float(left: Any, right: Any, tolerance: float = SCORE_TOLERANCE) -> bool:
    try:
        return abs(float(left) - float(right)) <= tolerance
    except (TypeError, ValueError):
        return left == right


def yes_no(value: bool) -> str:
    return "是" if value else "否"


def fmt_float(value: Any) -> str:
    if value is None:
        return ""
    try:
        return f"{float(value):.12g}"
    except (TypeError, ValueError):
        return str(value)


def short_hash(value: Any) -> str:
    if not value:
        return ""
    return str(value)[:16]


def md(value: Any) -> str:
    text = "" if value is None else str(value)
    return text.replace("|", "\\|").replace("\n", " ")[:240]


if __name__ == "__main__":
    main()
