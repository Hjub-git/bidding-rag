# -*- coding: utf-8 -*-
"""P-1 基线评测（v2：统一走 matcher 判定器）。

v1 的问题（复评 B3 指出）：文档里的 ECR 已改成 LCS 覆盖率，但本脚本仍算
bag-of-chars，导致"同一题基线 0.9091 / 实验 0.136"两套口径并存。
v2 起，判定与指标全部复用 matcher.py：norm / phrase_in / lcs_ratio，
保证"文档写的判定规则"与"跑出来的数字"是同一套代码。

用法：
    python run_eval.py                     # 增量：库空则先 ingest（默认 seed_80）
    python run_eval.py --reingest          # 清空后重灌
    python run_eval.py --out=runs/c1.json  # 指定输出（默认仍是 baseline_v2.json，别覆盖历史基线）
    python run_eval.py --golden=golden/seed_40.json   # 回退旧口径（35 可答，与 §11 口径表不符）
"""
import os
import sys
import json
import time
import statistics

# 路径全部由本文件位置推导：eval/ 的上一级就是仓库根，仓库搬到哪都能跑。
EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(EVAL_DIR)
RAG_DIR = os.path.join(REPO_ROOT, "rag-service")
# 招标文件样本（仓库不携带）：优先 `--fixture <path>`，其次环境变量 EVAL_FIXTURE。
# 不给也能评测——只要 VECTOR_DB_PATH 指向一个已有向量库（如 rag-service/vector_data）。
FIXTURE = os.environ.get("EVAL_FIXTURE", "")
# 默认题集为 seed_80（70 可答）。
# 原因：seed_40 是 35 可答的旧口径，手跑会得到与 README §9 主数字表
# 不一致的数字；gate.ps1 早已显式传 seed_80，只有"人手动跑"这一条路会拿到旧口径。
# 现役基线 baseline_seed80.json 与 72.9 / 82.9 都是 seed_80 口径（70 可答）。
GOLDEN = os.path.join(EVAL_DIR, "golden", "seed_80.json")
OUT = os.path.join(EVAL_DIR, "baseline_v2.json")

os.environ["DEEPSEEK_API_KEY"] = "sk-x"
os.environ["HF_HUB_OFFLINE"] = "1"
os.environ["TRANSFORMERS_OFFLINE"] = "1"
for _k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(_k, None)
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

sys.path.insert(0, RAG_DIR)
sys.path.insert(0, EVAL_DIR)

from app.core.config import Config  # noqa: E402

# 向量库路径：优先环境变量 VECTOR_DB_PATH，其次 eval/vector_base。
# eval/vector_base 是本地缓存（已进 .gitignore，不入库）；首次用
#   python run_eval.py --reingest --fixture <招标文件.pdf>
# 重建；也可以把 VECTOR_DB_PATH 指向 rag-service/vector_data 直接复用。
VECTOR_DB = os.environ.get("VECTOR_DB_PATH") or os.path.join(EVAL_DIR, "vector_base")
Config.VECTOR_DB_PATH = VECTOR_DB
Config.TOP_K = 10

from matcher import phrase_in, lcs_ratio  # noqa: E402  判定器唯一来源
from _common import TEST_TENDER_ID, wilson95  # noqa: E402  项目号；Wilson95 CI
from app.services.rag_service import RAGService  # noqa: E402


class FakeUpload:
    def __init__(self, path, name):
        self.name = name
        with open(path, "rb") as f:
            self._b = f.read()

    def getvalue(self):
        return self._b


