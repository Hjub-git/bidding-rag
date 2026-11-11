# -*- coding: utf-8 -*-
"""O2 query 改写评测：唯一能度量「口语 27 题 Hit@5」的入口。

为什么不能用 run_eval.py
------------------------
`run_eval.py` 走的是 `rs.retrieve(it["question"])`，直接进检索层，
不经过 `qa_service` 的改写层。所以它对 O2 是瞎的：改写改得再好，跑 run_eval
分数也不会动。本脚本补的就是这条管线：

    27 条口语题 → 改写（人工上界 或 LLM）→ retrieve(z-score/α=0.3/cand=30) → hit@5

三种模式
--------
    python o2_rewrite_eval.py --mode=base     # 原始口语 query（现状基线）
    python o2_rewrite_eval.py --mode=manual   # 人工改写上界（_rewrite_strict.json）
    python o2_rewrite_eval.py --mode=llm      # 生产改写层（调 qa_service）

口径（必须一致，否则数字不可比）
  - 库：eval/vector_base（74 chunk，seed_80 用的同一个）
  - 检索：生产路径 `RAGService.retrieve(tender_id=1, k=10)`，即 where + BM25 分片 + z-score RSF α=0.3 cand=30
  - 命中：`matcher.phrase_in(evidence.quote, chunk)`，任一 evidence 进 top-k 即算命中
  - 题集：seed_80 里 `colloquial=true` 且 `must_refuse=false` 的 27 条

输出：eval/o2_rewrite_result.json（含绝对数，如 10/27）
"""
import argparse
import json
import os
import sys

# 路径全部由本文件位置推导：eval/ 的上一级就是仓库根。
EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(EVAL_DIR)
RAG_DIR = os.path.join(REPO_ROOT, "rag-service")
GOLDEN = os.path.join(EVAL_DIR, "golden", "seed_80.json")
REWRITE_STRICT = os.path.join(EVAL_DIR, "_rewrite_strict.json")
OUT = os.path.join(EVAL_DIR, "o2_rewrite_result.json")

os.environ["DEEPSEEK_API_KEY"] = os.environ.get("DEEPSEEK_API_KEY", "sk-x")
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
for _k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(_k, None)

sys.path.insert(0, RAG_DIR)
sys.path.insert(0, EVAL_DIR)

from app.core.config import Config  # noqa: E402

# 同 run_eval.py：VECTOR_DB_PATH 环境变量优先，其次 eval/vector_base（本地缓存，不入库）
VECTOR_DB = os.environ.get("VECTOR_DB_PATH") or os.path.join(EVAL_DIR, "vector_base")
Config.VECTOR_DB_PATH = VECTOR_DB
Config.TOP_K = 10

from matcher import phrase_in  # noqa: E402
from _common import wilson95  # noqa: E402  Wilson95 CI
from app.services.rag_service import RAGService  # noqa: E402


def load_items():
    g = json.load(open(GOLDEN, encoding="utf-8"))
    return [i for i in g["items"] if i.get("colloquial") and not i.get("must_refuse")]


def rank_of(rs, query, quotes):
    """返回该题 evidence 在 top-10 里的最高名次（1-based），未命中返回 None。"""
    rs._clear_cache()
    hits = rs.retrieve(query, tender_id=1, k=10)
    texts = [h["content"] for h in hits]
    best = None
    for q in quotes:
        for idx, t in enumerate(texts, 1):
            if phrase_in(q, t):
                best = idx if best is None else min(best, idx)
                break
    return best


def rewrite_llm(rs, query):
    """生产改写层（O2 实现后这里返回改写后的 query）。

    当前 O2 未实现 → 显式报错，绝不静默退回原 query（否则数字会被误读成"改写无效"）。
    """
    try:
        from app.services.qa_service import QAService
        qs = QAService(rs)
        import asyncio
        intent, rewritten = asyncio.run(qs._classify_and_rewrite_async(query))
        return intent, rewritten
    except AttributeError:
        raise SystemExit(
            "--mode=llm 需要 qa_service._classify_and_rewrite_async（O2 尚未实现）。\n"
            "先做 O2，再回来跑这个模式。")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["base", "manual", "llm"], default="base")
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()

    items = load_items()
    print(f"口语可答 {len(items)} 题，模式={args.mode}")

    rs = RAGService()
    rows = []
    for it in items:
        quotes = [e["quote"] for e in it["evidence"]]
        if args.mode == "base":
            used = it["question"]
        elif args.mode == "manual":
            m = json.load(open(REWRITE_STRICT, encoding="utf-8"))
            used = m.get(it["id"]) or it["question"]
        else:
            _intent, used = rewrite_llm(rs, it["question"])
        r = rank_of(rs, used, quotes)
        rows.append({"id": it["id"], "query_used": used, "rank": r,
                     "hit5": r is not None and r <= 5, "hit10": r is not None and r <= 10})
        print(f'  {it["id"]} rank={"-" if r is None else r:<3} {used[:36]}')

    n = len(rows)
    h5 = sum(1 for r in rows if r["hit5"])
    h10 = sum(1 for r in rows if r["hit10"])
    # 27 题的抽样噪声极大（1 题 = 3.7pp），必须带 CI 才敢说"改写有效/无效"。
    hit5_ci = wilson95(h5, n)
    hit10_ci = wilson95(h10, n)
    payload = {
        "mode": args.mode,
        "n": n,
        "hit@5": round(100 * h5 / n, 1),
        "hit@10": round(100 * h10 / n, 1),
        "hit5_ci": hit5_ci,
        "hit10_ci": hit10_ci,
        "ci_method": "wilson95",
        "abs@5": f"{h5}/{n}",
        "abs@10": f"{h10}/{n}",
        "lib": "eval/vector_base (74 chunk)",
        "retrieval": "RAGService.retrieve(tender_id=1,k=10) = where + BM25 分片 + z-score RSF α=0.3 cand=30",
        "judge": "matcher.phrase_in, 任一 evidence 进 top-k 即命中",
        "note": "验收锚点：O2 的 --mode=llm 要求 hit@5 >= 60%，即 >=17/27",
        "items": rows,
    }
    json.dump(payload, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f'\n[{args.mode}] Hit@5 {payload["hit@5"]}% ({h5}/{n})  Hit@10 {payload["hit@10"]}% ({h10}/{n})')
    print(f'Wilson95 CI  Hit@5 {hit5_ci}  Hit@10 {hit10_ci}')
    print(f"written -> {args.out}")
    try:
        rs.vector_db._client.close()
    except Exception:
        pass


if __name__ == "__main__":
    main()