def main():
    reingest = "--reingest" in sys.argv
    out_path = OUT
    golden_path = GOLDEN
    fixture = FIXTURE
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        a = args[i]
        if a.startswith("--out="):
            out_path = a.split("=", 1)[1]
        elif a.startswith("--golden="):
            golden_path = a.split("=", 1)[1]
        elif a.startswith("--fixture="):
            fixture = a.split("=", 1)[1]
        elif a == "--fixture" and i + 1 < len(args):
            i += 1
            fixture = args[i]
        i += 1

    t0 = time.time()
    rs = RAGService()
    init_ms = (time.time() - t0) * 1000

    name = os.path.basename(fixture) if fixture else ""

    # T1：doc_chunk_map 的键从文件名改成 doc_id（这里是 "1"）。
    # 兼容：早期灌的库仍是文件名键，两种都认，避免误判成"没灌过"而重复写入。
    doc_key = "1"
    already = (bool(name) and name in rs.doc_chunk_map) or doc_key in rs.doc_chunk_map

    if reingest:
        # 清掉该 tender 下的全部文档再重灌。
        # 只删 doc_key="1" 会漏删：doc_chunk_map 在生产库里以雪花 doc_id 为键，
        # "1" 根本不在 map 里 → 旧 chunk 不删、新 chunk 又灌一份（实测踩过：74 变 148）。
        for _key, _info in list(rs.doc_chunk_map.items()):
            if _info.get("tender_id") == TEST_TENDER_ID:
                rs.delete_document(_key)
        already = False

    ingest_ms = None
    if not already:
        if not fixture or not os.path.isfile(fixture):
            print("[ERROR] 向量库里没有可评测的文档，需要一份招标文件样本才能重建。")
            print("  用法：python run_eval.py --reingest --fixture <招标文件.pdf>")
            print("  或先设环境变量 EVAL_FIXTURE=<路径>")
            print("  （仓库不携带样本；也可以把 VECTOR_DB_PATH 指向已有向量库，跳过重建）")
            return 2
        t = time.time()
        rs.process_document(FakeUpload(fixture, name),
                            doc_id=int(doc_key), tender_id=TEST_TENDER_ID)
        ingest_ms = (time.time() - t) * 1000

    meta_keys = set()
    for m in (rs._bm25_metadatas or []):
        meta_keys |= set(m.keys())

    golden = json.load(open(golden_path, encoding="utf-8"))
    rows, lat = [], []
    leak_note = "未测：单文档库无第二项目，leak_count 恒为 0（无意义）"
    table_note = "未测：chunk metadata 无 type 字段"

    for it in golden["items"]:
        t = time.time()
        # vector_base 的 58 个 chunk 已打上 tender_id=1，
        # 这里必须传，否则 T2 之后 retrieve 会直接拒绝（禁止全库检索）。
        hits = rs.retrieve(it["question"], tender_id=TEST_TENDER_ID, k=10)
        lat.append((time.time() - t) * 1000)
        texts = [h["content"] for h in hits]

        if it.get("must_refuse"):
            rows.append({"id": it["id"], "category": it["category"], "kind": "refuse",
                         "hit5": None, "hit10": None, "ecr": None,
                         "latency_ms": round(lat[-1], 1)})
            continue

        ev = it.get("evidence") or []
        per_ev = []
        for e in ev:
            q = e["quote"]
            rank = next((i for i, t_ in enumerate(texts, 1) if phrase_in(q, t_)), None)
            cover = max((lcs_ratio(q, t_) for t_ in texts), default=0.0)
            per_ev.append({"rank": rank, "lcs": round(cover, 4)})

        ranks = [p["rank"] for p in per_ev]
        rows.append({
            "id": it["id"], "category": it["category"], "kind": "answer",
            "ranks": ranks,
            "hit5": any(r and r <= 5 for r in ranks),
            "hit10": any(r and r <= 10 for r in ranks),
            "all10": all(r and r <= 10 for r in ranks),
            "ecr": round(sum(p["lcs"] for p in per_ev) / len(per_ev), 4) if per_ev else 0.0,
            "latency_ms": round(lat[-1], 1),
        })

    ans = [r for r in rows if r["kind"] == "answer"]
    ref = [r for r in rows if r["kind"] == "refuse"]

    def pct(k):
        return round(100.0 * sum(1 for r in ans if r[k]) / len(ans), 1) if ans else 0.0

    # 点估计 + Wilson95。n_answerable 显式写进 metrics，
    # 免得以后只看 "hit@5=72.9" 却分不清分母是 70（seed_80）还是 35（seed_40）。
    h5 = sum(1 for r in ans if r["hit5"])
    h10 = sum(1 for r in ans if r["hit10"])
    hit5_ci = wilson95(h5, len(ans))
    hit10_ci = wilson95(h10, len(ans))

    lat_sorted = sorted(lat)
    p95 = lat_sorted[min(len(lat_sorted) - 1, int(0.95 * len(lat_sorted)))] if lat_sorted else 0

    by_cat = {}
    for cat in sorted({r["category"] for r in ans}):
        sub = [r for r in ans if r["category"] == cat]
        by_cat[cat] = {"n": len(sub),
                       "hit5": round(100.0 * sum(r["hit5"] for r in sub) / len(sub), 1),
                       "hit10": round(100.0 * sum(r["hit10"] for r in sub) / len(sub), 1)}

    result = {
        "dataset": name,
        "golden_version": golden["version"],
        "judge": "matcher.py (phrase_in + lcs_ratio)",
        "ci_method": "wilson95",
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "chunker": {"size": Config.CHUNK_SIZE, "overlap": Config.CHUNK_OVERLAP, "top_k": Config.TOP_K},
        "init_ms": round(init_ms),
        "ingest_ms": None if ingest_ms is None else round(ingest_ms),
        "chunk_total": len(rs._bm25_texts or []),
        "n_answerable": len(ans),
        "n_refuse": len(ref),
        "metrics": {
            "hit@5": pct("hit5"),
            "hit@10": pct("hit10"),
            "hit5_ci": hit5_ci,
            "hit10_ci": hit10_ci,
            "n_answerable": len(ans),
            "ci_method": "wilson95",
            "all@10": pct("all10"),
            "ecr@10_mean": round(statistics.mean([r["ecr"] for r in ans]), 4),
            "latency_p50_ms": round(statistics.median(lat), 1),
            "latency_p95_ms": round(p95, 1),
            "leak_count": None,
            "table_hit": None,
        },
        "notes": {"leak_count": leak_note, "table_hit": table_note},
        "by_category": by_cat,
        "refusal": {"n": len(ref), "detectable": False,
                    "reason": "现状无 score、无拒答分支：retrieve 恒返回 chunk"},
        "capability_gaps": {
            "metadata_keys": sorted(meta_keys),
            "has_tender_id": "tender_id" in meta_keys,
            "has_page": "page" in meta_keys or "page_start" in meta_keys,
            "has_section": "section" in meta_keys or "section_no" in meta_keys,
            "has_type": "type" in meta_keys,
            "chunk_size_config": [Config.CHUNK_SIZE, Config.CHUNK_OVERLAP],
            "embedding": Config.EMBEDDING_MODEL,
        },
        "per_item": rows,
    }

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)

    print(f"judge=matcher(phrase_in+lcs)  chunk={result['chunk_total']}  n={len(ans)}+{len(ref)}")
    print(f"Hit@5 {result['metrics']['hit@5']}%  Hit@10 {result['metrics']['hit@10']}%  "
          f"ECR(LCS) {result['metrics']['ecr@10_mean']}")
    print(f"Wilson95 CI  Hit@5 {hit5_ci} ({h5}/{len(ans)})  Hit@10 {hit10_ci} ({h10}/{len(ans)})")
    print(f"p50 {result['metrics']['latency_p50_ms']}ms  p95 {result['metrics']['latency_p95_ms']}ms")
    print(f"leak_count={result['metrics']['leak_count']} (未测)  table_hit={result['metrics']['table_hit']} (未测)")
    print(f"written -> {out_path}")


if __name__ == "__main__":
    sys.exit(main())
